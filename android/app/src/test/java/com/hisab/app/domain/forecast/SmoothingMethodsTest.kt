package com.hisab.app.domain.forecast

import com.hisab.app.domain.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 86's methods, to the digit: EWMA, seasonal naive, and the per-weekday
 * EWMA that sits between them.
 *
 * The recursions are short enough to work out by hand, and every expected number
 * below has its arithmetic written next to it. That matters more here than
 * anywhere else in this package: a smoothing constant applied to the wrong term,
 * or a season off by one day, produces forecasts that look entirely plausible
 * and are wrong every day.
 */
class SmoothingMethodsTest {
    private fun scaled(
        method: ForecastMethod,
        history: DemandHistory,
    ): Long =
        method
            .fit(history)
            .forecast(1)
            .perDay
            .first()
            .scaledUnits

    private fun scaledDays(
        method: ForecastMethod,
        history: DemandHistory,
        horizon: Int,
    ): List<Long> =
        method
            .fit(history)
            .forecast(horizon)
            .perDay
            .map { it.scaledUnits }

    @Test
    fun `EWMA moves its level toward the newest day by alpha`() {
        // Seven quiet days, then a sale of 10. The level starts at the first
        // week's mean, which is 0, and 0 + 0.3 x (10 - 0) = 3.
        assertEquals(3000L, scaled(EwmaMethod(), DemandHistory.ofUnits(0, 0, 0, 0, 0, 0, 0, 10)))

        // A week at 2 a day, then a 9: 2 + 0.3 x (9 - 2) = 4.1.
        assertEquals(4100L, scaled(EwmaMethod(), DemandHistory.ofUnits(2, 2, 2, 2, 2, 2, 2, 9)))
    }

    @Test
    fun `EWMA at alpha one is the naive method`() {
        val eightDays = DemandHistory.ofUnits(1, 2, 3, 4, 5, 6, 7, 8)
        assertEquals(
            scaled(NaiveMethod(), eightDays),
            scaled(EwmaMethod(alpha = 1.0), eightDays),
        )
    }

    @Test
    fun `EWMA forgets a level the shop has left behind`() {
        // Thirty days at 10, then thirty at 2. The running mean still says 6
        // (see SimpleMethodsTest); this has followed the shop down to about 2.
        val halved = DemandHistory(List(30) { Quantity.fromDecimal(10.0) } + List(30) { Quantity.fromDecimal(2.0) })
        assertEquals(2000.0, scaled(EwmaMethod(), halved).toDouble(), 50.0)
    }

    @Test
    fun `EWMA starts from the first week's average, not the first day's`() {
        // A first day that happens to be busy would otherwise set the level for
        // weeks. Starting from the week: (12 + 0 + 0 + 0 + 0 + 0 + 0) / 7 = 1.714…
        val busyFirstDay = DemandHistory.ofUnits(12, 0, 0, 0, 0, 0, 0)
        assertEquals(1714L, scaled(EwmaMethod(), busyFirstDay))
    }

    @Test
    fun `EWMA refuses a smoothing constant outside its range`() {
        assertThrows(IllegalArgumentException::class.java) { EwmaMethod(alpha = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { EwmaMethod(alpha = 1.5) }
    }

    @Test
    fun `seasonal naive replays the last complete week, in order, for as long as asked`() {
        val twoWeeks = DemandHistory.ofUnits(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14)

        // Days 8 to 14 are the most recent week, and the horizon starts right
        // after day 14 — so the first forecast day is day 8's weekday.
        assertEquals(
            listOf(8000L, 9000L, 10000L, 11000L, 12000L, 13000L, 14000L, 8000L, 9000L),
            scaledDays(SeasonalNaiveMethod(), twoWeeks, horizon = 9),
        )
    }

    @Test
    fun `seasonal naive with less than a week to copy falls back to the average and says so`() {
        val fourDays = DemandHistory.ofUnits(1, 2, 3, 4)
        val fitted = SeasonalNaiveMethod().fit(fourDays)

        assertEquals(ForecastBasis.SHORT_HISTORY, fitted.basis)
        // 10 / 4, flat. Inventing a weekly shape from four days would be worse
        // than admitting there isn't one yet.
        assertEquals(listOf(2500L, 2500L, 2500L), fitted.forecast(3).perDay.map { it.scaledUnits })
    }

    @Test
    fun `seasonal naive carries one odd day into every future week`() {
        // Six ordinary days and one unexplained Friday of 40. This is the cost
        // of the simplest possible seasonal method, and the reason seasonal EWMA
        // is next to it in the catalogue.
        val oneWeek = DemandHistory.ofUnits(2, 2, 2, 2, 40, 2, 2)
        assertEquals(
            listOf(2000L, 2000L, 2000L, 2000L, 40000L, 2000L, 2000L),
            scaledDays(SeasonalNaiveMethod(), oneWeek, horizon = 7),
        )
    }

    @Test
    fun `seasonal EWMA smooths each weekday against its own past`() {
        val twoWeeks = DemandHistory.ofUnits(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14)

        // Each weekday sees two observations, a week apart. For the first:
        // 1 + 0.3 x (8 - 1) = 3.1. The next weekday: 2 + 0.3 x (9 - 2) = 4.1.
        assertEquals(
            listOf(3100L, 4100L, 5100L, 6100L, 7100L, 8100L, 9100L),
            scaledDays(SeasonalEwmaMethod(), twoWeeks, horizon = 7),
        )
    }

    @Test
    fun `seasonal EWMA is not fooled by one odd day the way seasonal naive is`() {
        // Three weeks of 2 a day, with a single Friday of 40 in the middle week.
        val threeWeeks =
            DemandHistory.ofUnits(
                2,
                2,
                2,
                2,
                2,
                2,
                2,
                2,
                2,
                2,
                2,
                40,
                2,
                2,
                2,
                2,
                2,
                2,
                2,
                2,
                2,
            )
        val seasonalNaive = scaledDays(SeasonalNaiveMethod(), threeWeeks, horizon = 7)
        val seasonalEwma = scaledDays(SeasonalEwmaMethod(), threeWeeks, horizon = 7)

        // Seasonal naive copies the most recent week, which is ordinary, so it
        // has forgotten the spike entirely. Seasonal EWMA remembers some of it
        // in that one weekday and nothing of it in the others.
        assertTrue(seasonalNaive.all { it == 2000L })
        assertEquals(6, seasonalEwma.count { it == 2000L })
        val busyWeekday = seasonalEwma.single { it != 2000L }
        assertTrue("the spike is remembered: $busyWeekday", busyWeekday in 2001L..20000L)
    }

    @Test
    fun `a weekday seasonal EWMA has never seen starts at the overall average`() {
        // Five days of history against a seven-day season: two weekdays have no
        // observation at all. "We have not traded on a Sunday yet" is not
        // evidence that Sundays sell nothing, so they start at the mean of 3.
        val fiveDays = DemandHistory.ofUnits(1, 2, 3, 4, 5)
        val fitted = SeasonalEwmaMethod().fit(fiveDays)

        assertEquals(ForecastBasis.SHORT_HISTORY, fitted.basis)
        assertEquals(
            listOf(3000L, 3000L, 1000L),
            fitted.forecast(3).perDay.map { it.scaledUnits },
        )
    }

    @Test
    fun `a season of one day is not a season`() {
        assertThrows(IllegalArgumentException::class.java) { SeasonalNaiveMethod(period = 1) }
        assertThrows(IllegalArgumentException::class.java) { SeasonalEwmaMethod(period = 1) }
    }

    @Test
    fun `seasonal EWMA keeps one number per weekday and nothing else`() {
        assertEquals(BYTES_PER_DOUBLE * 7, SeasonalEwmaMethod().stateBytes)
        assertEquals(BYTES_PER_DOUBLE, EwmaMethod().stateBytes)
    }
}
