package com.hisab.app.domain.forecast

import com.hisab.app.domain.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 85's methods, to the digit: naive, moving average, and the running mean.
 *
 * The universal cases — constant, zero, intermittent, short — run against every
 * method in `ForecastMethodTest`. What is here is the arithmetic, worked out by
 * hand in the comments, so a change to one of these formulas fails on a number
 * rather than on a vague band.
 */
class SimpleMethodsTest {
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

    @Test
    fun `naive repeats the last day it saw`() {
        assertEquals(3000L, scaled(NaiveMethod(), DemandHistory.ofUnits(1, 2, 3)))
    }

    @Test
    fun `naive predicts nothing after a quiet day, however busy the week was`() {
        // The whole weakness of the baseline, in one case: six days of steady
        // sales and one quiet Friday, and it forecasts an empty Saturday.
        assertEquals(0L, scaled(NaiveMethod(), DemandHistory.ofUnits(5, 5, 5, 5, 5, 5, 0)))
    }

    @Test
    fun `a moving average averages exactly its own window`() {
        val tenDays = DemandHistory.ofUnits(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        // (8 + 9 + 10) / 3
        assertEquals(9000L, scaled(MovingAverageMethod(3), tenDays))
        // (4 + 5 + 6 + 7 + 8 + 9 + 10) / 7
        assertEquals(7000L, scaled(MovingAverageMethod(7), tenDays))
    }

    @Test
    fun `a moving average given less than its window averages what it has, and says so`() {
        val tenDays = DemandHistory.ofUnits(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val fitted = MovingAverageMethod(28).fit(tenDays)

        // 55 / 10, not 55 / 28 — a short window is a weaker answer, not a
        // quarter of the right one.
        assertEquals(
            5500L,
            fitted
                .forecast(1)
                .perDay
                .first()
                .scaledUnits,
        )
        assertEquals(ForecastBasis.SHORT_HISTORY, fitted.basis)
    }

    @Test
    fun `a moving average over one whole cycle reads an intermittent rate exactly`() {
        // 4 units every fifth day is 0.8 a day, and a five-day window sees
        // exactly one cycle. It is the one window length that gets this series
        // right, and a shop has no way to know that in advance — which is what
        // the Croston family is for.
        val everyFifthDay = DemandHistory((0 until 40).map { if (it % 5 == 4) Quantity.fromDecimal(4.0) else Quantity.ZERO })
        assertEquals(800L, scaled(MovingAverageMethod(5), everyFifthDay))
    }

    @Test
    fun `a moving average needs a window of at least one day`() {
        assertThrows(IllegalArgumentException::class.java) { MovingAverageMethod(0) }
        assertThrows(IllegalArgumentException::class.java) { MovingAverageMethod(-7) }
    }

    @Test
    fun `the running mean averages every day it has ever seen`() {
        assertEquals(2500L, scaled(CumulativeMeanMethod(), DemandHistory.ofUnits(1, 2, 3, 4)))
    }

    @Test
    fun `the running mean never forgets, which is its weakness`() {
        // Thirty days at 10 a day, then thirty at 2. The shop has plainly
        // changed; this still says 6. Step 86's smoothing is the answer to it.
        val halved = DemandHistory(List(30) { Quantity.fromDecimal(10.0) } + List(30) { Quantity.fromDecimal(2.0) })
        assertEquals(6000L, scaled(CumulativeMeanMethod(), halved))
    }

    @Test
    fun `the running mean costs less to keep than a long moving average`() {
        // The reason it is its own method and not a moving average with a very
        // long window: a sum and a count, against one number per day of window.
        assertTrue(CumulativeMeanMethod().stateBytes < MovingAverageMethod(28).stateBytes)
        assertEquals(BYTES_PER_DOUBLE * 28, MovingAverageMethod(28).stateBytes)
    }
}
