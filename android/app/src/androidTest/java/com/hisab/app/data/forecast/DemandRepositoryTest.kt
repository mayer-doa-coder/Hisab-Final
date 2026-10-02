package com.hisab.app.data.forecast

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
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
import com.hisab.app.domain.forecast.DemandSeries
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Step 84's check against a real database: a known sale history recorded through
 * the real `SaleRepository`, read back as daily demand.
 *
 * The pipeline's rules are proved on the JVM in `DemandSeriesTest`. What can
 * only be proved here is that the rows reaching those rules are the right rows —
 * in particular that a reversal is dated back to the sale it undoes, which is a
 * `LEFT JOIN` in `DemandDao` and cannot be tested without a database.
 *
 * Sales are recorded through a repository holding a fixed clock, one per day, so
 * the history has real dates instead of all landing on the day the test ran.
 *
 * Test names are camelCase, not backtick sentences: with minSdk 26 the dex format
 * forbids spaces in method names, so a device test with one compiles but fails to
 * dex. (The JVM unit tests in `domain/forecast/` can and do use sentences.)
 */
@RunWith(AndroidJUnit4::class)
class DemandRepositoryTest {
    private lateinit var database: HisabDatabase
    private lateinit var products: ProductRepository
    private lateinit var demand: DemandRepository

    private var rice = EntityId("")
    private var oil = EntityId("")
    private var soap = EntityId("")

    private val zone = ZoneOffset.UTC

    private fun day(dayOfMonth: Int): LocalDate = LocalDate.of(2026, 9, dayOfMonth)

    /** Midday on the given day, so no case depends on a time-zone edge. */
    private fun at(dayOfMonth: Int): Instant = day(dayOfMonth).atTime(12, 0).toInstant(zone)

    private fun salesOn(dayOfMonth: Int): SaleRepository = SaleRepository(database, clock = Clock.fixed(at(dayOfMonth), zone))

    @Before
    fun createDatabase(): Unit =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
            products = ProductRepository(database)
            demand = DemandRepository(database, zone = zone)

            rice = products.create(ProductDraft(name = "চাল", unit = ProductUnits.KG, sellingPrice = Money(5000))).id
            oil = products.create(ProductDraft(name = "তেল", unit = ProductUnits.LITRE, sellingPrice = Money(2500))).id
            soap = products.create(ProductDraft(name = "সাবান", sellingPrice = Money(1500))).id

            val stock = StockRepository(database, clock = Clock.fixed(at(1), zone))
            stock.restock(rice, Quantity(100_000))
            stock.restock(oil, Quantity(100_000))
            stock.restock(soap, Quantity(100_000))
        }

    @After
    fun closeDatabase() {
        database.close()
    }

    private suspend fun sell(
        dayOfMonth: Int,
        productId: EntityId,
        units: Double,
    ): EntityId =
        salesOn(dayOfMonth)
            .recordCashSale(listOf(SaleLine(productId, Quantity.fromDecimal(units), Money(5000))))
            .sale
            .id

    private fun unitsPerDay(series: DemandSeries): List<Double> = series.days.map { it.quantitySold.toDecimal() }

    @Test
    fun aKnownSaleHistoryComesBackAsTheRightDailyDemand(): Unit =
        runBlocking {
            sell(1, rice, 3.0)
            // A second sale of the same product on the same day is one demand row.
            sell(1, rice, 2.0)
            sell(4, rice, 1.0)
            sell(10, rice, 4.0)

            val series = demand.seriesFor(rice, today = day(10))!!

            assertEquals(day(1), series.firstDay)
            assertEquals(day(10), series.lastDay)
            assertEquals(listOf(5.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 4.0), unitsPerDay(series))
        }

    @Test
    fun aReversalCancelsTheDayTheSaleHappenedNotTheDayItWasUndone(): Unit =
        runBlocking {
            sell(2, rice, 3.0)
            val undone = sell(5, rice, 8.0)
            sell(9, rice, 1.0)

            // Undone four days later. The negative sale line carries the 9th,
            // but the demand it cancels happened on the 5th.
            assertTrue(salesOn(9).reverse(undone) is ReversalResult.Reversed)

            val series = demand.seriesFor(rice, today = day(9))!!

            assertEquals(day(2), series.firstDay)
            assertEquals(
                // The 5th is back to zero and the 9th still shows its own sale
                // of 1 — not 1 minus 8.
                listOf(3.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0),
                unitsPerDay(series),
            )
        }

    @Test
    fun aProductThatHasNeverBeenSoldHasNoSeriesAtAll(): Unit =
        runBlocking {
            sell(3, rice, 1.0)
            assertNull(demand.seriesFor(oil, today = day(10)))
        }

    @Test
    fun aDeactivatedProductsSeriesStopsAtItsLastSale(): Unit =
        runBlocking {
            sell(2, soap, 2.0)
            sell(5, soap, 3.0)
            val current = products.byId(soap)!!
            products.setActive(soap, current.revision, active = false)

            val series = demand.seriesFor(soap, today = day(20))!!

            assertEquals(day(5), series.lastDay)
            assertEquals(listOf(2.0, 0.0, 0.0, 3.0), unitsPerDay(series))
        }

    @Test
    fun everyProductThatHasSoldGetsASeriesAndOnlyThose(): Unit =
        runBlocking {
            sell(1, rice, 2.0)
            sell(3, oil, 1.0)

            val all = demand.series(today = day(5))

            assertEquals(setOf(rice, oil), all.map { it.productId }.toSet())
            assertTrue(all.all { it.dayCount > 0 })
        }

    @Test
    fun theDaysTheLedgerReadEmptyAreMarkedFromTheStockLedgerNotTyped(): Unit =
        runBlocking {
            // Starts with 100 in stock from setUp. Sell it all on the 3rd.
            sell(2, oil, 10.0)
            sell(3, oil, 90.0)

            val series = demand.seriesFor(oil, today = day(5), withStockOutDays = true)!!

            assertEquals(listOf(false, true, true, true), series.days.map { it.stockOut })
            assertEquals(3, series.stockOutDays)
        }

    @Test
    fun aSaleAfterMidnightInTheShopsOwnZoneBelongsToTheShopsDay(): Unit =
        runBlocking {
            // 18:30 UTC on the 4th is 00:30 on the 5th in Dhaka. A pipeline that
            // grouped by UTC would file this sale under the 4th and hand every
            // forecasting method a series shifted by a day.
            val dhaka = ZoneId.of("Asia/Dhaka")
            val justAfterMidnightInDhaka = day(4).atTime(18, 30).toInstant(ZoneOffset.UTC)
            SaleRepository(database, clock = Clock.fixed(justAfterMidnightInDhaka, ZoneOffset.UTC))
                .recordCashSale(listOf(SaleLine(rice, Quantity.fromDecimal(6.0), Money(5000))))

            val inDhaka = DemandRepository(database, zone = dhaka).seriesFor(rice, today = day(6))!!
            assertEquals(day(5), inDhaka.firstDay)
            assertEquals(listOf(6.0, 0.0), unitsPerDay(inDhaka))

            // The same rows read in UTC put it on the 4th. Which day a sale
            // belongs to is a question about the shop's zone, and the answer
            // follows whichever zone is asked for.
            assertEquals(day(4), demand.seriesFor(rice, today = day(6))!!.firstDay)
        }

    @Test
    fun askingForDailyDemandWritesNothing(): Unit =
        runBlocking {
            sell(1, rice, 2.0)
            sell(3, oil, 4.0)
            val outboxBefore = database.syncOutboxDao().count()
            val riceStockBefore = database.stockMovementDao().currentStockScaled(rice.value)

            demand.series(today = day(10))
            demand.seriesFor(rice, today = day(10), withStockOutDays = true)

            assertEquals(outboxBefore, database.syncOutboxDao().count())
            assertEquals(riceStockBefore, database.stockMovementDao().currentStockScaled(rice.value))
        }
}
