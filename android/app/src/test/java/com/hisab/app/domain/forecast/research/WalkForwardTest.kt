package com.hisab.app.domain.forecast.research

import com.hisab.app.domain.Quantity
import com.hisab.app.domain.forecast.DemandHistory
import com.hisab.app.domain.forecast.FittedForecast
import com.hisab.app.domain.forecast.Forecast
import com.hisab.app.domain.forecast.ForecastBasis
import com.hisab.app.domain.forecast.ForecastFamily
import com.hisab.app.domain.forecast.ForecastMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scorer, to the digit. Every expected number below is worked out by hand
 * in the comment above it, on series small enough to check on paper — because
 * a metric that is subtly wrong would make every row of the report subtly
 * wrong, and nothing downstream would notice.
 */
class WalkForwardTest {
    /** A method that ignores its history and predicts fixed amounts, so the scorer is tested alone. */
    private class Stub(
        override val id: String,
        private val perDayUnits: List<Int>,
        override val stateBytes: Int = 1,
        private val onFit: (DemandHistory) -> Unit = {},
    ) : ForecastMethod {
        override val family = ForecastFamily.NAIVE
        override val minimumDays = 1

        override fun fit(history: DemandHistory): FittedForecast {
            onFit(history)
            val owner = this
            return object : FittedForecast {
                override val method = owner
                override val basis = ForecastBasis.ENOUGH_HISTORY

                override fun forecast(horizon: Int) = Forecast(id, basis, perDayUnits.take(horizon).map { Quantity(it * 1000L) })
            }
        }
    }

    private val smallFold = Fold(trainDays = 4, horizon = 3)

    @Test
    fun `the folds are exactly the three the phase guide names`() {
        assertEquals(listOf(30, 37, 44), WalkForward.FOLDS.map { it.trainDays })
        assertTrue(WalkForward.FOLDS.all { it.horizon == 7 })
        // 30 + 7 = 37, 37 + 7 = 44, 44 + 7 = 51: each fold forecasts exactly the
        // days the next one then trains on.
        assertEquals(listOf(37, 44, 51), WalkForward.FOLDS.map { it.trainDays + it.horizon })
    }

    @Test
    fun `a method is only ever shown the days before the forecast window`() {
        var seen = -1
        var lastSeenDay = -1L
        val spy =
            Stub("spy", listOf(0, 0, 0)) { history ->
                seen = history.days
                lastSeenDay = history.daily.last().scaledUnits
            }
        // Training days are 1s; the forecast window holds 9s. If any 9 leaked
        // into the history, the last value the method saw would be 9000.
        WalkForward.scoreFold(spy, listOf(1, 1, 1, 1, 9, 9, 9), smallFold)

        assertEquals(4, seen)
        assertEquals(1000L, lastSeenDay)
    }

    @Test
    fun `a constant training window gives no scale, so MASE is undefined and not infinite`() {
        // Forecast 2 a day against actual 1, 3, 5.
        //   MAE = (|2-1| + |2-3| + |2-5|) / 3 = 5/3
        //   forecast total 6, actual total 9: 3 units short, none left over
        val score = WalkForward.scoreFold(Stub("two", listOf(2, 2, 2)), listOf(2, 2, 2, 2, 1, 3, 5), smallFold)

        assertEquals(5.0 / 3, score.mae, 1e-12)
        assertNull(score.naiveScale)
        assertNull(score.mase)
        assertEquals(3000L, score.shortfallScaled)
        assertEquals(0L, score.excessScaled)
        assertEquals(-3000L, score.signedErrorScaled)
    }

    @Test
    fun `MASE divides by the training window own one-step change`() {
        // Training 0 4 0 4: changes 4, 4, 4, so the scale is 4.
        // Forecast 2 a day against actual 4, 0, 2: MAE = (2 + 2 + 0) / 3 = 4/3.
        // MASE = (4/3) / 4 = 1/3. Forecast total 6 equals actual total 6.
        val score = WalkForward.scoreFold(Stub("two", listOf(2, 2, 2)), listOf(0, 4, 0, 4, 4, 0, 2), smallFold)

        assertEquals(4.0, score.naiveScale!!, 1e-12)
        assertEquals(4.0 / 3, score.mae, 1e-12)
        assertEquals(1.0 / 3, score.mase!!, 1e-12)
        assertEquals(0L, score.shortfallScaled)
        assertEquals(0L, score.excessScaled)
    }

    @Test
    fun `over-forecasting is counted as units left over, not as units short`() {
        // Forecast 5 a day (15) against actual 1, 1, 1 (3): 12 units left over.
        val score = WalkForward.scoreFold(Stub("five", listOf(5, 5, 5)), listOf(1, 1, 1, 1, 1, 1, 1), smallFold)

        assertEquals(0L, score.shortfallScaled)
        assertEquals(12_000L, score.excessScaled)
        assertEquals(12_000L, score.signedErrorScaled)
    }

    @Test
    fun `pooled scores average folds, leave undefined MASE out, and say how many`() {
        val constantTraining = WalkForward.scoreFold(Stub("two", listOf(2, 2, 2)), listOf(2, 2, 2, 2, 1, 3, 5), smallFold)
        val steppedTraining = WalkForward.scoreFold(Stub("two", listOf(2, 2, 2)), listOf(0, 4, 0, 4, 4, 0, 2), smallFold)

        val pooled = WalkForward.summarise(listOf(constantTraining, steppedTraining))

        assertEquals(2, pooled.folds)
        // (5/3 + 4/3) / 2
        assertEquals(1.5, pooled.mae, 1e-12)
        // Only the second fold has a scale, so only its 1/3 is averaged.
        assertEquals(1.0 / 3, pooled.mase!!, 1e-12)
        assertEquals(1, pooled.maseUndefinedFolds)
        // (-3000 + 0) scaled units over 6 forecast days = -0.5 a day
        assertEquals(-0.5, pooled.biasPerDay, 1e-12)
        // One of the two folds ran short; 3 units short of 9 + 6 = 15 demanded.
        assertEquals(0.5, pooled.stockoutShare, 1e-12)
        assertEquals(3.0 / 15, pooled.shortfallOfDemand!!, 1e-12)
        assertEquals(0.0, pooled.overstockShare, 1e-12)
        assertEquals(0.0, pooled.excessOfDemand!!, 1e-12)
    }

    @Test
    fun `a share of real demand is not reported when nothing was demanded at all`() {
        val score = WalkForward.scoreFold(Stub("one", listOf(1, 1, 1)), listOf(0, 0, 0, 0, 0, 0, 0), smallFold)
        val pooled = WalkForward.summarise(listOf(score))

        assertNull(pooled.shortfallOfDemand)
        assertNull(pooled.excessOfDemand)
        assertEquals(1.0, pooled.overstockShare, 1e-12)
    }

    @Test
    fun `a fold that does not fit in the series is refused, not scored on missing days`() {
        val failure = runCatching { WalkForward.scoreFold(Stub("x", listOf(1, 1, 1)), listOf(1, 2, 3), smallFold) }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `tied methods share a rank, and the order they are listed in decides nothing`() {
        val ranks = Step88.ranks(mapOf("a" to 1.0, "b" to 1.0, "c" to 2.0))
        assertEquals(1.5, ranks.getValue("a"), 0.0)
        assertEquals(1.5, ranks.getValue("b"), 0.0)
        assertEquals(3.0, ranks.getValue("c"), 0.0)

        val reversed = Step88.ranks(mapOf("c" to 2.0, "b" to 1.0, "a" to 1.0))
        assertEquals(ranks, reversed)
    }

    @Test
    fun `the Pareto view drops a method that is both less accurate and larger`() {
        val steady = SyntheticSeries("steady-001", SyntheticShape.STEADY, List(56) { 5 })
        // Predicts 5, the truth: error 0, 100 bytes.
        val exact = Stub("exact", List(7) { 5 }, stateBytes = 100)
        // Predicts 4: error 1, only 10 bytes — worse, but smaller, so it stays.
        val smallAndOff = Stub("small-and-off", List(7) { 4 }, stateBytes = 10)
        // Predicts 6: the same error as the one above, but 200 bytes — dominated.
        val bigAndOff = Stub("big-and-off", List(7) { 6 }, stateBytes = 200)

        val standings = Step88.standings(Step88.evaluate(listOf(steady), listOf(exact, smallAndOff, bigAndOff)))
        val efficient = standings.filter { it.paretoEfficient }.map { it.method.id }

        assertEquals(setOf("exact", "small-and-off"), efficient.toSet())
    }

    private fun standing(
        id: String,
        rank: Double,
        bytes: Int,
    ) = Step88.Standing(Stub(id, listOf(1), stateBytes = bytes), rank, rank, paretoEfficient = false)

    @Test
    fun `the default rule takes the smaller method when two are within a quarter rank point`() {
        val chosen =
            Step88.provisionalDefault(
                listOf(
                    standing("best-but-large", 2.00, bytes = 100),
                    standing("within-a-quarter-and-small", 2.20, bytes = 10),
                    standing("too-far-behind-though-tiny", 2.30, bytes = 1),
                ),
            )

        assertEquals("within-a-quarter-and-small", chosen.id)
    }

    @Test
    fun `the default rule takes the best rank outright when nothing else is close`() {
        assertEquals(
            "best",
            Step88.provisionalDefault(listOf(standing("best", 1.0, 500), standing("small", 3.0, 1))).id,
        )
    }
}
