package com.hisab.app.data.sale

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.LOCAL_SHOP_ID
import com.hisab.app.data.baki.BakiEntryDao
import com.hisab.app.data.customer.CustomerRepository
import com.hisab.app.data.product.ProductDraft
import com.hisab.app.data.product.ProductRepository
import com.hisab.app.data.stock.StockMovementDao
import com.hisab.app.data.stock.StockRepository
import com.hisab.app.domain.BakiEntryType
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.ProductUnits
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SaleLine
import com.hisab.app.domain.SalePayment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The checks Steps 39 and 40 actually name, against a real Room database:
 *
 *   Step 39 — a cash sale, offline, saves the sale and reduces stock.
 *   Step 40 — a credit sale, offline, saves the sale, reduces stock, and
 *             creates a baki entry.
 *
 * "Offline" is not simulated here: nothing in this path can reach a network,
 * because no repository involved holds an API. That is the design, and these
 * tests are what prove the write half of it works.
 */
@RunWith(AndroidJUnit4::class)
class SaleFlowTest {
    private lateinit var database: HisabDatabase
    private lateinit var sales: SaleRepository
    private lateinit var stock: StockRepository
    private lateinit var customers: CustomerRepository
    private lateinit var products: ProductRepository
    private lateinit var movements: StockMovementDao
    private lateinit var baki: BakiEntryDao

    // Not lateinit: EntityId is a value class, so it cannot be one. Both are set in createDatabase.
    private var rice = EntityId("")
    private var oil = EntityId("")

    // `: Unit` is not decoration. Without it this function returns whatever its
    // last line returns — here a StockMovement — and JUnit refuses to run the
    // whole class. scripts/check-junit-methods.sh now catches that in CI.
    @Before
    fun createDatabase(): Unit =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
            sales = SaleRepository(database)
            stock = StockRepository(database)
            customers = CustomerRepository(database)
            products = ProductRepository(database)
            movements = database.stockMovementDao()
            baki = database.bakiEntryDao()

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

    // Step 39's check.
    @Test
    fun aCashSaleSavesTheSaleAndReducesStock() =
        runBlocking {
            val transaction = sales.recordCashSale(cart())

            val stored = sales.byId(transaction.sale.id)
            assertNotNull("the sale is on the device", stored)
            assertEquals(20_000L, stored!!.totalPoisha)
            assertEquals(SalePayment.CASH.name, stored.payment)
            assertEquals(2, sales.itemsFor(transaction.sale.id).size)

            assertEquals("10 kg less 3 kg", Quantity(7000), stock.currentStock(rice))
            assertEquals("10 l less 2 l", Quantity(8000), stock.currentStock(oil))
        }

    @Test
    fun aCashSaleOwesNobodyAnything() =
        runBlocking {
            val transaction = sales.recordCashSale(cart())
            assertNull(transaction.bakiEntry)
            assertNull(sales.byId(transaction.sale.id)!!.customerId)
            assertTrue(baki.forReference(transaction.sale.id.value).isEmpty())
        }

    // Step 40's check.
    @Test
    fun aCreditSaleSavesTheSaleReducesStockAndCreatesABakiEntry() =
        runBlocking {
            val customer = customers.findOrCreate("রহিম")
            val transaction = sales.recordCreditSale(EntityId(customer.id), cart())

            val stored = sales.byId(transaction.sale.id)!!
            assertEquals(SalePayment.CREDIT.name, stored.payment)
            assertEquals(customer.id, stored.customerId)
            assertEquals(20_000L, stored.totalPoisha)

            assertEquals(Quantity(7000), stock.currentStock(rice))
            assertEquals(Quantity(8000), stock.currentStock(oil))

            val entries = baki.forReference(transaction.sale.id.value)
            assertEquals("exactly one entry, for this sale", 1, entries.size)
            assertEquals(BakiEntryType.CREDIT_SALE.name, entries.first().entryType)
            assertEquals("owes exactly what it totalled", 20_000L, entries.first().amountDeltaPoisha)
            assertEquals(customer.id, entries.first().customerId)
        }

    @Test
    fun whatACustomerOwesIsTheSumOfTheirEntries() =
        runBlocking {
            val customer = customers.findOrCreate("রহিম")
            sales.recordCreditSale(EntityId(customer.id), listOf(SaleLine(rice, Quantity(1000), Money(5000))))
            sales.recordCreditSale(EntityId(customer.id), listOf(SaleLine(oil, Quantity(2000), Money(2500))))

            assertEquals(10_000L, baki.balancePoisha(customer.id))
        }

    @Test
    fun aDueDateIsKeptWhenOneIsGiven() =
        runBlocking {
            val customer = customers.findOrCreate("করিম")
            val due = LocalDate.of(2026, 10, 15)
            val transaction = sales.recordCreditSale(EntityId(customer.id), cart(), due)

            val entry = baki.forReference(transaction.sale.id.value).first()
            assertEquals(due.toEpochDay(), entry.dueDateEpochDay)
        }

    @Test
    fun aDueDateIsOptional() =
        runBlocking {
            val customer = customers.findOrCreate("করিম")
            val transaction = sales.recordCreditSale(EntityId(customer.id), cart())
            assertNull(baki.forReference(transaction.sale.id.value).first().dueDateEpochDay)
        }

    /** The same name is the same person, not a second row with half the debt. */
    @Test
    fun recordingTwoSalesForOneNameUsesOneCustomer(): Unit =
        runBlocking {
            val first = customers.findOrCreate("রহিম")
            val second = customers.findOrCreate("  রহিম  ")
            assertEquals(first.id, second.id)
        }

    @Test
    fun everyStockMovementPointsBackAtTheSaleThatCausedIt() =
        runBlocking {
            val transaction = sales.recordCashSale(cart())
            val caused = movements.forReference(transaction.sale.id.value)

            assertEquals(2, caused.size)
            assertEquals(-5000L, caused.sumOf { it.quantityDeltaScaled })
        }

    /** D031: the ledger records what happened, even when it disagrees with the shelf. */
    @Test
    fun sellingMoreThanTheStockShowsIsRecorded() =
        runBlocking {
            sales.recordCashSale(listOf(SaleLine(rice, Quantity(15_000), Money(5000))))
            assertEquals(Quantity(-5000), stock.currentStock(rice))
        }

    @Test
    fun aSaleBelongsToThisShopAndNoOther() =
        runBlocking {
            val transaction = sales.recordCashSale(cart())
            assertEquals(LOCAL_SHOP_ID, sales.byId(transaction.sale.id)!!.shopId)
        }
}
