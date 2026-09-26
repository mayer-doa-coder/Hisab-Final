package com.hisab.app.data.baki

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.domain.BakiEntryType
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SaleLine
import com.hisab.app.domain.calculateBalance
import com.hisab.app.domain.completeCreditSale
import com.hisab.app.domain.generateId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Step 57: undoing a hand-written baki entry, against a real Room database held
 * in memory.
 *
 * What matters here is that an undo is a *new* entry, that it is written at
 * most once however the taps arrive, and that a credit sale's baki cannot be
 * undone on its own (D021).
 */
@RunWith(AndroidJUnit4::class)
class BakiRepositoryTest {
    private lateinit var database: HisabDatabase
    private lateinit var repository: BakiRepository
    private lateinit var dao: BakiEntryDao

    private val rahim = EntityId("rahim")

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        repository = BakiRepository(database)
        dao = database.bakiEntryDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private suspend fun balance() = dao.balancePoisha(rahim.value)

    private suspend fun rows() = dao.forCustomer(rahim.value)

    // The Step 56 numbers, then Step 57's check: undo one, and the balance is right again.
    @Test
    fun undoingThePaymentPutsTheBalanceBackToWhatItWouldHaveBeen() =
        runBlocking<Unit> {
            repository.addCredit(rahim, Money(50_000), null)
            val payment = repository.receivePayment(rahim, Money(20_000))
            repository.addCredit(rahim, Money(10_000), null)
            assertEquals("500 - 200 + 100", 40_000L, balance())

            val result = repository.reverse(payment.id)

            assertTrue(result is BakiReverseResult.Reversed)
            assertEquals("400 + the 200 that was never really paid", 60_000L, balance())
            assertEquals("three entries and their undo", 4, rows().size)
        }

    @Test
    fun undoingACreditLowersTheBalance() =
        runBlocking<Unit> {
            repository.addCredit(rahim, Money(50_000), null)
            val credit = repository.addCredit(rahim, Money(10_000), null)

            repository.reverse(credit.id)

            assertEquals(50_000L, balance())
        }

    @Test
    fun theUndoIsANewEntryThatNamesTheOriginalAndTheOriginalIsUntouched() =
        runBlocking<Unit> {
            val credit = repository.addCredit(rahim, Money(50_000), null)
            val before = dao.byId(credit.id.value)

            val result = repository.reverse(credit.id) as BakiReverseResult.Reversed

            assertEquals("the original row is never edited", before, dao.byId(credit.id.value))
            assertEquals(BakiEntryType.ENTRY_REVERSAL, result.reversal.type)
            assertEquals(credit.id.value, result.reversal.reference)
            assertEquals(Money(-50_000), result.reversal.amountDelta)
            assertEquals(rahim, result.reversal.customerId)
            // The database keeps milliseconds; the entry in hand keeps finer time. Compare
            // at the precision that is stored, so the test checks the entry, not the clock.
            val inHand =
                result.reversal.copy(
                    time =
                        result.reversal.time.copy(
                            occurredAt =
                                result.reversal.time.occurredAt
                                    .truncatedTo(ChronoUnit.MILLIS),
                        ),
                )
            assertEquals("both are stored", inHand, dao.byId(result.reversal.id.value)!!.toDomain())
        }

    @Test
    fun aSecondUndoOfTheSameEntryIsRefusedAndWritesNothing() =
        runBlocking<Unit> {
            val credit = repository.addCredit(rahim, Money(50_000), null)
            repository.reverse(credit.id)
            val rowsAfterFirst = rows().size

            val again = repository.reverse(credit.id)

            assertEquals(BakiReverseResult.AlreadyReversed, again)
            assertEquals(rowsAfterFirst, rows().size)
            assertEquals(0L, balance())
        }

    // Two taps at once, or a tap while the first is still being written: the
    // check and the write are one transaction, so exactly one of them wins.
    @Test
    fun twoUndosAtTheSameMomentWriteExactlyOneOppositeEntry() =
        runBlocking<Unit> {
            val credit = repository.addCredit(rahim, Money(50_000), null)

            val results = (1..8).map { async(Dispatchers.Default) { repository.reverse(credit.id) } }.awaitAll()

            assertEquals(1, results.count { it is BakiReverseResult.Reversed })
            assertEquals(7, results.count { it == BakiReverseResult.AlreadyReversed })
            assertEquals("the credit and one undo, not eight", 2, rows().size)
            assertEquals(0L, balance())
        }

    @Test
    fun aCreditSalesBakiCannotBeUndoneOnItsOwn() =
        runBlocking<Unit> {
            val sale =
                completeCreditSale(
                    "shop",
                    rahim,
                    listOf(SaleLine(generateId(), Quantity(1000), Money(9_000))),
                    Instant.parse("2026-09-26T10:00:00Z"),
                )
            dao.insert(sale.bakiEntry!!.toEntity())

            assertEquals(BakiReverseResult.NotReversible, repository.reverse(sale.bakiEntry!!.id))

            assertEquals("nothing was written", 1, rows().size)
            assertEquals(9_000L, balance())
        }

    @Test
    fun anUndoCannotItselfBeUndone() =
        runBlocking<Unit> {
            val credit = repository.addCredit(rahim, Money(50_000), null)
            val undone = repository.reverse(credit.id) as BakiReverseResult.Reversed

            assertEquals(BakiReverseResult.NotReversible, repository.reverse(undone.reversal.id))

            assertEquals(2, rows().size)
        }

    @Test
    fun anEntryThatDoesNotExistIsNotFound() =
        runBlocking<Unit> {
            assertEquals(BakiReverseResult.NotFound, repository.reverse(EntityId("nope")))
            assertEquals(0, rows().size)
        }

    @Test
    fun theBalanceAfterUndoingMatchesTheDomainFunction() =
        runBlocking<Unit> {
            val a = repository.addCredit(rahim, Money(50_000), null)
            repository.receivePayment(rahim, Money(20_000))

            repository.reverse(a.id)

            val entries = rows().map { it.toDomain() }
            assertEquals(calculateBalance(entries, rahim).minorUnits, balance())
            assertEquals(-20_000L, balance())
        }
}
