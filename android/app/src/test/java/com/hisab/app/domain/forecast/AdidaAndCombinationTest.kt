package com.hisab.app.domain.forecast

import com.hisab.app.domain.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The two techniques that wrap other methods rather than replacing them:
 * temporal aggregation (ADIDA) and equal-weight combination.
 *
 * Both are additions to the PRD's required list, and both are here on the same
 * argument: they cost almost nothing to run and they fix a specific, visible
 * failure of a method already in the catalogue. These tests are where that
 * claim is either true or not.
 */
class AdidaAndCombinationTest {
    /** 56 days, 7 units on one day a week. A true rate of exactly 1 a day. */
    private fun oneDayAWeek(): DemandHistory =
        DemandHistory((0 until 56).map { if (it % 7 == 0) Quantity.fromDecimal(7.0) else Quantity.ZERO })

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
    fun `aggregating to weeks turns an intermittent series into a smooth one and reads it exactly`() {
        // Seen a day at a time, this series is six zeros out of every seven.
        // Seen a week at a time it is eight identical weeks of 7 — and 7 spread
        // over 7 days is 1 a day, which is the truth.
        val adida = AdidaMethod(base = EwmaMethod(), bucketDays = 7)
        assertEquals(1000L, scaled(adida, oneDayAWeek()))
        assertEquals(ForecastBasis.ENOUGH_HISTORY, adida.fit(oneDayAWeek()).basis)
    }

    @Test
    fun `aggregation fixes the very failure its own base method has on this series`() {
        val history = oneDayAWeek()
        val truth = 1000L

        val plainEwma = abs(scaled(EwmaMethod(), history) - truth)
        val aggregated = abs(scaled(AdidaMethod(base = EwmaMethod(), bucketDays = 7), history) - truth)

        // Plain EWMA has decayed for six quiet days by the time the history
        // ends, so it predicts far too little. The same method over weeks does
        // not, because there are no quiet weeks.
        assertEquals(0L, aggregated)
        assertTrue("plain EWMA was off by $plainEwma", plainEwma > truth / 2)
    }

    @Test
    fun `aggregation keeps every unit of demand it was given`() {
        // The aggregation is a sum and the disaggregation divides it back, so
        // nothing is lost on the way. Over one bucket's worth of days, the
        // forecast total is the bucket's own forecast.
        val adida = AdidaMethod(base = CumulativeMeanMethod(), bucketDays = 7)
        val weekTotal = adida.fit(oneDayAWeek()).forecast(7).total
        assertEquals(Quantity.fromDecimal(7.0), weekTotal)
    }

    @Test
    fun `with less than one whole bucket, aggregation falls back to its base and says so`() {
        val threeDays = DemandHistory.ofUnits(1, 2, 3)
        val fitted = AdidaMethod(base = EwmaMethod(), bucketDays = 7).fit(threeDays)

        assertEquals(ForecastBasis.SHORT_HISTORY, fitted.basis)
        assertEquals(
            scaled(EwmaMethod(), threeDays),
            fitted
                .forecast(1)
                .perDay
                .first()
                .scaledUnits,
        )
    }

    @Test
    fun `aggregation drops the oldest leftover days, never the newest`() {
        // Ten days at a bucket of seven makes one bucket. It must be the last
        // seven days, not the first — a method that quietly forecasts from
        // stale days is worse than one that admits it has too few.
        val quietThenBusy = DemandHistory.ofUnits(0, 0, 0, 7, 7, 7, 7, 7, 7, 7)
        val adida = AdidaMethod(base = CumulativeMeanMethod(), bucketDays = 7)

        // The last seven days hold 49 units, so 7 a day.
        assertEquals(7000L, scaled(adida, quietThenBusy))
    }

    @Test
    fun `aggregating over a single day is not aggregating`() {
        assertThrows(IllegalArgumentException::class.java) { AdidaMethod(base = EwmaMethod(), bucketDays = 1) }
    }

    @Test
    fun `aggregation costs its base method plus one part-filled bucket`() {
        val base = EwmaMethod()
        val adida = AdidaMethod(base = base, bucketDays = 7)
        assertEquals(base.stateBytes + BYTES_PER_DOUBLE + BYTES_PER_COUNTER, adida.stateBytes)
    }

    @Test
    fun `a combination is the plain average of its members, to the unit`() {
        val constant = DemandHistory(List(60) { Quantity.fromDecimal(5.0) })
        val members = listOf(MovingAverageMethod(7), EwmaMethod(), CrostonMethod.sba())

        // On constant demand the moving average and EWMA both read 5 exactly,
        // and SBA reads 4.75 — its bias correction, which on demand this smooth
        // is a cost rather than a gain. (5 + 5 + 4.75) / 3 = 4.916…
        assertEquals(4916L, scaled(CombinationMethod(members), constant))
    }

    @Test
    fun `a combination of one method is that method`() {
        val history = DemandHistory.ofUnits(0, 0, 4, 0, 0, 0, 0, 9, 0, 0)
        assertEquals(
            scaled(CrostonMethod.sba(), history),
            scaled(CombinationMethod(listOf(CrostonMethod.sba())), history),
        )
    }

    @Test
    fun `a combination is only as well-founded as its weakest member`() {
        val threeDays = DemandHistory.ofUnits(1, 2, 3)
        val combined = CombinationMethod(listOf(MovingAverageMethod(28), EwmaMethod())).fit(threeDays)

        // EWMA alone would call three days enough. A 28-day average would not,
        // and the combination contains it.
        assertEquals(ForecastBasis.ENOUGH_HISTORY, EwmaMethod().fit(threeDays).basis)
        assertEquals(ForecastBasis.SHORT_HISTORY, combined.basis)
    }

    @Test
    fun `a combination sits between its members rather than outside them`() {
        val history = DemandHistory((0 until 60).map { if (it % 5 == 4) Quantity.fromDecimal(4.0) else Quantity.ZERO })
        val members = listOf<ForecastMethod>(NaiveMethod(), EwmaMethod(), CrostonMethod.sba())
        val each = members.map { scaled(it, history) }
        val combined = scaled(CombinationMethod(members), history)

        assertTrue("$combined is outside ${each.min()}..${each.max()}", combined in each.min()..each.max())
    }

    @Test
    fun `a combination of nothing is not a method`() {
        assertThrows(IllegalArgumentException::class.java) { CombinationMethod(emptyList()) }
    }

    @Test
    fun `a combination costs what all its members cost`() {
        val members = listOf(MovingAverageMethod(7), EwmaMethod(), CrostonMethod.sba())
        assertEquals(members.sumOf { it.stateBytes }, CombinationMethod(members).stateBytes)
    }
}
