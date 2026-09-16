package com.hisab.app.data.stock

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.product.ProductDraft
import com.hisab.app.data.product.ProductRepository
import com.hisab.app.data.sale.SaleRepository
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.ProductUnits
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SaleLine
import com.hisab.app.domain.StockMovementType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step 41's check: restocking updates the displayed stock immediately,
 * offline.
 *
 * "Displayed" is the live query the Stock screen actually watches, not a
 * number the test computes for itself — so what is asserted here is what a
 * shopkeeper would see.
 */
@RunWith(AndroidJUnit4::class)
class StockFlowTest {
    private lateinit var database: HisabDatabase
    private lateinit var stock: StockRepository
    private lateinit var sales: SaleRepository
    private lateinit var products: ProductRepository

    // Not lateinit: EntityId is a value class, so it cannot be one. Set in createDatabase.
    private var rice = EntityId("")

    @Before
    fun createDatabase() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
            stock = StockRepository(database)
            sales = SaleRepository(database)
            products = ProductRepository(database)

            rice = products.create(ProductDraft(name = "চাল", unit = ProductUnits.KG, sellingPrice = Money(5000))).id
        }

    @After
    fun closeDatabase() {
        database.close()
    }

    private suspend fun displayedStock(productId: EntityId): Quantity =
        Quantity(
            stock
                .observeProductsWithStock(includeInactive = true)
                .first()
                .first { it.product.id == productId.value }
                .stockScaled,
        )

    @Test
    fun aProductThatNeverMovedIsListedWithNoStock() =
        runBlocking {
            assertEquals(Quantity.ZERO, displayedStock(rice))
        }

    // Step 41's check.
    @Test
    fun restockingShowsOnTheStockScreenImmediately() =
        runBlocking {
            stock.restock(rice, Quantity(10_000))
            assertEquals(Quantity(10_000), displayedStock(rice))

            stock.restock(rice, Quantity(2500))
            assertEquals("the second delivery adds to the first", Quantity(12_500), displayedStock(rice))
        }

    @Test
    fun aRestockIsTypedAsARestockAndKeepsItsNote() =
        runBlocking {
            stock.restock(rice, Quantity(10_000), note = "করিম স্টোর")

            val history = stock.historyFor(rice)
            assertEquals(1, history.size)
            assertEquals(StockMovementType.RESTOCK.name, history.first().movementType)
            assertEquals("করিম স্টোর", history.first().sourceReference)
            assertNull("nothing has reached the server yet", history.first().serverReceivedAt)
        }

    @Test
    fun aBlankNoteIsStoredAsNothingRatherThanAsEmptyText() =
        runBlocking {
            stock.restock(rice, Quantity(1000), note = "   ")
            assertNull(stock.historyFor(rice).first().sourceReference)
        }

    @Test
    fun aSaleAndARestockBothCountTowardsTheSameNumber() =
        runBlocking {
            stock.restock(rice, Quantity(10_000))
            sales.recordCashSale(listOf(SaleLine(rice, Quantity(3000), Money(5000))))
            assertEquals(Quantity(7000), displayedStock(rice))

            stock.restock(rice, Quantity(1000))
            assertEquals(Quantity(8000), displayedStock(rice))
        }

    @Test
    fun aRestockOfNothingOrLessIsRefusedBeforeItReachesTheLedger() =
        runBlocking {
            for (amount in listOf(0L, -1000L)) {
                try {
                    stock.restock(rice, Quantity(amount))
                    throw AssertionError("a restock of $amount should have been refused")
                } catch (_: IllegalArgumentException) {
                    // expected
                }
            }
            assertTrue("nothing was written", stock.historyFor(rice).isEmpty())
        }

    @Test
    fun theStockScreenListsEveryProductEvenWithNoMovements() =
        runBlocking {
            products.create(ProductDraft(name = "চিনি", unit = ProductUnits.KG, sellingPrice = Money(12_000)))
            val listed = stock.observeProductsWithStock().first()
            assertEquals(2, listed.size)
            assertTrue("both start at nothing", listed.all { it.stockScaled == 0L })
        }

    @Test
    fun searchOnTheStockScreenMatchesNameAndAlias() =
        runBlocking {
            products.create(
                ProductDraft(
                    name = "চিনি",
                    aliases = listOf("chini", "sugar"),
                    unit = ProductUnits.KG,
                    sellingPrice = Money(12_000),
                ),
            )

            assertEquals(1, stock.observeProductsWithStock("চিনি").first().size)
            assertEquals("found by alias", 1, stock.observeProductsWithStock("sugar").first().size)
            assertTrue(stock.observeProductsWithStock("nothing-like-this").first().isEmpty())
        }
}
