package com.hisab.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.product.ProductDraft
import com.hisab.app.data.product.ProductRepository
import com.hisab.app.data.sale.SaleRepository
import com.hisab.app.data.stock.StockRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Step 42's check: history shows sales and stock movements in order.
 *
 * The clock is fixed and stepped by hand, because "in order" cannot be
 * checked against a real clock that advances by microseconds between writes.
 */
@RunWith(AndroidJUnit4::class)
class HistoryOrderTest {
    private lateinit var database: HisabDatabase
    private lateinit var products: ProductRepository

    // Not lateinit: EntityId is a value class, so it cannot be one. Set in createDatabase.
    private var rice = EntityId("")

    private var now = Instant.parse("2026-09-16T04:00:00Z")

    /** A clock that only moves when a test says so. */
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?): Clock = this

            override fun instant(): Instant = now
        }

    private lateinit var sales: SaleRepository
    private lateinit var stock: StockRepository

    @Before
    fun createDatabase() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
            products = ProductRepository(database)
            sales = SaleRepository(database, clock)
            stock = StockRepository(database, clock)

            rice = products.create(ProductDraft(name = "চাল", unit = ProductUnits.KG, sellingPrice = Money(5000))).id
        }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun tick(minutes: Long) {
        now = now.plusSeconds(minutes * 60)
    }

    @Test
    fun salesAndStockMovementsBothAppear() =
        runBlocking {
            stock.restock(rice, Quantity(10_000))
            tick(10)
            sales.recordCashSale(listOf(SaleLine(rice, Quantity(1000), Money(5000))))

            assertEquals(1, sales.observeRecent().first().size)
            assertEquals(1, stock.observeRecentMovements().first().size)
        }

    @Test
    fun theNewestSaleIsFirst() =
        runBlocking {
            stock.restock(rice, Quantity(10_000))

            tick(10)
            val older = sales.recordCashSale(listOf(SaleLine(rice, Quantity(1000), Money(5000))))
            tick(10)
            val newer = sales.recordCashSale(listOf(SaleLine(rice, Quantity(2000), Money(5000))))

            val listed = sales.observeRecent().first()
            assertEquals(
                listOf(newer.sale.id.value, older.sale.id.value),
                listed.map { it.sale.id },
            )
        }

    @Test
    fun theNewestStockMovementIsFirst() =
        runBlocking {
            stock.restock(rice, Quantity(10_000), note = "first")
            tick(10)
            stock.restock(rice, Quantity(2000), note = "second")

            val listed = stock.observeRecentMovements().first()
            assertEquals(listOf("second", "first"), listed.map { it.movement.sourceReference })
        }

    /**
     * A sale's own stock movement is not listed beside it. The sale is
     * already in the list, and showing its movement too would be the same
     * event twice.
     */
    @Test
    fun aSaleIsListedOnceAndNotAlsoAsAStockMovement() =
        runBlocking {
            stock.restock(rice, Quantity(10_000))
            tick(10)
            sales.recordCashSale(listOf(SaleLine(rice, Quantity(1000), Money(5000))))

            val movements = stock.observeRecentMovements().first()
            assertEquals("only the restock", 1, movements.size)
            assertEquals(StockMovementType.RESTOCK.name, movements.first().movement.movementType)
        }

    @Test
    fun eachHistoryRowCarriesTheProductName() =
        runBlocking {
            stock.restock(rice, Quantity(10_000))
            assertEquals(
                "চাল",
                stock
                    .observeRecentMovements()
                    .first()
                    .first()
                    .productName,
            )
        }

    @Test
    fun aRowSurvivesItsProductBeingDeleted() =
        runBlocking {
            stock.restock(rice, Quantity(10_000))
            products.purge(rice)

            val listed = stock.observeRecentMovements().first()
            assertEquals("the movement is still history", 1, listed.size)
            assertEquals(null, listed.first().productName)
        }

    @Test
    fun aSaleRowKnowsHowManyLinesItHad() =
        runBlocking {
            val oil =
                products
                    .create(ProductDraft(name = "তেল", unit = ProductUnits.LITRE, sellingPrice = Money(2500)))
                    .id
            sales.recordCashSale(
                listOf(
                    SaleLine(rice, Quantity(1000), Money(5000)),
                    SaleLine(oil, Quantity(2000), Money(2500)),
                ),
            )

            assertEquals(
                2,
                sales
                    .observeRecent()
                    .first()
                    .first()
                    .lineCount,
            )
        }

    @Test
    fun historyIsEmptyBeforeAnythingHappens() =
        runBlocking {
            assertTrue(sales.observeRecent().first().isEmpty())
            assertTrue(stock.observeRecentMovements().first().isEmpty())
        }
}
