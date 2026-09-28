package com.hisab.app.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Steps 68 and 69 on the phone's side, and Step 70's sizes: what the sync
 * engine does when the network, the server or the app itself gives out part
 * way through.
 *
 * Every case here is checked against the same two promises: nothing the
 * shopkeeper confirmed is lost, and nothing is sent twice. A stand-in server
 * is used rather than a real one so each failure can be produced exactly,
 * on purpose, instead of hoped for.
 */
@RunWith(AndroidJUnit4::class)
class SyncFailureTest {
    private lateinit var database: HisabDatabase
    private lateinit var products: ProductRepository
    private lateinit var settings: SyncSettings
    private lateinit var api: UnreliableSyncApi

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clearSavedSettings(context)
        database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        products = ProductRepository(database)
        settings = SyncSettings(context)
        settings.email = "rahim@example.com"
        settings.token = "test-token"
        api = UnreliableSyncApi()
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

    private suspend fun queueOneProduct(name: String = "চিনি") = products.create(ProductDraft(name = name, sellingPrice = Money(8500)))

    private suspend fun pending() = database.syncOutboxDao().withStatus(SyncOutboxEntity.STATUS_PENDING)

    // --- Step 68: offline and network problems ---

    // "offline operation". The shopkeeper's work is already saved; the sync
    // simply cannot happen yet. Nothing about the queue may change.
    @Test
    fun aSyncWithNoNetworkLeavesTheQueueExactlyAsItWas(): Unit =
        runBlocking {
            val saved = queueOneProduct()
            api.failPushWith = IOException("no network")

            runCatching { engine().sync() }

            val kept = pending().single()
            assertEquals(saved.id.value, kept.entityId)
            assertEquals("still pending, not marked as anything else", 0, kept.retryCount)
            assertNotNull("the product itself is untouched", products.byId(saved.id))
        }

    // "connection loss during upload". The server may or may not have got it;
    // the phone cannot tell. It must keep the change rather than assume.
    @Test
    fun aConnectionLostWhileSendingKeepsTheChangeForTheNextTry(): Unit =
        runBlocking {
            queueOneProduct()
            api.failPushWith = IOException("connection reset")
            runCatching { engine().sync() }
            assertEquals(1, pending().size)

            // The network comes back.
            api.failPushWith = null
            val report = engine().sync()

            assertEquals(1, report.pushed)
            assertEquals("sent exactly once, not twice", 1, api.pushedEvents.size)
            assertTrue(pending().isEmpty())
        }

    // "timeout after server processing". The server did the work; the answer
    // never arrived. The phone sends it again, the server recognises it, and
    // the queue clears without the change being applied twice.
    @Test
    fun aTimeoutAfterTheServerAlreadyAppliedItIsResentAndRecognised(): Unit =
        runBlocking {
            queueOneProduct()
            api.applyThenTimeOut = true

            runCatching { engine().sync() }
            assertEquals("the server saw it", 1, api.pushedEvents.size)
            assertEquals("the phone did not, so it keeps it", 1, pending().size)

            api.applyThenTimeOut = false
            api.pushStatus = PushResult.STATUS_ALREADY_APPLIED
            val report = engine().sync()

            assertEquals("already-applied counts as done, not as a failure", 1, report.pushed)
            assertTrue("and the queue clears", pending().isEmpty())
            assertEquals("the same event id both times", 2, api.pushedEvents.size)
            assertEquals(api.pushedEvents[0].eventId, api.pushedEvents[1].eventId)
        }

    // "invalid response". Something answered, but not something this app can
    // read. That is a failure like any other — it must not be taken as success.
    @Test
    fun anAnswerThePhoneCannotReadIsTreatedAsAFailure(): Unit =
        runBlocking {
            queueOneProduct()
            api.failPushWith = SyncException("POST /sync/push failed with HTTP 502: <html>", 502)

            runCatching { engine().sync() }

            assertEquals("nothing was thrown away on a reply we could not trust", 1, pending().size)
        }

    // --- Step 69: repeats and restarts ---

    // "app killed before ACK" and "app killed after ACK" look the same from
    // here: the outbox row is still there on the next start, because it is
    // only deleted once an answer has actually been read. Restarting is what
    // the fresh engine below stands for — nothing is held in memory between
    // the two syncs.
    @Test
    fun aChangeSentButNotConfirmedBeforeTheAppDiedIsSentAgainAndSettledOnce(): Unit =
        runBlocking {
            val saved = queueOneProduct()
            api.applyThenTimeOut = true
            runCatching { engine().sync() }

            // A brand new engine, as after a restart.
            api.applyThenTimeOut = false
            api.pushStatus = PushResult.STATUS_ALREADY_APPLIED
            val report = SyncEngine(database, api, settings).sync()

            assertEquals(1, report.pushed)
            assertTrue(pending().isEmpty())
            assertEquals("one product, not two", 1, products.observe().first().size)
            assertEquals(saved.id.value, api.pushedEvents.last().entityId)
        }

    // "server outage". Down for a while, then back. The queue survives every
    // attempt in between and goes out exactly once at the end.
    @Test
    fun aServerThatIsDownForSeveralTriesLosesNothingWhenItComesBack(): Unit =
        runBlocking {
            queueOneProduct("Salt")
            api.failPushWith = IOException("connection refused")

            repeat(3) { runCatching { engine().sync() } }
            assertEquals("kept through every failed attempt", 1, pending().size)
            assertEquals("never actually delivered", 0, api.pushedEvents.size)

            api.failPushWith = null
            val report = engine().sync()

            assertEquals(1, report.pushed)
            assertEquals(1, api.pushedEvents.size)
            assertTrue(pending().isEmpty())
        }

    // A pull cut off part way must not lose the pages already taken, and must
    // not read them again: the cursor is saved per page for exactly this.
    @Test
    fun aPullCutOffPartWayResumesFromTheLastPageItFinished(): Unit =
        runBlocking {
            api.pagesBeforePullFails = 1
            api.pullPages =
                listOf(
                    PullResult(listOf(serverProduct("p-1", "Rice")), cursor = 10),
                    PullResult(listOf(serverProduct("p-2", "Dal")), cursor = 20),
                )

            runCatching { engine().sync() }

            assertEquals("the first page was kept", "10", database.syncMetadataDao().get()?.lastServerCursor)
            assertNotNull(products.byId(EntityId("p-1")))

            api.pagesBeforePullFails = Int.MAX_VALUE
            engine().sync()

            assertEquals("it carried on from where it stopped", 20, api.requestedCursors.last())
            assertEquals("both products, each stored once", 2, products.observe().first().size)
        }

    // --- Step 70: scale ---

    @Test
    fun aHundredQueuedChangesAllGoOutAndTheQueueClears(): Unit = syncQueueOfSize(100)

    @Test
    fun aThousandQueuedChangesAllGoOutAndTheQueueClears(): Unit = syncQueueOfSize(1_000)

    private fun syncQueueOfSize(count: Int): Unit =
        runBlocking {
            repeat(count) { index -> queueOneProduct("Product $index") }
            assertEquals(count, pending().size)

            val report = engine().sync()

            assertEquals("every one of them was sent", count, report.pushed)
            assertEquals(count, api.pushedEvents.size)
            assertEquals(
                "no duplicates among them",
                count,
                api.pushedEvents
                    .map { it.eventId }
                    .toSet()
                    .size,
            )
            assertTrue("and the queue is empty afterwards", pending().isEmpty())
        }

    private fun serverProduct(
        id: String,
        name: String,
    ) = PulledChange(
        entityType = "Product",
        entityId = id,
        operation = "create",
        payload =
            JSONObject()
                .put("name", name)
                .put("aliases", org.json.JSONArray(listOf<String>()))
                .put("unit", "piece")
                .put("sellingPricePoisha", 1000)
                .put("purchasePricePoisha", JSONObject.NULL)
                .put("active", true)
                .put("revision", 1)
                .put("updatedAt", "2026-09-27T10:00:00Z")
                .put("deletedAt", JSONObject.NULL),
    )
}

/**
 * A server that can be made to fail in exactly the way a test needs: refusing
 * the connection, timing out after it has already done the work, or dying
 * between two pages of a pull.
 */
private class UnreliableSyncApi : SyncApi {
    val pushedEvents = mutableListOf<SyncOutboxEntity>()
    val requestedCursors = mutableListOf<Long>()

    var pushStatus: String = PushResult.STATUS_APPLIED

    /** Thrown instead of answering a push, if set. */
    var failPushWith: Exception? = null

    /** Records the push as done on the server, then fails before answering. */
    var applyThenTimeOut: Boolean = false

    var pullPages: List<PullResult> = emptyList()
    var pagesBeforePullFails: Int = Int.MAX_VALUE
    private var pullsServed = 0

    override suspend fun login(
        email: String,
        password: String,
    ): String = "issued-token"

    override suspend fun push(
        token: String,
        events: List<SyncOutboxEntity>,
    ): List<PushResult> {
        failPushWith?.let { throw it }
        if (applyThenTimeOut) {
            pushedEvents += events
            throw SocketTimeoutException("the server answered too late")
        }
        pushedEvents += events
        return events.map { PushResult(it.eventId, pushStatus, null) }
    }

    override suspend fun pull(
        token: String,
        afterCursor: Long,
    ): PullResult {
        requestedCursors += afterCursor
        if (pullsServed >= pagesBeforePullFails) throw IOException("connection lost mid-pull")

        val page = pullPages.firstOrNull { it.cursor > afterCursor }
        pullsServed += 1
        return page ?: PullResult(emptyList(), afterCursor)
    }
}
