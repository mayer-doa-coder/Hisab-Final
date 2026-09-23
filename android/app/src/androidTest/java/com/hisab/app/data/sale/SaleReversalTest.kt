package com.hisab.app.data.sale

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.baki.BakiEntryDao
import com.hisab.app.data.baki.toEntity
import com.hisab.app.data.customer.CustomerRepository
import com.hisab.app.data.product.ProductDraft
import com.hisab.app.data.product.ProductRepository
import com.hisab.app.data.stock.StockRepository
import com.hisab.app.data.sync.SyncOutboxDao
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.ProductUnits
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SaleLine
import com.hisab.app.domain.SalePayment
import com.hisab.app.domain.StockMovementType
import com.hisab.app.domain.reverseSale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * Step 43: reversing a cash sale restores stock correctly.
 * Step 44: reversing a credit sale undoes the sale, restores the stock AND
 * clears the matching baki — all together, or not at all (D021).
 *
 * Against a real Room database, because "all together" is a promise about a
 * database transaction, and only a database can be asked whether it kept it.
 */
@RunWith(AndroidJUnit4::class)
class SaleReversalTest {
    private lateinit var database: HisabDatabase
    private lateinit var sales: SaleRepository
    private lateinit var stock: StockRepository
    private lateinit var customers: CustomerRepository
    private lateinit var products: ProductRepository
    private lateinit var baki: BakiEntryDao
    private lateinit var outbox: SyncOutboxDao

    // Not lateinit: EntityId is a value class, so it cannot be one. Set in createDatabase.
    private var rice = EntityId("")
    private var oil = EntityId("")

    @Before
    fun createDatabase(): Unit =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
            sales = SaleRepository(database)
            stock = StockRepository(database)
            customers = CustomerRepository(database)
            products = ProductRepository(database)
            baki = database.bakiEntryDao()
            outbox = database.syncOutboxDao()

            rice = products.create(ProductDraft(name = "চাল", unit = ProductUnits.KG, sellingPrice = Money(5000))).id
            oil = products.create(ProductDraft(name = "তেল", unit = ProductUnits.LITRE, sellingPrice = Money(2500))).id
            stock.restock(rice, Quantity(10_000))
            stock.restock(oil, Quantity(10_000))
        }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun cart() =
        listOf(
            SaleLine(rice, Quantity(3000), Money(5000)),
            SaleLine(oil, Quantity(2000), Money(2500)),
        )

    private suspend fun balanceOf(customerId: String): Long = baki.balancePoisha(customerId)

    // Step 43's check.
    @Test
    fun reversingACashSaleRestoresStock(): Unit =
        runBlocking {
            val sale = sales.recordCashSale(cart())
            assertEquals(Quantity(7000), stock.currentStock(rice))
            assertEquals(Quantity(8000), stock.currentStock(oil))

            val result = sales.reverse(sale.sale.id)

            assertTrue(result is ReversalResult.Reversed)
            assertEquals("stock is back where it started", Quantity(10_000), stock.currentStock(rice))
            assertEquals(Quantity(10_000), stock.currentStock(oil))
        }

    @Test
    fun aReversalIsASecondSaleThatUndoesTheFirst(): Unit =
        runBlocking {
            val sale = sales.recordCashSale(cart())
            val reversal = (sales.reverse(sale.sale.id) as ReversalResult.Reversed).transaction

            // The original is untouched: history is never rewritten.
            val original = sales.byId(sale.sale.id)
            assertNotNull(original)
            assertEquals(20_000L, original!!.totalPoisha)

            val stored = sales.byId(reversal.sale.id)!!
            assertEquals(-20_000L, stored.totalPoisha)
            assertEquals(sale.sale.id.value, stored.reversesSaleId)
            // The original knows what undid it, and the reversal knows what it undid.
            assertEquals(reversal.sale.id.value, sales.detail(sale.sale.id)!!.reversedBy?.id)
            assertEquals(sale.sale.id.value, sales.detail(reversal.sale.id)!!.reverses?.id)
        }

    @Test
    fun aDaysTakingsExcludeAReversedSaleWithoutEditingIt(): Unit =
        runBlocking {
            val kept = sales.recordCashSale(listOf(SaleLine(rice, Quantity(1000), Money(5000))))
            val mistake = sales.recordCashSale(cart())
            sales.reverse(mistake.sale.id)

            // The history list adds up to the same as the kept sale alone:
            // the mistake and its reversal cancel each other out.
            val takings = sales.observeRecent().first().sumOf { it.sale.totalPoisha }
            assertEquals(kept.sale.total.minorUnits, takings)
        }

    // Step 44's check: the sale, the stock and the baki all move together.
    @Test
    fun reversingACreditSaleRestoresStockAndClearsTheBaki(): Unit =
        runBlocking {
            val customer = customers.findOrCreate("রহিম")
            val sale = sales.recordCreditSale(EntityId(customer.id), cart())
            assertEquals(20_000L, balanceOf(customer.id))
            assertEquals(Quantity(7000), stock.currentStock(rice))

            val result = sales.reverse(sale.sale.id)

            assertTrue(result is ReversalResult.Reversed)
            assertEquals(
                "the money nets to zero",
                -20_000L,
                sales.byId((result as ReversalResult.Reversed).transaction.sale.id)!!.totalPoisha,
            )
            assertEquals("the stock is back", Quantity(10_000), stock.currentStock(rice))
            assertEquals("nothing is owed any more", 0L, balanceOf(customer.id))

            val entries = baki.forCustomer(customer.id)
            assertEquals("both entries are kept — the debt and its undoing", 2, entries.size)
            assertEquals(
                customer.id,
                result.transaction.bakiEntry!!
                    .customerId.value,
            )
        }

    /**
     * The point of D021: there is no order of failures that leaves the stock
     * restored while the customer still owes the money. The reversal is
     * written in one database transaction, so if any part of it fails, none
     * of it is kept.
     */
    @Test
    fun aReversalThatCannotBeFullyWrittenIsNotWrittenAtAll(): Unit =
        runBlocking {
            val customer = customers.findOrCreate("করিম")
            val sale = sales.recordCreditSale(EntityId(customer.id), cart())
            val reversal = reverseSale(sales.transactionOf(sale.sale.id)!!, Instant.now())

            // Make the last part of the write — the baki entry — impossible,
            // by taking its id first. Everything before it must be undone.
            baki.insert(reversal.bakiEntry!!.toEntity().copy(amountDeltaPoisha = 1))

            val failed =
                try {
                    sales.save(reversal)
                    false
                } catch (_: Exception) {
                    true
                }

            assertTrue("writing the reversal should have failed", failed)
            assertNull("no reversal sale was kept", sales.byId(reversal.sale.id))
            assertEquals("stock stayed as the sale left it", Quantity(7000), stock.currentStock(rice))
            assertEquals("the customer still owes what they did", 20_001L, balanceOf(customer.id))
            assertTrue(
                "no half-written reversal was queued for the server",
                outbox.all().none { it.entityId == reversal.sale.id.value },
            )
        }

    @Test
    fun aSaleCanOnlyBeReversedOnce(): Unit =
        runBlocking {
            val sale = sales.recordCashSale(cart())

            assertTrue(sales.reverse(sale.sale.id) is ReversalResult.Reversed)
            assertEquals(ReversalResult.AlreadyReversed, sales.reverse(sale.sale.id))
            assertEquals("stock came back once", Quantity(10_000), stock.currentStock(rice))
        }

    @Test
    fun aReversalCannotItselfBeReversed(): Unit =
        runBlocking {
            val sale = sales.recordCashSale(cart())
            val reversal = (sales.reverse(sale.sale.id) as ReversalResult.Reversed).transaction

            assertEquals(ReversalResult.IsAReversal, sales.reverse(reversal.sale.id))
        }

    @Test
    fun reversingASaleThatIsNotThereSaysSo(): Unit =
        runBlocking {
            assertEquals(ReversalResult.NotFound, sales.reverse(EntityId("no-such-sale")))
        }

    @Test
    fun theStockComesBackAsAReturnPointingAtTheSaleItUndid(): Unit =
        runBlocking {
            val sale = sales.recordCashSale(cart())
            sales.reverse(sale.sale.id)

            val caused = database.stockMovementDao().forReference(sale.sale.id.value)
            val returns = caused.filter { it.movementType == StockMovementType.RETURN.name }
            assertEquals(2, returns.size)
            assertTrue(returns.all { it.quantityDeltaScaled > 0 })
            assertEquals("everything the sale caused nets to nothing", 0L, caused.sumOf { it.quantityDeltaScaled })
        }

    @Test
    fun aStoredSaleReadsBackAsTheTransactionItWasMadeFrom(): Unit =
        runBlocking {
            val customer = customers.findOrCreate("রহিম")
            val sale = sales.recordCreditSale(EntityId(customer.id), cart())

            val reloaded = sales.transactionOf(sale.sale.id)!!

            assertEquals(sale.sale.total, reloaded.sale.total)
            assertEquals(SalePayment.CREDIT, reloaded.sale.payment)
            assertEquals(2, reloaded.items.size)
            assertEquals(2, reloaded.stockMovements.size)
            assertEquals(sale.bakiEntry!!.amountDelta, reloaded.bakiEntry!!.amountDelta)
        }

    // D003: the change and the record of it to send are written together.
    @Test
    fun aSaleAndItsReversalAreBothQueuedForTheServer(): Unit =
        runBlocking {
            val sale = sales.recordCashSale(cart())
            val reversal = (sales.reverse(sale.sale.id) as ReversalResult.Reversed).transaction

            val queued = outbox.all().filter { it.entityType == SaleRepository.ENTITY_TYPE_SALE }
            assertEquals(2, queued.size)
            assertEquals(listOf(sale.sale.id.value, reversal.sale.id.value), queued.map { it.entityId })
            assertTrue(queued.all { it.operation == SaleRepository.OPERATION_CREATE })
        }
}
