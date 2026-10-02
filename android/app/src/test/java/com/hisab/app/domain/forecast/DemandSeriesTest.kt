package com.hisab.app.domain.forecast

import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.StockMovement
import com.hisab.app.domain.restock
import com.hisab.app.domain.sell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Step 84's check, in full: a known sale history in, a known daily demand
 * series out.
 *
 * Every case here is one of the four things the step asks the pipeline to
 * handle — missing days, zero demand, discontinued products, short history —
 * plus the rule that decides all of them: the series is one row per calendar
 * day, in order, and nothing is ever shuffled.
 */
class DemandSeriesTest {
    private val rice = EntityId("product-rice")
    private val oil = EntityId("product-oil")
    private val soap = EntityId("product-soap")

    private fun day(dayOfMonth: Int): LocalDate = LocalDate.of(2026, 9, dayOfMonth)

    private fun sold(
        productId: EntityId,
        dayOfMonth: Int,
        units: Int,
    ): SoldQuantity = SoldQuantity(productId, day(dayOfMonth), Quantity.fromDecimal(units.toDouble()))

    /** The whole history the cases below read, written out once. */
    private fun history(): List<SoldQuantity> =
        listOf(
            sold(rice, 1, 3),
            // Two sales of rice on the same day. One demand row, not two.
            sold(rice, 1, 2),
            sold(oil, 3, 7),
            sold(rice, 4, 1),
            sold(soap, 2, 2),
            sold(soap, 5, 3),
            sold(rice, 10, 4),
        )

    private fun unitsPerDay(series: DemandSeries): List<Double> = series.days.map { it.quantitySold.toDecimal() }

    @Test
    fun `a known sale history becomes the right daily demand`() {
        val window = observedWindow(history(), rice, today = day(10))!!
        val series = dailyDemand(rice, history(), window)

        assertEquals(day(1), series.firstDay)
        assertEquals(day(10), series.lastDay)
        assertEquals(10, series.dayCount)
        // 5 on the 1st (two sales added together), 1 on the 4th, 4 on the 10th,
        // and every quiet day in between present as a zero.
        assertEquals(listOf(5.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 4.0), unitsPerDay(series))
        assertEquals(Quantity.fromDecimal(10.0), series.totalSold)
    }

    @Test
    fun `the days come out in calendar order, one per day, with nothing missing`() {
        val window = observedWindow(history(), rice, today = day(10))!!
        val dates = dailyDemand(rice, history(), window).days.map { it.date }

        assertEquals((1..10).map(::day), dates)
        assertEquals(dates.sorted(), dates)
    }

    @Test
    fun `another product's sales never leak into this product's series`() {
        val window = observedWindow(history(), oil, today = day(10))!!
        val series = dailyDemand(oil, history(), window)

        // Oil first sold on the 3rd, so that is where its series starts.
        assertEquals(day(3), series.firstDay)
        assertEquals(listOf(7.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), unitsPerDay(series))
    }

    @Test
    fun `a product with no sales at all has no series, which is not a series of zeros`() {
        val unsold = EntityId("product-never-sold")
        assertNull(observedWindow(history(), unsold, today = day(10)))
    }

    @Test
    fun `a discontinued product's series stops at its last sale instead of trailing zeros`() {
        val stillSold = observedWindow(history(), soap, today = day(10), discontinued = false)!!
        val withdrawn = observedWindow(history(), soap, today = day(10), discontinued = true)!!

        assertEquals(day(10), stillSold.lastDay)
        assertEquals(day(5), withdrawn.lastDay)

        val series = dailyDemand(soap, history(), withdrawn)
        assertEquals(listOf(2.0, 0.0, 0.0, 3.0), unitsPerDay(series))
    }

    @Test
    fun `a window can be cut short at the front without moving any sale`() {
        val window = observedWindow(history(), rice, today = day(10), notBefore = day(4))!!

        assertEquals(day(4), window.firstDay)
        // The sale of 5 on the 1st is outside the window, so it is left out
        // rather than pulled onto the window's first day.
        assertEquals(listOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 4.0), unitsPerDay(dailyDemand(rice, history(), window)))
    }

    @Test
    fun `a reversed sale leaves the day it happened at zero, not the day it was undone`() {
        // This is what the DAO hands over: the reversal's negative line carries
        // the date of the sale it undoes, not the date of the undoing.
        val withReversal =
            history() +
                listOf(
                    sold(rice, 7, 6),
                    SoldQuantity(rice, day(7), Quantity.fromDecimal(-6.0)),
                )
        val window = observedWindow(withReversal, rice, today = day(10))!!
        val series = dailyDemand(rice, withReversal, window)

        assertEquals(0.0, unitsPerDay(series)[6], 0.0)
        assertEquals(listOf(5.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 4.0), unitsPerDay(series))
    }

    @Test
    fun `a day that nets below zero is floored at zero rather than refused`() {
        // Only reachable when a reversal reached this phone before the sale it
        // undoes, so the original's date is not here yet.
        val orphanReversal = history() + SoldQuantity(rice, day(6), Quantity.fromDecimal(-3.0))
        val window = observedWindow(orphanReversal, rice, today = day(10))!!

        assertEquals(0.0, unitsPerDay(dailyDemand(rice, orphanReversal, window))[5], 0.0)
    }

    @Test
    fun `a series with a gap in it cannot be built at all`() {
        val gapped =
            listOf(
                DailyDemand(day(1), Quantity.fromDecimal(1.0)),
                DailyDemand(day(3), Quantity.fromDecimal(1.0)),
            )
        val failure = assertThrows(IllegalArgumentException::class.java) { DemandSeries(rice, gapped) }
        assertTrue(failure.message!!.contains("no gaps"))
    }

    @Test
    fun `a series with a negative day cannot be built at all`() {
        val negative = listOf(DailyDemand(day(1), Quantity.fromDecimal(-1.0)))
        assertThrows(IllegalArgumentException::class.java) { DemandSeries(rice, negative) }
    }

    @Test
    fun `short history is reported against the research minimum, not guessed at`() {
        val window = observedWindow(history(), rice, today = day(10))!!
        val short = dailyDemand(rice, history(), window)
        assertFalse(short.meetsResearchMinimum)

        val longEnough =
            DemandSeries(
                rice,
                (0 until DemandSeries.RESEARCH_MINIMUM_DAYS).map {
                    DailyDemand(day(1).plusDays(it.toLong()), Quantity.ZERO)
                },
            )
        assertTrue(longEnough.meetsResearchMinimum)
    }

    @Test
    fun `every product that has ever sold gets its own series, in a fixed order`() {
        val all = dailyDemandByProduct(history(), today = day(10), discontinued = setOf(soap))

        assertEquals(listOf(oil, rice, soap), all.map { it.productId })
        assertEquals(day(3), all[0].firstDay)
        assertEquals(day(1), all[1].firstDay)
        // Soap is withdrawn, so its series ends at its last sale.
        assertEquals(day(5), all[2].lastDay)
    }

    @Test
    fun `the days the ledger read empty are marked, and the rest are not`() {
        val window = DemandWindow(day(1), day(6))
        val series = dailyDemand(rice, listOf(sold(rice, 1, 3), sold(rice, 3, 7)), window)

        val movements =
            listOf(
                restockOf(10.0, "2026-09-01T09:00:00Z"),
                saleOf(3.0, "2026-09-01T11:00:00Z"),
                // Sells the last 7 on the 3rd, so the shelf reads empty from then on.
                saleOf(7.0, "2026-09-03T11:00:00Z"),
                restockOf(5.0, "2026-09-05T15:00:00Z"),
            )
        val marked = markStockOutDays(series, movements, ZoneOffset.UTC)

        assertEquals(
            // 1st, 2nd: 10 then 7 in stock. 3rd: sells down to zero. 4th: still
            // zero. 5th: opens empty, restocked in the afternoon. 6th: 5 left.
            listOf(false, false, true, true, true, false),
            marked.days.map { it.stockOut },
        )
        assertEquals(3, marked.stockOutDays)
    }

    @Test
    fun `the first day of a series is not marked empty just because nothing was recorded before it`() {
        val window = DemandWindow(day(1), day(2))
        val series = dailyDemand(rice, listOf(sold(rice, 1, 3)), window)

        // The restock and the sale are both on the 1st. The ledger read zero
        // before the restock, but a product whose first sale is that day was
        // plainly in stock — the zero is a missing record, not an empty shelf.
        val movements =
            listOf(
                restockOf(10.0, "2026-09-01T09:00:00Z"),
                saleOf(3.0, "2026-09-01T11:00:00Z"),
            )
        assertEquals(listOf(false, false), markStockOutDays(series, movements, ZoneOffset.UTC).days.map { it.stockOut })
    }

    private fun restockOf(
        units: Double,
        at: String,
    ): StockMovement = restock(rice, Quantity.fromDecimal(units), Instant.parse(at))

    private fun saleOf(
        units: Double,
        at: String,
    ): StockMovement = sell(rice, Quantity.fromDecimal(units), EntityId("sale-$at"), Instant.parse(at))
}
