package com.hisab.app.domain.forecast

import com.hisab.app.domain.EntityId
import com.hisab.app.domain.ProductUnits
import com.hisab.app.domain.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * What the advisor says about one product, and — as much — when it says
 * nothing. Every case that needs an exact number uses EWMA or a method whose
 * answer is fixed by the test, so the expected values can be worked out on
 * paper; the app's own default method is checked separately, against the same
 * cases, to show it agrees where the answer is unambiguous.
 */
class InventoryAdvisorTest {
    private val product = EntityId("product-coke")
    private val start = LocalDate.of(2026, 9, 1)

    private fun series(unitsPerDay: List<Int>): DemandSeries =
        DemandSeries(
            product,
            unitsPerDay.mapIndexed { index, units -> DailyDemand(start.plusDays(index.toLong()), Quantity(units * 1000L)) },
        )

    private fun steady(
        days: Int,
        perDay: Int,
    ) = series(List(days) { perDay })

    private fun units(amount: Double) = Quantity.fromDecimal(amount)

    private fun advise(
        series: DemandSeries?,
        stock: Double,
        method: ForecastMethod = EwmaMethod(),
        settings: ReorderSettings = ReorderSettings(),
    ): ProductAdvice = InventoryAdvisor(method, settings).advise(series, units(stock), ProductUnits.PIECE)

    private fun ready(advice: ProductAdvice): ProductAdvice.Ready = advice as ProductAdvice.Ready

    /** A method whose forecast is whatever the test says, cycling if the horizon is longer. */
    private class Scripted(
        private val unitsPerDay: List<Int>,
        private val reportedBasis: ForecastBasis = ForecastBasis.ENOUGH_HISTORY,
    ) : ForecastMethod {
        override val id = "scripted"
        override val family = ForecastFamily.NAIVE
        override val minimumDays = 1
        override val stateBytes = 1

        override fun fit(history: DemandHistory): FittedForecast {
            val owner = this
            return object : FittedForecast {
                override val method = owner
                override val basis = reportedBasis

                override fun forecast(horizon: Int) =
                    Forecast(id, basis, List(horizon) { Quantity(unitsPerDay[it % unitsPerDay.size] * 1000L) })
            }
        }
    }

    @Test
    fun `steady sales of 3 a day with 16 on the shelf - the whole answer, by hand`() {
        // EWMA of a constant 3 is 3, so the next 7 days are 21.
        //   margin 20% of 21 = 4.2;  needed 21 + 4.2 - 16 = 9.2;  bought 10 (a whole piece), 0.8 over
        //   stock 16 is gone during day 6 (3 a day: 3, 6, 9, 12, 15, 18)
        //   16 is below the 21 expected before a new order arrives, so it runs out first
        val advice = ready(advise(steady(28, 3), stock = 16.0))

        assertEquals(units(21.0), advice.reorder.expectedDemand)
        assertEquals(units(4.2), advice.reorder.safetyMargin)
        assertEquals(units(10.0), advice.reorder.quantityToBuy)
        assertEquals(units(0.8), advice.reorder.roundedUpBy)
        assertEquals(StockoutOutlook.InDays(6), advice.outlook)
        assertTrue(advice.runsOutBeforeRestock)
    }

    @Test
    fun `the app's own default method gives the same answer where the answer is unambiguous`() {
        // Steady demand is steady under any sensible method, aggregated weekly or not.
        val advice = ready(advise(steady(28, 3), stock = 16.0, method = ForecastMethods.appDefault()))

        assertEquals(units(21.0), advice.reorder.expectedDemand)
        assertEquals(units(10.0), advice.reorder.quantityToBuy)
        assertEquals(StockoutOutlook.InDays(6), advice.outlook)
        assertEquals(ForecastMethods.appDefault().id, advice.methodId)
    }

    @Test
    fun `plenty of stock means nothing to buy, nothing running out and no urgency`() {
        val advice = ready(advise(steady(28, 3), stock = 100.0))

        assertTrue(advice.reorder.enoughStock)
        // 100 at 3 a day lasts 34 days, beyond the 30 it looks ahead.
        assertEquals(StockoutOutlook.NotSoon(InventoryAdvisor.STOCKOUT_LOOKAHEAD_DAYS), advice.outlook)
        assertTrue(!advice.runsOutBeforeRestock)
    }

    @Test
    fun `an empty shelf is already out, and the whole lead time plus margin is to be bought`() {
        // 21 + 4.2 = 25.2 needed, rounded up to 26.
        val advice = ready(advise(steady(28, 3), stock = 0.0))

        assertEquals(StockoutOutlook.AlreadyOut, advice.outlook)
        assertEquals(units(26.0), advice.reorder.quantityToBuy)
        assertTrue(advice.runsOutBeforeRestock)
    }

    @Test
    fun `a ledger below zero reads as already out and orders the same as an empty shelf`() {
        val negative = ready(advise(steady(28, 3), stock = -4.0))
        val empty = ready(advise(steady(28, 3), stock = 0.0))

        assertEquals(StockoutOutlook.AlreadyOut, negative.outlook)
        assertEquals(empty.reorder.quantityToBuy, negative.reorder.quantityToBuy)
        assertTrue(negative.reorder.stockWasNegative)
    }

    @Test
    fun `stock that lasts exactly to a day's end runs out on that day`() {
        // 5 a day and 10 on the shelf: 5 left after day 1, none after day 2.
        val advice = ready(advise(steady(20, 5), stock = 10.0, method = Scripted(listOf(5))))
        assertEquals(StockoutOutlook.InDays(2), advice.outlook)
    }

    @Test
    fun `a busy day is counted on the day it falls, not smoothed across the week`() {
        // Forecast 1, 1, 10, 1, ... against 6 in stock: 5 left, 4 left, then the
        // 10 on day 3 empties it. Stock divided by the average rate would have
        // said about day 5.
        val advice = ready(advise(steady(20, 2), stock = 6.0, method = Scripted(listOf(1, 1, 10, 1, 1, 1, 1))))
        assertEquals(StockoutOutlook.InDays(3), advice.outlook)
    }

    @Test
    fun `a longer lead time covers more days of demand`() {
        val week = ready(advise(steady(28, 3), stock = 16.0))
        val fortnight = ready(advise(steady(28, 3), stock = 16.0, settings = ReorderSettings(leadTimeDays = 14)))

        assertEquals(units(21.0), week.reorder.expectedDemand)
        assertEquals(units(42.0), fortnight.reorder.expectedDemand)
        assertEquals(14, fortnight.reorder.leadTimeDays)
        assertTrue(fortnight.reorder.quantityToBuy.scaledUnits > week.reorder.quantityToBuy.scaledUnits)
    }

    @Test
    fun `a bigger safety margin buys more`() {
        val none = ready(advise(steady(28, 3), stock = 16.0, settings = ReorderSettings(safetyPercent = 0)))
        val double = ready(advise(steady(28, 3), stock = 16.0, settings = ReorderSettings(safetyPercent = 100)))

        // 21 - 16 = 5, and 21 + 21 - 16 = 26.
        assertEquals(units(5.0), none.reorder.quantityToBuy)
        assertEquals(units(26.0), double.reorder.quantityToBuy)
    }

    @Test
    fun `a product with under two weeks of history gets no forecast, only the facts`() {
        assertEquals(ProductAdvice.NotEnoughData(daysObserved = 10, demandDays = 10), advise(steady(10, 3), stock = 16.0))
    }

    @Test
    fun `two weeks of history with only two sale days is still not enough`() {
        val twoSales = series(List(20) { if (it == 4 || it == 12) 5 else 0 })
        assertEquals(ProductAdvice.NotEnoughData(daysObserved = 20, demandDays = 2), advise(twoSales, stock = 16.0))
    }

    @Test
    fun `a product that has never been sold is not a series of zeros and gets the same answer`() {
        assertEquals(ProductAdvice.NotEnoughData(0, 0), advise(null, stock = 16.0, method = ForecastMethods.appDefault()))
    }

    @Test
    fun `a long history of nothing but zeros is not enough either`() {
        assertEquals(ProductAdvice.NotEnoughData(daysObserved = 40, demandDays = 0), advise(steady(40, 0), stock = 16.0))
    }

    @Test
    fun `exactly at the minimum is enough, and one short is not`() {
        val days = InventoryAdvisor.MIN_DAYS_OBSERVED
        val sales = InventoryAdvisor.MIN_DEMAND_DAYS

        fun history(length: Int) = series(List(length) { if (it < sales) 4 else 0 })

        assertTrue(advise(history(days), stock = 5.0) is ProductAdvice.Ready)
        assertTrue(advise(history(days - 1), stock = 5.0) is ProductAdvice.NotEnoughData)
    }

    @Test
    fun `a method that says it has too little to go on is believed over the length of the series`() {
        // Plenty of days and sale days for the advisor, but the method itself
        // reports short history — as the Croston family does for a single sale,
        // and weekly aggregation does for under a week of buckets. A stricter
        // gate in the method is kept, not overridden.
        val cautious = Scripted(listOf(3), reportedBasis = ForecastBasis.SHORT_HISTORY)
        assertTrue(advise(steady(28, 3), stock = 16.0, method = cautious) is ProductAdvice.NotEnoughData)
    }

    @Test
    fun `an intermittent product is advised from its rhythm, within a sensible band`() {
        // 4 units every fifth day for 40 days: 0.8 a day, so about 5.6 expected in a week.
        val intermittent = series(List(40) { if (it % 5 == 4) 4 else 0 })
        val advice = ready(advise(intermittent, stock = 2.0, method = ForecastMethods.appDefault()))

        val expected = advice.reorder.expectedDemand.toDecimal()
        assertTrue("expected demand $expected", expected in 3.0..9.0)
        assertTrue(advice.reorder.quantityToBuy.scaledUnits > 0)
    }
}
