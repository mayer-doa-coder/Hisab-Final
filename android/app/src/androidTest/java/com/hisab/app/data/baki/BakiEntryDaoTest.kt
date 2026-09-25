package com.hisab.app.data.baki

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.domain.BakiEntry
import com.hisab.app.domain.BakiEntryType
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SaleLine
import com.hisab.app.domain.addCredit
import com.hisab.app.domain.calculateBalance
import com.hisab.app.domain.completeCreditSale
import com.hisab.app.domain.generateId
import com.hisab.app.domain.receivePayment
import com.hisab.app.domain.reverseEntry
import com.hisab.app.domain.reverseSale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

/**
 * Step 51: the BakiEntry table in Room. Insert and read back.
 *
 * Runs against a real Room database held in memory. BakiEntry is a ledger
 * entity — no `revision`, no `deletedAt`, never edited in place — so what these
 * tests check is that whatever the domain functions build survives the trip
 * through the table unchanged, and that a balance can only ever be a sum of
 * rows (D001).
 */
@RunWith(AndroidJUnit4::class)
class BakiEntryDaoTest {
    private lateinit var database: HisabDatabase
    private lateinit var dao: BakiEntryDao

    private val at = Instant.parse("2026-09-24T10:00:00Z")
    private val rahim = EntityId("rahim")
    private val karim = EntityId("karim")

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        dao = database.bakiEntryDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private suspend fun store(vararg entries: BakiEntry) = entries.forEach { dao.insert(it.toEntity()) }

    private suspend fun stored(id: EntityId): BakiEntry = dao.byId(id.value)!!.toDomain()

    @Test
    fun everyKindOfEntryIsWrittenAndReadBackUnchanged() =
        runBlocking<Unit> {
            val sale =
                completeCreditSale(
                    "shop",
                    rahim,
                    listOf(SaleLine(generateId(), Quantity(1000), Money(9_000))),
                    at,
                    LocalDate.of(2026, 10, 1),
                )
            val credit = addCredit(rahim, Money(50_000), at.plusSeconds(1), LocalDate.of(2026, 11, 5))
            val payment = receivePayment(rahim, Money(20_000), at.plusSeconds(2))
            val entries =
                listOf(
                    sale.bakiEntry!!,
                    reverseSale(sale, at.plusSeconds(3)).bakiEntry!!,
                    credit,
                    payment,
                    reverseEntry(credit, at.plusSeconds(4)),
                )
            store(*entries.toTypedArray())

            entries.forEach { assertEquals("${it.type}", it, stored(it.id)) }
            assertEquals(
                "all five types were exercised",
                BakiEntryType.entries.toSet(),
                entries.map { it.type }.toSet(),
            )
        }

    @Test
    fun aPaymentIsStoredNegativeAndReadBackNegative() =
        runBlocking<Unit> {
            val payment = receivePayment(rahim, Money(20_000), at)
            store(payment)

            assertEquals(-20_000L, dao.byId(payment.id.value)!!.amountDeltaPoisha)
            assertEquals(Money(-20_000), stored(payment.id).amountDelta)
        }

    @Test
    fun theDueDateIsKeptAsADateWithNoClockTime() =
        runBlocking<Unit> {
            val withDate = addCredit(rahim, Money(100), at, LocalDate.of(2026, 10, 15))
            val without = addCredit(rahim, Money(100), at)
            store(withDate, without)

            assertEquals(LocalDate.of(2026, 10, 15), stored(withDate.id).dueDate)
            assertNull(stored(without.id).dueDate)
        }

    @Test
    fun aHandWrittenEntryNamesNothingAndAReversalNamesItsOriginal() =
        runBlocking<Unit> {
            val credit = addCredit(rahim, Money(100), at)
            val undone = reverseEntry(credit, at.plusSeconds(1))
            store(credit, undone)

            assertNull(stored(credit.id).reference)
            assertEquals(credit.id.value, stored(undone.id).reference)
        }

    @Test
    fun anEntryIsNotYetKnownToTheServerUntilTheServerSaysSo() =
        runBlocking<Unit> {
            val credit = addCredit(rahim, Money(100), at)
            store(credit)
            assertNull(dao.byId(credit.id.value)!!.serverReceivedAt)

            val received = at.plusSeconds(600)
            dao.markServerReceived(credit.id.value, received)
            dao.markServerReceived(credit.id.value, received.plusSeconds(600))

            val row = dao.byId(credit.id.value)!!
            assertEquals("the first acknowledgement stands", received, row.serverReceivedAt)
            assertEquals("acknowledging never touches the money", credit.amountDelta, Money(row.amountDeltaPoisha))
            assertEquals("or when it happened", at, row.occurredAt)
        }

    @Test
    fun theSameIdCannotBeInsertedTwice() =
        runBlocking<Unit> {
            val credit = addCredit(rahim, Money(100), at, id = EntityId("e1"))
            store(credit)

            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { dao.insert(addCredit(rahim, Money(999), at, id = EntityId("e1")).toEntity()) }
            }
            assertEquals("the first entry is untouched", Money(100), stored(credit.id).amountDelta)
        }

    @Test
    fun aCustomersEntriesComeBackOldestFirstAndNobodyElses() =
        runBlocking<Unit> {
            val first = addCredit(rahim, Money(1), at, id = EntityId("a"))
            val second = receivePayment(rahim, Money(1), at.plusSeconds(60), id = EntityId("b"))
            val third = addCredit(rahim, Money(1), at.plusSeconds(120), id = EntityId("c"))
            val strangers = addCredit(karim, Money(1), at.plusSeconds(30), id = EntityId("d"))
            store(third, strangers, first, second)

            assertEquals(listOf("a", "b", "c"), dao.forCustomer(rahim.value).map { it.id })
        }

    @Test
    fun entriesRecordedAtTheSameMomentComeBackInAStableOrder() =
        runBlocking<Unit> {
            store(
                addCredit(rahim, Money(1), at, id = EntityId("b")),
                addCredit(rahim, Money(1), at, id = EntityId("a")),
            )

            assertEquals(listOf("a", "b"), dao.forCustomer(rahim.value).map { it.id })
        }

    @Test
    fun aCustomerWithNoEntriesOwesNothing() =
        runBlocking<Unit> {
            assertEquals(0L, dao.balancePoisha("nobody"))
        }

    @Test
    fun theBalanceIsTheSumOfTheEntriesAndTheDatabaseAgreesWithTheDomainFunction() =
        runBlocking<Unit> {
            // The Step 56 numbers: 500 in baki, 200 received, 100 more added.
            val entries =
                listOf(
                    addCredit(rahim, Money(50_000), at),
                    receivePayment(rahim, Money(20_000), at.plusSeconds(1)),
                    addCredit(rahim, Money(10_000), at.plusSeconds(2)),
                )
            store(*entries.toTypedArray())

            assertEquals(40_000L, dao.balancePoisha(rahim.value))
            assertEquals(calculateBalance(entries, rahim).minorUnits, dao.balancePoisha(rahim.value))
        }

    @Test
    fun oneCustomersBalanceNeverIncludesAnothers() =
        runBlocking<Unit> {
            store(addCredit(rahim, Money(500), at), addCredit(karim, Money(70), at), receivePayment(karim, Money(20), at))

            assertEquals(500L, dao.balancePoisha(rahim.value))
            assertEquals(50L, dao.balancePoisha(karim.value))
        }

    @Test
    fun overpayingLeavesANegativeBalance() =
        runBlocking<Unit> {
            store(addCredit(rahim, Money(10_000), at), receivePayment(rahim, Money(15_000), at.plusSeconds(1)))

            assertEquals(-5_000L, dao.balancePoisha(rahim.value))
        }

    @Test
    fun undoingAnEntryAddsARowAndLeavesTheOriginalAsItWas() =
        runBlocking<Unit> {
            val credit = addCredit(rahim, Money(50_000), at, LocalDate.of(2026, 10, 1))
            store(credit)
            val before = dao.byId(credit.id.value)

            store(reverseEntry(credit, at.plusSeconds(60)))

            assertEquals("the original is never edited", before, dao.byId(credit.id.value))
            assertEquals("both rows are history", 2, dao.forCustomer(rahim.value).size)
            assertEquals("and together they owe nothing", 0L, dao.balancePoisha(rahim.value))
        }

    @Test
    fun aReversalIsFoundFromTheEntryItUndoes() =
        runBlocking<Unit> {
            val credit = addCredit(rahim, Money(100), at)
            val undone = reverseEntry(credit, at.plusSeconds(1))
            store(credit, undone)

            assertEquals(listOf(undone.id.value), dao.forReference(credit.id.value).map { it.id })
            assertNotNull(dao.byId(undone.id.value))
        }

    @Test
    fun aSalesBakiAndItsReversalAreBothFoundFromTheSale() =
        runBlocking<Unit> {
            val sale = completeCreditSale("shop", rahim, listOf(SaleLine(generateId(), Quantity(1000), Money(9_000))), at)
            val undone = reverseSale(sale, at.plusSeconds(1))
            store(sale.bakiEntry!!, undone.bakiEntry!!)

            assertEquals(
                listOf(BakiEntryType.CREDIT_SALE.name, BakiEntryType.REVERSAL.name),
                dao.forReference(sale.sale.id.value).map { it.entryType },
            )
            assertEquals(0L, dao.balancePoisha(rahim.value))
        }

    @Test
    fun thereIsNoStoredBalanceAnywhereInTheLedgerTable() =
        runBlocking<Unit> {
            // D001: the table holds entries and nothing that could be edited
            // into disagreeing with them.
            val columns = columnsOf("baki_entry")

            assertEquals(
                setOf(
                    "id",
                    "customerId",
                    "amountDeltaPoisha",
                    "entryType",
                    "reference",
                    "dueDateEpochDay",
                    "occurredAt",
                    "serverReceivedAt",
                ),
                columns,
            )
            assertFalse("a ledger row has no revision", "revision" in columns)
            assertFalse("and is never soft-deleted", "deletedAt" in columns)
            assertFalse(columns.any { it.equals("balance", ignoreCase = true) })
        }

    private fun columnsOf(table: String): Set<String> =
        database.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(nameColumn)) }
        }
}
