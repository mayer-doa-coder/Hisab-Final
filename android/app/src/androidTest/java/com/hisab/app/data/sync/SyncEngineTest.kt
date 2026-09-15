package com.hisab.app.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.LOCAL_SHOP_ID
import com.hisab.app.data.product.ProductDraft
import com.hisab.app.data.product.ProductRepository
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Steps 32 and 33 on the phone's side, against a stand-in server, so the
 * behaviour is tested without a network: what gets sent, what happens to a
 * queued change afterwards, and what a pull does to the local data.
 */
@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    private lateinit var database: HisabDatabase
    private lateinit var repository: ProductRepository
    private lateinit var settings: SyncSettings
    private lateinit var api: FakeSyncApi

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clearSavedSettings(context)
        database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        repository = ProductRepository(database)
        settings = SyncSettings(context)
        settings.email = "rahim@example.com"
        settings.token = "test-token"
        api = FakeSyncApi()
    }

    @After
    fun tearDown() {
        database.close()
        clearSavedSettings(ApplicationProvider.getApplicationContext())
    }

    private fun clearSavedSettings(context: Context) {
        context
            .getSharedPreferences("hisab_sync", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun engine() = SyncEngine(database, api, settings)

    private fun serverProduct(
        id: String,
        name: String,
        revision: Int = 1,
        deletedAt: String? = null,
        sellingPricePoisha: Long = 2500,
    ) = PulledChange(
        entityType = "Product",
        entityId = id,
        operation =
            if (deletedAt != null) {
                "delete"
            } else if (revision == 1) {
                "create"
            } else {
                "update"
            },
        payload =
            JSONObject()
                .put("name", name)
                .put("aliases", org.json.JSONArray(listOf("fromserver")))
                .put("unit", "piece")
                .put("sellingPricePoisha", sellingPricePoisha)
                .put("purchasePricePoisha", JSONObject.NULL)
                .put("active", true)
                .put("revision", revision)
                .put("updatedAt", "2026-09-14T10:00:00Z")
                .put("deletedAt", deletedAt ?: JSONObject.NULL),
    )

    // Step 32: what was queued offline goes out, and stops being queued.
    @Test
    fun queuedChangesAreSentAndThenCleared(): Unit =
        runBlocking {
            val saved = repository.create(ProductDraft(name = "চিনি", sellingPrice = Money(8500)))

            val report = engine().sync()

            assertEquals(1, api.pushedEvents.size)
            assertEquals(saved.id.value, api.pushedEvents.first().entityId)
            assertEquals("create", api.pushedEvents.first().operation)
            assertEquals(1, report.pushed)
            assertTrue(database.syncOutboxDao().all().isEmpty())
        }

    @Test
    fun nothingIsSentWhenThereIsNothingQueued(): Unit =
        runBlocking {
            val report = engine().sync()

            assertTrue(api.pushedEvents.isEmpty())
            assertEquals(0, report.pushed)
        }

    // Step 34 on the phone: the server refused the edit, so the queued change
    // is kept and marked rather than thrown away or retried blindly.
    @Test
    fun aRefusedChangeIsKeptAndMarkedAsAConflict(): Unit =
        runBlocking {
            repository.create(ProductDraft(name = "Coke", sellingPrice = Money(2000)))
            api.pushStatus = PushResult.STATUS_CONFLICT
            api.pushCode = "REVISION_CONFLICT"

            val report = engine().sync()

            assertEquals(1, report.conflicts)
            assertEquals(0, report.pushed)
            val kept = database.syncOutboxDao().all().single()
            assertEquals(SyncOutboxEntity.STATUS_CONFLICT, kept.status)
            assertEquals(1, kept.retryCount)
        }

    @Test
    fun aRejectedChangeIsMarkedTooSoItStopsBeingResent(): Unit =
        runBlocking {
            repository.create(ProductDraft(name = "Coke", sellingPrice = Money(2000)))
            api.pushStatus = PushResult.STATUS_REJECTED
            api.pushCode = "INVALID_PAYLOAD"

            val report = engine().sync()

            assertEquals(1, report.rejected)
            assertEquals(
                SyncOutboxEntity.STATUS_REJECTED,
                database
                    .syncOutboxDao()
                    .all()
                    .single()
                    .status,
            )
        }

    // Step 33: a product the phone has never seen arrives from the server.
    @Test
    fun productsFromTheServerAreSavedOnThePhone(): Unit =
        runBlocking {
            api.pulled = listOf(serverProduct("server-1", "Server চিনি", sellingPricePoisha = 9000))
            api.pullCursor = 7

            val report = engine().sync()

            assertEquals(1, report.pulled)
            val stored = repository.byId(EntityId("server-1"))
            assertNotNull(stored)
            assertEquals("Server চিনি", stored!!.name)
            assertEquals(9000L, stored.sellingPricePoisha)
            assertEquals(LOCAL_SHOP_ID, stored.shopId)
            assertEquals(listOf("fromserver"), stored.aliases)
            assertTrue(repository.observe().first().any { it.id == "server-1" })
        }

    @Test
    fun anEditFromTheServerReplacesWhatThePhoneHad(): Unit =
        runBlocking {
            api.pulled = listOf(serverProduct("server-2", "Old name"))
            engine().sync()

            api.pulled = listOf(serverProduct("server-2", "New name", revision = 2))
            engine().sync()

            val stored = repository.byId(EntityId("server-2"))!!
            assertEquals("New name", stored.name)
            assertEquals(2, stored.revision)
        }

    @Test
    fun aDeletionFromTheServerTakesTheProductOutOfTheList(): Unit =
        runBlocking {
            api.pulled = listOf(serverProduct("server-3", "Going away"))
            engine().sync()
            assertTrue(repository.observe().first().any { it.id == "server-3" })

            api.pulled =
                listOf(
                    serverProduct("server-3", "Going away", revision = 2, deletedAt = "2026-09-14T11:00:00Z"),
                )
            engine().sync()

            assertTrue(repository.observe(includeInactive = true).first().none { it.id == "server-3" })
            assertNotNull(repository.byId(EntityId("server-3"))!!.deletedAt)
        }

    @Test
    fun theNextSyncAsksFromWhereTheLastOneStopped(): Unit =
        runBlocking {
            // The first sync has to actually receive something, or there is no
            // new place to carry on from.
            api.pulled = listOf(serverProduct("server-cursor", "Cursor check"))
            api.pullCursor = 12
            engine().sync()

            api.pulled = emptyList()
            engine().sync()

            assertEquals(listOf(0L, 12L), api.requestedCursors)
        }

    // Nothing new to receive must not move the place it carries on from
    // backwards, or the phone would ask for the same changes forever.
    @Test
    fun aSyncWithNothingNewKeepsThePlaceItCarriesOnFrom(): Unit =
        runBlocking {
            api.pulled = listOf(serverProduct("server-cursor-2", "Cursor check"))
            api.pullCursor = 9
            engine().sync()

            api.pulled = emptyList()
            engine().sync()
            engine().sync()

            assertEquals(listOf(0L, 9L, 9L), api.requestedCursors)
        }

    @Test
    fun signingInNeedsAPasswordWhenThereIsNoSavedSession(): Unit =
        runBlocking {
            settings.token = null

            val failure =
                try {
                    engine().sync()
                    null
                } catch (error: NotSignedInException) {
                    error
                }

            assertNotNull(failure)
            assertTrue(api.pushedEvents.isEmpty())
        }

    @Test
    fun aPasswordIsExchangedForATokenThatIsThenReused(): Unit =
        runBlocking {
            settings.token = null

            engine().sync(password = "correct-horse-1")

            assertEquals(1, api.logins)
            assertEquals("issued-token", settings.token)

            engine().sync()
            assertEquals("only the first sync signs in", 1, api.logins)
        }

    @Test
    fun aChangeThatArrivedFromTheServerIsNotSentBack(): Unit =
        runBlocking {
            api.pulled = listOf(serverProduct("server-4", "Echo check"))
            engine().sync()
            api.pushedEvents.clear()

            engine().sync()

            assertTrue(api.pushedEvents.isEmpty())
            assertNull(database.syncOutboxDao().all().firstOrNull())
        }
}

/** A stand-in server: records what it was sent, returns what the test set up. */
private class FakeSyncApi : SyncApi {
    val pushedEvents = mutableListOf<SyncOutboxEntity>()
    val requestedCursors = mutableListOf<Long>()
    var logins = 0

    var pushStatus: String = PushResult.STATUS_APPLIED
    var pushCode: String? = null
    var pulled: List<PulledChange> = emptyList()
    var pullCursor: Long = 0

    override suspend fun login(
        email: String,
        password: String,
    ): String {
        logins += 1
        return "issued-token"
    }

    override suspend fun push(
        token: String,
        events: List<SyncOutboxEntity>,
    ): List<PushResult> {
        pushedEvents += events
        return events.map { PushResult(it.eventId, pushStatus, pushCode) }
    }

    override suspend fun pull(
        token: String,
        afterCursor: Long,
    ): PullResult {
        requestedCursors += afterCursor
        return PullResult(pulled, if (pulled.isEmpty()) afterCursor else pullCursor)
    }
}
