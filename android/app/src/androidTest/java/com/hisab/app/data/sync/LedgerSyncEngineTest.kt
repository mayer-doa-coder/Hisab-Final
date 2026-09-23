package com.hisab.app.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.LOCAL_SHOP_ID
import com.hisab.app.data.customer.CustomerRepository
import com.hisab.app.data.product.ProductDraft
import com.hisab.app.data.product.ProductRepository
import com.hisab.app.data.sale.SaleRepository
import com.hisab.app.data.stock.StockRepository
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.ProductUnits
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SaleLine
import com.hisab.app.domain.SaleTransaction
import com.hisab.app.domain.StockMovement
import com.hisab.app.domain.completeCashSale
import com.hisab.app.domain.completeCreditSale
import com.hisab.app.domain.generateId
import com.hisab.app.domain.restock
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
import java.time.Instant

/**
 * What a phone does with the sales, stock movements and customers a pull
 * brings back (Step 47), against a stand-in server so the behaviour is tested
 * without a network.
 *
 * Two things matter here. A sale arrives whole — the sale, its lines, its
 * stock movements and its baki entry — and is stored in one transaction, so a
 * device can never hold half a sale (D021). And a pull keeps asking until the
 * server has nothing left, so a phone that was offline for a week is not left
 * holding only the first page.
 */
@RunWith(AndroidJUnit4::class)
class LedgerSyncEngineTest {
    private lateinit var database: HisabDatabase
    private lateinit var settings: SyncSettings
    private lateinit var api: PagingFakeApi
    private lateinit var sales: SaleRepository
    private lateinit var stock: StockRepository
    private lateinit var customers: CustomerRepository
    private lateinit var products: ProductRepository

    private val at = Instant.parse("2026-09-17T10:00:00Z")

    // Not lateinit: EntityId is a value class, so it cannot be one. Set in setUp.
    private var rice = EntityId("")

    @Before
    fun setUp(): Unit =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context
                .getSharedPreferences("hisab_sync", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
            database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
            settings = SyncSettings(context)
            settings.email = "rahim@example.com"
            settings.token = "test-token"
            api = PagingFakeApi()
            sales = SaleRepository(database)
            stock = StockRepository(database)
            customers = CustomerRepository(database)
            products = ProductRepository(database)

            rice = products.create(ProductDraft(name = "চাল", unit = ProductUnits.KG, sellingPrice = Money(5000))).id
            database.syncOutboxDao().all().forEach { database.syncOutboxDao().deleteByEventId(it.eventId) }
        }

    @After
    fun tearDown() {
        database.close()
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSharedPreferences("hisab_sync", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun engine() = SyncEngine(database, api, settings)

    private fun saleChange(transaction: SaleTransaction) =
        PulledChange(
            entityType = "Sale",
            entityId = transaction.sale.id.value,
            operation = "create",
            payload = SaleSyncPayload.toJsonObject(transaction),
        )

    private fun movementChange(movement: StockMovement) =
        PulledChange(
            entityType = "StockMovement",
            entityId = movement.id.value,
            operation = "create",
            payload = StockMovementSyncPayload.toJsonObject(movement),
        )

    private fun customerChange(
        id: String,
        name: String,
    ) = PulledChange(
        entityType = "Customer",
        entityId = id,
        operation = "create",
        payload = JSONObject().put("name", name).put("phone", JSONObject.NULL).put("revision", 1),
    )

    private fun cart() = listOf(SaleLine(rice, Quantity(3000), Money(5000)))

    @Test
    fun aRestockFromAnotherDeviceMovesThisPhonesStock(): Unit =
        runBlocking {
            api.pages = listOf(listOf(movementChange(restock(rice, Quantity(10_000), at))) to 5L)

            val report = engine().sync()

            assertEquals(1, report.pulled)
            assertEquals(Quantity(10_000), stock.currentStock(rice))
        }

    @Test
    fun aSaleFromAnotherDeviceArrivesWholeAndTakesItsStock(): Unit =
        runBlocking {
            stock.restock(rice, Quantity(10_000))
            val sale = completeCashSale(LOCAL_SHOP_ID, cart(), at)
            api.pages = listOf(listOf(saleChange(sale)) to 5L)

            engine().sync()

            val stored = sales.byId(sale.sale.id)
            assertNotNull("the sale itself", stored)
            assertEquals("3 kg at 50 taka", 15_000L, stored!!.totalPoisha)
            assertEquals("its lines", 1, sales.itemsFor(sale.sale.id).size)
            assertEquals("its stock movement", Quantity(7000), stock.currentStock(rice))
        }

    @Test
    fun aCreditSaleFromAnotherDeviceBringsItsCustomerAndWhatIsOwed(): Unit =
        runBlocking {
            val customerId = generateId()
            val sale = completeCreditSale(LOCAL_SHOP_ID, customerId, cart(), at)
            api.pages =
                listOf(
                    listOf(customerChange(customerId.value, "রহিম"), saleChange(sale)) to 5L,
                )

            engine().sync()

            assertEquals("রহিম", customers.byId(customerId)!!.name)
            assertEquals(15_000L, database.bakiEntryDao().balancePoisha(customerId.value))
        }

    @Test
    fun aSaleThatIsAlreadyHereIsNotStoredTwice(): Unit =
        runBlocking {
            stock.restock(rice, Quantity(10_000))
            val sale = sales.recordCashSale(cart())
            api.pages = listOf(listOf(saleChange(sale)) to 5L)

            engine().sync()

            assertEquals("stock left once, not twice", Quantity(7000), stock.currentStock(rice))
            assertEquals(1, sales.itemsFor(sale.sale.id).size)
        }

    /** The echo of this phone's own sale teaches it that the server has it. */
    @Test
    fun aSaleComingBackFromTheServerIsMarkedAsHavingReachedIt(): Unit =
        runBlocking {
            stock.restock(rice, Quantity(10_000))
            val sale = sales.recordCashSale(cart())
            assertNull(sales.byId(sale.sale.id)!!.serverReceivedAt)

            val received = Instant.parse("2026-09-17T12:00:00Z")
            val acknowledged =
                sale.copy(sale = sale.sale.copy(time = sale.sale.time.copy(serverReceivedAt = received)))
            api.pages = listOf(listOf(saleChange(acknowledged)) to 5L)

            engine().sync()

            assertEquals(received, sales.byId(sale.sale.id)!!.serverReceivedAt)
        }

    // A phone that was away for a busy week must not be left holding only the
    // first page of what it missed.
    @Test
    fun aPullKeepsAskingUntilTheServerHasNothingLeft(): Unit =
        runBlocking {
            val first = restock(rice, Quantity(1000), at)
            val second = restock(rice, Quantity(2000), at)
            val third = restock(rice, Quantity(3000), at)
            api.pages =
                listOf(
                    listOf(movementChange(first)) to 1L,
                    listOf(movementChange(second)) to 2L,
                    listOf(movementChange(third)) to 3L,
                )

            val report = engine().sync()

            assertEquals(3, report.pulled)
            assertEquals(Quantity(6000), stock.currentStock(rice))
            assertEquals("asked from 0, then from each page's end", listOf(0L, 1L, 2L, 3L), api.requestedCursors)
            assertEquals(3L, report.cursor)
        }

    @Test
    fun theNextSyncCarriesOnFromTheLastPage(): Unit =
        runBlocking {
            api.pages = listOf(listOf(movementChange(restock(rice, Quantity(1000), at))) to 4L)
            engine().sync()

            api.requestedCursors.clear()
            engine().sync()

            assertEquals(listOf(4L), api.requestedCursors)
        }

    @Test
    fun aKindOfChangeThisVersionDoesNotKnowIsSkippedRatherThanBreakingTheSync(): Unit =
        runBlocking {
            api.pages =
                listOf(
                    listOf(
                        PulledChange("Dragon", "dragon-1", "create", JSONObject().put("name", "Smaug")),
                        movementChange(restock(rice, Quantity(1000), at)),
                    ) to 5L,
                )

            engine().sync()

            assertEquals("the change it did understand still arrived", Quantity(1000), stock.currentStock(rice))
        }

    @Test
    fun whatArrivedFromTheServerIsNotSentBack(): Unit =
        runBlocking {
            api.pages = listOf(listOf(movementChange(restock(rice, Quantity(1000), at))) to 5L)
            engine().sync()

            assertTrue(database.syncOutboxDao().all().isEmpty())
            assertTrue(api.pushedEvents.isEmpty())
        }
}

/** A stand-in server that hands its changes back one page at a time, as the real one does. */
private class PagingFakeApi : SyncApi {
    val pushedEvents = mutableListOf<SyncOutboxEntity>()
    val requestedCursors = mutableListOf<Long>()

    /** Each page: the changes it holds, and the cursor that comes back with them. */
    var pages: List<Pair<List<PulledChange>, Long>> = emptyList()

    override suspend fun login(
        email: String,
        password: String,
    ): String = "issued-token"

    override suspend fun push(
        token: String,
        events: List<SyncOutboxEntity>,
    ): List<PushResult> {
        pushedEvents += events
        return events.map { PushResult(it.eventId, PushResult.STATUS_APPLIED, null) }
    }

    override suspend fun pull(
        token: String,
        afterCursor: Long,
    ): PullResult {
        requestedCursors += afterCursor
        val page = pages.firstOrNull { (_, cursor) -> cursor > afterCursor }
        return if (page == null) PullResult(emptyList(), afterCursor) else PullResult(page.first, page.second)
    }
}
