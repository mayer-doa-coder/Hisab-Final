package com.hisab.app.data.customer

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Step 50: the Customer table in Room. Insert and read back.
 *
 * Runs against a real Room database held in memory, so nothing is left on the
 * phone when the tests finish. Customer is a mutable entity, so unlike the
 * ledger tables it carries `revision`, `updatedAt` and `deletedAt` (D017) —
 * and, like every entity, it stores no balance, ever (D001).
 */
@RunWith(AndroidJUnit4::class)
class CustomerDaoTest {
    private lateinit var database: HisabDatabase
    private lateinit var dao: CustomerDao

    private val shop = "shop-under-test"
    private val now = Instant.parse("2026-09-24T10:00:00Z")

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        dao = database.customerDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun customer(
        id: String,
        name: String,
        phone: String? = null,
        shopId: String = shop,
        revision: Int = 1,
        updatedAt: Instant = now,
        deletedAt: Instant? = null,
    ) = CustomerEntity(id, shopId, name, phone, revision, updatedAt, deletedAt)

    private suspend fun listed(query: String = "") = dao.observe(shop, query).first().map { it.name }

    @Test
    fun aCustomerIsWrittenAndReadBackUnchanged() =
        runBlocking<Unit> {
            val written = customer("c1", "Rahim", phone = "01711000000", revision = 3, updatedAt = now.plusSeconds(90))
            dao.insert(written)

            assertEquals(written, dao.byId("c1"))
        }

    @Test
    fun theOptionalPhoneCanBeAbsent() =
        runBlocking<Unit> {
            dao.insert(customer("c1", "Rahim"))

            assertNull(dao.byId("c1")!!.phone)
        }

    @Test
    fun aNewCustomerStartsAtRevisionOneWithNoDeletion() =
        runBlocking<Unit> {
            dao.insert(customer("c1", "Rahim"))
            val stored = dao.byId("c1")!!

            assertEquals(1, stored.revision)
            assertNull("a live customer has no deletion time", stored.deletedAt)
            assertEquals(now, stored.updatedAt)
        }

    @Test
    fun aDeletedCustomerIsATombstoneThatStillReadsBack() =
        runBlocking<Unit> {
            val gone = now.plusSeconds(3600)
            dao.insert(customer("c1", "Rahim", revision = 2, deletedAt = gone))

            val stored = dao.byId("c1")
            assertNotNull("deletion sets a time instead of removing the row (D017)", stored)
            assertEquals(gone, stored!!.deletedAt)
            assertEquals(2, stored.revision)
        }

    @Test
    fun aBanglaNameAndItsSearchRoundTrip() =
        runBlocking<Unit> {
            dao.insert(customer("c1", "রহিম মিয়া"))
            dao.insert(customer("c2", "করিম"))

            assertEquals("রহিম মিয়া", dao.byId("c1")!!.name)
            assertEquals(listOf("রহিম মিয়া"), listed("রহিম"))
            assertEquals(listOf("করিম"), listed("রিম"))
        }

    @Test
    fun aCustomerIsFoundByNameIgnoringCase() =
        runBlocking<Unit> {
            dao.insert(customer("c1", "Rahim"))

            assertEquals("c1", dao.byName(shop, "rahim")?.id)
            assertEquals("c1", dao.byName(shop, "RAHIM")?.id)
            assertNull("only an exact name matches, not part of one", dao.byName(shop, "Rahi"))
        }

    @Test
    fun aDeletedCustomerIsNotFoundByName() =
        runBlocking<Unit> {
            dao.insert(customer("c1", "Rahim", deletedAt = now))

            assertNull(dao.byName(shop, "Rahim"))
        }

    @Test
    fun anotherShopsCustomerIsNotFoundOrListed() =
        runBlocking<Unit> {
            dao.insert(customer("c1", "Rahim", shopId = "some-other-shop"))

            assertNull(dao.byName(shop, "Rahim"))
            assertTrue(listed().isEmpty())
            assertNotNull("the row itself is there — it is only scoped out", dao.byId("c1"))
        }

    @Test
    fun customersAreListedByNameWithDeletedOnesLeftOut() =
        runBlocking<Unit> {
            dao.insert(customer("c1", "Karim"))
            dao.insert(customer("c2", "anwar"))
            dao.insert(customer("c3", "Zakir", deletedAt = now))
            dao.insert(customer("c4", "Belal"))

            assertEquals(listOf("anwar", "Belal", "Karim"), listed())
        }

    @Test
    fun searchNarrowsTheList() =
        runBlocking<Unit> {
            dao.insert(customer("c1", "Karim Uddin"))
            dao.insert(customer("c2", "Rahim Uddin"))
            dao.insert(customer("c3", "Belal"))

            assertEquals(listOf("Karim Uddin", "Rahim Uddin"), listed("uddin"))
            assertTrue(listed("nobody").isEmpty())
        }

    @Test
    fun theSameIdCannotBeInsertedTwice() =
        runBlocking<Unit> {
            dao.insert(customer("c1", "Rahim"))

            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { dao.insert(customer("c1", "Someone else")) }
            }
            assertEquals("Rahim", dao.byId("c1")!!.name)
        }

    @Test
    fun savingWhatTheServerSentReplacesTheRowOrAddsIt() =
        runBlocking<Unit> {
            dao.upsert(customer("c1", "Rahim"))
            dao.upsert(customer("c1", "Rahim Mia", revision = 2, updatedAt = now.plusSeconds(60)))

            val stored = dao.byId("c1")!!
            assertEquals("Rahim Mia", stored.name)
            assertEquals(2, stored.revision)
            assertEquals(1, dao.observe(shop, "").first().size)
        }

    @Test
    fun thereIsNoStoredBalanceOnACustomer() =
        runBlocking<Unit> {
            // D001: what a customer owes is the sum of their baki entries. If a
            // balance column ever appears here, two numbers could disagree.
            val columns = columnsOf("customer")

            assertEquals(
                setOf("id", "shopId", "name", "phone", "revision", "updatedAt", "deletedAt"),
                columns,
            )
            assertFalse(columns.any { it.contains("balance", ignoreCase = true) || it.contains("baki", ignoreCase = true) })
        }

    private fun columnsOf(table: String): Set<String> =
        database.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(nameColumn)) }
        }
}
