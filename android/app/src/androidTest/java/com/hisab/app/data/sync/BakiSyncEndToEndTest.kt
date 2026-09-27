package com.hisab.app.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.baki.BakiReverseResult
import com.hisab.app.data.baki.BakiRepository
import com.hisab.app.data.customer.CustomerRepository
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Step 58 for real: a hand-written baki entry, over HTTP, against the actual
 * server and its Postgres. Nothing is faked.
 *
 * Same shape as `SaleSyncEndToEndTest` — it needs the development server
 * running and reachable from the phone:
 *
 *     cd server && npm start
 *     adb reverse tcp:3000 tcp:3000
 *
 * Without that, every test here skips itself rather than failing, because a
 * missing server is not a broken app. CI has no phone, so CI never runs it.
 */
@RunWith(AndroidJUnit4::class)
class BakiSyncEndToEndTest {
    private val baseUrl = "http://127.0.0.1:3000"
    private val email = "rahim@example.com"
    private val password = "correct-horse-1"

    private lateinit var database: HisabDatabase
    private lateinit var baki: BakiRepository
    private lateinit var customers: CustomerRepository
    private lateinit var settings: SyncSettings
    private lateinit var api: HttpSyncApi
    private var token: String = ""

    // Not lateinit: EntityId is a value class, so it cannot be one. Set in setUp.
    private var customerId = EntityId("")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .getSharedPreferences("hisab_sync", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        api = HttpSyncApi(baseUrl)
        token =
            runBlocking {
                try {
                    api.login(email, password)
                } catch (error: Exception) {
                    Assume.assumeNoException(
                        "needs the development server (npm start) reachable from the phone " +
                            "(adb reverse tcp:3000 tcp:3000)",
                        error,
                    )
                    ""
                }
            }

        database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        baki = BakiRepository(database)
        customers = CustomerRepository(database)
        settings = SyncSettings(context)
        settings.serverUrl = baseUrl
        settings.email = email
        settings.token = token

        runBlocking {
            val marker = "E2E-${UUID.randomUUID().toString().take(8)}"
            val customer = customers.findOrCreate("$marker রহিম")
            customerId = EntityId(customer.id)
            // The customer must reach the server before a baki entry naming
            // them can (D004's "the customer before the baki that names them").
            SyncEngine(database, api, settings).sync()
        }
    }

    @After
    fun tearDown() {
        if (this::database.isInitialized) database.close()
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSharedPreferences("hisab_sync", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun engine() = SyncEngine(database, api, settings)

    private fun serverCall(
        method: String,
        path: String,
        body: JSONObject? = null,
    ): JSONObject {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** What the server says this customer owes — its own SUM over its own ledger (D001). */
    private fun serverBalance(): Long = serverCall("GET", "/customers/${customerId.value}/baki").getLong("balance")

    private suspend fun phoneBalance(): Long = database.bakiEntryDao().balancePoisha(customerId.value)

    private suspend fun assertBalanceMatches(expected: Long) {
        assertEquals("on the phone", expected, phoneBalance())
        assertEquals("on the server", expected, serverBalance())
    }

    // Step 54, synced: Add Baki → Confirm, then the shopkeeper syncs.
    @Test
    fun creditAddedByHandOnThePhoneReachesTheServer(): Unit =
        runBlocking {
            baki.addCredit(customerId, Money(50_000), null)

            val report = engine().sync()

            assertEquals(1, report.pushed)
            assertTrue(database.syncOutboxDao().all().isEmpty())
            assertBalanceMatches(50_000)
        }

    // Step 55, synced: Receive Payment → Confirm, then synced.
    @Test
    fun aPaymentReceivedOnThePhoneReducesTheBalanceOnTheServerToo(): Unit =
        runBlocking {
            baki.addCredit(customerId, Money(50_000), null)
            engine().sync()

            baki.receivePayment(customerId, Money(20_000))
            val report = engine().sync()

            assertEquals(1, report.pushed)
            assertBalanceMatches(30_000)
        }

    // Step 57, synced: Undo → confirm, then synced.
    @Test
    fun undoingAHandWrittenEntryOnThePhoneClearsItOnTheServerToo(): Unit =
        runBlocking {
            val credit = baki.addCredit(customerId, Money(50_000), null)
            engine().sync()
            assertBalanceMatches(50_000)

            val reversed = baki.reverse(credit.id) as BakiReverseResult.Reversed
            val report = engine().sync()

            assertEquals(1, report.pushed)
            val onServer = serverCall("GET", "/customers/${customerId.value}/baki")
            assertEquals(
                "the entry and its undo are both there",
                2,
                onServer.getJSONArray("entries").length(),
            )
            assertEquals(reversed.reversal.id.value, onServer.getJSONArray("entries").getJSONObject(0).getString("id"))
            assertBalanceMatches(0)
        }

    @Test
    fun theServerRefusesASecondUndoOfTheSameEntry(): Unit =
        runBlocking {
            val credit = baki.addCredit(customerId, Money(50_000), null)
            engine().sync()
            baki.reverse(credit.id)
            engine().sync()

            // Another device got there first, as far as this server is
            // concerned: the same entry, undone again under a fresh id.
            val secondReversalId = UUID.randomUUID().toString()
            val secondUndo =
                serverCall(
                    "POST",
                    "/sync/push",
                    JSONObject().put(
                        "events",
                        JSONArray().put(
                            JSONObject()
                                .put("eventId", UUID.randomUUID().toString())
                                .put("entityType", "BakiEntry")
                                .put("entityId", secondReversalId)
                                .put("operation", "create")
                                .put(
                                    "payload",
                                    JSONObject()
                                        .put("id", secondReversalId)
                                        .put("customerId", customerId.value)
                                        .put("amountDelta", -50_000)
                                        .put("type", "entry_reversal")
                                        .put("reference", credit.id.value)
                                        .put("dueDate", JSONObject.NULL)
                                        .put(
                                            "time",
                                            JSONObject()
                                                .put("occurredAt", "2026-09-27T10:00:00Z")
                                                .put("serverReceivedAt", JSONObject.NULL),
                                        ),
                                ).put("baseRevision", JSONObject.NULL)
                                .put("clientTimestamp", "2026-09-27T10:00:00Z"),
                        ),
                    ),
                )

            assertEquals(
                "rejected",
                secondUndo.getJSONArray("results").getJSONObject(0).getString("status"),
            )
            assertEquals(
                "ALREADY_REVERSED",
                secondUndo.getJSONArray("results").getJSONObject(0).getString("code"),
            )
            assertBalanceMatches(0)
        }

    // The other direction: added straight on the server (as a shop's own
    // records might be adjusted outside the app), then the phone catches up.
    @Test
    fun creditAddedOnTheServerReachesThePhone(): Unit =
        runBlocking {
            serverCall(
                "POST",
                "/baki/entries",
                JSONObject().put("customerId", customerId.value).put("type", "credit").put("amountPoisha", 30_000),
            )

            val report = engine().sync()

            assertTrue(report.pulled > 0)
            assertBalanceMatches(30_000)
        }

    @Test
    fun aPhoneThatSyncsTwiceDoesNotApplyItsOwnEntryTwice(): Unit =
        runBlocking {
            baki.addCredit(customerId, Money(50_000), null)

            engine().sync()
            engine().sync()

            assertBalanceMatches(50_000)
        }

    @Test
    fun aSyncedEntryKnowsItReachedTheServer(): Unit =
        runBlocking {
            val credit = baki.addCredit(customerId, Money(50_000), null)

            engine().sync()

            assertNotNull(database.bakiEntryDao().byId(credit.id.value)!!.serverReceivedAt)
        }
}
