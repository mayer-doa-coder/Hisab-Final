package com.hisab.app.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.customer.CustomerRepository
import com.hisab.app.data.product.ProductDraft
import com.hisab.app.data.product.ProductRepository
import com.hisab.app.data.sale.ReversalResult
import com.hisab.app.data.sale.SaleRepository
import com.hisab.app.data.stock.StockRepository
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.ProductUnits
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SaleLine
import kotlinx.coroutines.runBlocking
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
 * Step 47 for real: this phone, over HTTP, against the actual server and its
 * Postgres. Nothing is faked.
 *
 * Each of the four workflows the step names is run end to end, and the stock
 * ledger is checked on both sides afterwards:
 *
 *     Cash sale, credit sale, restock, and a correction (a reversal).
 *
 * It needs the development server running and reachable from the phone:
 *
 *     cd server && npm start
 *     adb reverse tcp:3000 tcp:3000
 *
 * Without that, every test here skips itself rather than failing, because a
 * missing server is not a broken app. CI has no phone, so CI never runs it.
 */
@RunWith(AndroidJUnit4::class)
class SaleSyncEndToEndTest {
    private val baseUrl = "http://127.0.0.1:3000"
    private val email = "rahim@example.com"
    private val password = "correct-horse-1"

    /** Marks every row this test makes on the server, so they can be told apart. */
    private val marker = "E2E-${UUID.randomUUID().toString().take(8)}"

    private lateinit var database: HisabDatabase
    private lateinit var products: ProductRepository
    private lateinit var sales: SaleRepository
    private lateinit var stock: StockRepository
    private lateinit var customers: CustomerRepository
    private lateinit var settings: SyncSettings
    private lateinit var api: HttpSyncApi
    private var token: String = ""

    // Not lateinit: EntityId is a value class, so it cannot be one. Set in setUp.
    private var rice = EntityId("")

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
        products = ProductRepository(database)
        sales = SaleRepository(database)
        stock = StockRepository(database)
        customers = CustomerRepository(database)
        settings = SyncSettings(context)
        settings.serverUrl = baseUrl
        settings.email = email
        settings.token = token

        runBlocking {
            rice =
                products
                    .create(ProductDraft(name = "$marker চাল", unit = ProductUnits.KG, sellingPrice = Money(5000)))
                    .id
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

    /** What the server says this product's stock is — its own sum over its own ledger. */
    private fun serverStock(productId: EntityId): Long = serverCall("GET", "/stock/${productId.value}").getLong("quantity")

    private fun serverSale(saleId: EntityId): JSONObject = serverCall("GET", "/sales/${saleId.value}")

    /** Both sides agree about this product's stock — the check Step 47 asks for after each workflow. */
    private suspend fun assertStockMatches(expected: Long) {
        assertEquals("on the phone", Quantity(expected), stock.currentStock(rice))
        assertEquals("on the server", expected, serverStock(rice))
    }

    // Workflow 3: Stock → Product → Add Stock → Quantity → Confirm.
    @Test
    fun aRestockMadeOnThePhoneReachesTheServer(): Unit =
        runBlocking {
            stock.restock(rice, Quantity(10_000), note = "$marker supplier")

            val report = engine().sync()

            assertEquals("the product and the restock", 2, report.pushed)
            assertTrue(database.syncOutboxDao().all().isEmpty())
            assertStockMatches(10_000)
        }

    // Workflow 1: Home → New Sale → Product → Quantity → Cash → Confirm.
    @Test
    fun aCashSaleMadeOnThePhoneReachesTheServerWithItsStock(): Unit =
        runBlocking {
            stock.restock(rice, Quantity(10_000))
            val sale = sales.recordCashSale(listOf(SaleLine(rice, Quantity(3000), Money(5000))))

            engine().sync()

            val onServer = serverSale(sale.sale.id).getJSONObject("sale")
            assertEquals(15_000, onServer.getJSONObject("sale").getInt("total"))
            assertEquals("cash", onServer.getJSONObject("sale").getString("payment"))
            assertEquals("its lines came too", 1, onServer.getJSONArray("items").length())
            assertEquals("and the stock it moved", 1, onServer.getJSONArray("stockMovements").length())
            assertStockMatches(7000)
        }

    // Workflow 2: Home → New Sale → Product → Quantity → Customer → Baki → Confirm.
    @Test
    fun aCreditSaleTakesItsCustomerAndWhatIsOwedToTheServer(): Unit =
        runBlocking {
            stock.restock(rice, Quantity(10_000))
            val customer = customers.findOrCreate("$marker রহিম")
            val sale = sales.recordCreditSale(EntityId(customer.id), listOf(SaleLine(rice, Quantity(2000), Money(5000))))

            engine().sync()

            val onServer = serverSale(sale.sale.id).getJSONObject("sale")
            assertEquals("credit", onServer.getJSONObject("sale").getString("payment"))
            assertEquals(customer.id, onServer.getJSONObject("sale").getString("customerId"))
            assertEquals("owed exactly what it totalled", 10_000, onServer.getJSONObject("bakiEntry").getInt("amountDelta"))
            assertStockMatches(8000)
        }

    // Workflow 4: History → Transaction → Reverse → Confirm.
    @Test
    fun reversingACreditSaleClearsTheBakiOnTheServerToo(): Unit =
        runBlocking {
            stock.restock(rice, Quantity(10_000))
            val customer = customers.findOrCreate("$marker করিম")
            val sale = sales.recordCreditSale(EntityId(customer.id), listOf(SaleLine(rice, Quantity(2000), Money(5000))))
            engine().sync()
            assertEquals(10_000, serverSale(sale.sale.id).getJSONObject("sale").getJSONObject("bakiEntry").getInt("amountDelta"))

            val reversal = sales.reverse(sale.sale.id) as ReversalResult.Reversed
            val report = engine().sync()

            assertEquals(1, report.pushed)
            val onServer = serverSale(reversal.transaction.sale.id).getJSONObject("sale")
            assertEquals(-10_000, onServer.getJSONObject("sale").getInt("total"))
            assertEquals(sale.sale.id.value, onServer.getJSONObject("sale").getString("reversesSaleId"))
            assertEquals("the baki is cleared there as well", -10_000, onServer.getJSONObject("bakiEntry").getInt("amountDelta"))
            assertEquals(
                "and the server says the sale was undone",
                reversal.transaction.sale.id.value,
                serverSale(sale.sale.id).getString("reversedBy"),
            )
            assertStockMatches(10_000)
        }

    @Test
    fun theServerRefusesASecondReversalOfTheSameSale(): Unit =
        runBlocking {
            stock.restock(rice, Quantity(10_000))
            val sale = sales.recordCashSale(listOf(SaleLine(rice, Quantity(1000), Money(5000))))
            sales.reverse(sale.sale.id)
            engine().sync()

            // Another device got there first, as far as this server is
            // concerned: the same sale, reversed again.
            val secondReversal =
                serverCall(
                    "POST",
                    "/sync/push",
                    JSONObject().put(
                        "events",
                        org.json.JSONArray().put(
                            JSONObject()
                                .put("eventId", UUID.randomUUID().toString())
                                .put("entityType", "Sale")
                                .put("entityId", UUID.randomUUID().toString())
                                .put("operation", "create")
                                .put("payload", JSONObject(serverSale(sale.sale.id).getJSONObject("sale").toString()))
                                .put("baseRevision", JSONObject.NULL)
                                .put("clientTimestamp", "2026-09-17T10:00:00Z"),
                        ),
                    ),
                )

            // It is refused: the payload is not a fresh, sound reversal.
            assertEquals("rejected", secondReversal.getJSONArray("results").getJSONObject(0).getString("status"))
            assertStockMatches(10_000)
        }

    // The other direction: made on the server, then the phone catches up.
    @Test
    fun aSaleMadeOnTheServerReachesThePhoneWithItsStock(): Unit =
        runBlocking {
            stock.restock(rice, Quantity(10_000))
            engine().sync()

            val madeOnServer =
                serverCall(
                    "POST",
                    "/sales",
                    JSONObject()
                        .put("payment", "cash")
                        .put(
                            "lines",
                            org.json.JSONArray().put(
                                JSONObject().put("productId", rice.value).put("quantity", 4000),
                            ),
                        ),
                ).getJSONObject("sale")
            val saleId = EntityId(madeOnServer.getJSONObject("sale").getString("id"))

            val report = engine().sync()

            assertTrue(report.pulled > 0)
            assertNotNull("the sale itself", sales.byId(saleId))
            assertEquals("and the stock it took", Quantity(6000), stock.currentStock(rice))
            assertEquals(1, sales.itemsFor(saleId).size)
        }

    @Test
    fun aPhoneThatSyncsTwiceDoesNotApplyItsOwnSaleTwice(): Unit =
        runBlocking {
            stock.restock(rice, Quantity(10_000))
            sales.recordCashSale(listOf(SaleLine(rice, Quantity(3000), Money(5000))))

            engine().sync()
            engine().sync()

            assertStockMatches(7000)
        }

    @Test
    fun aSyncedSaleKnowsItReachedTheServer(): Unit =
        runBlocking {
            stock.restock(rice, Quantity(10_000))
            val sale = sales.recordCashSale(listOf(SaleLine(rice, Quantity(1000), Money(5000))))

            // The first sync sends it; the same sync pulls it back with the
            // moment the server received it (D019).
            engine().sync()

            assertNotNull(sales.byId(sale.sale.id)!!.serverReceivedAt)
        }
}
