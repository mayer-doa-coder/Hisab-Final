package com.hisab.app.domain.forecast.research

import com.hisab.app.domain.Quantity
import com.hisab.app.domain.forecast.DemandHistory
import com.hisab.app.domain.forecast.ForecastMethod
import kotlin.math.abs

// Walk-forward scoring (Step 88), as `research/forecasting/PROTOCOL.md` defines it.
//
// A method is fitted on the days before a fold's forecast window and on those
// alone — `fit` is handed a history cut at the origin and nothing after it —
// then scored against the days that actually followed. Then the origin moves
// forward and it is done again. Nothing here looks at a later day to score an
// earlier one.

/** One fold: fit on the first [trainDays] days, forecast the next [horizon]. */
data class Fold(
    val trainDays: Int,
    val horizon: Int,
)

object WalkForward {
    /** `docs/PHASE_GUIDE.md` Step 88, exactly: 1–30 → 31–37, 1–37 → 38–44, 1–44 → 45–51. */
    val FOLDS: List<Fold> = listOf(30, 37, 44).map { Fold(trainDays = it, horizon = 7) }

    /**
     * Scores [method] on one fold of [units] (whole units per day).
     *
     * Quantities stay in scaled integers until the very end, so units short and
     * units left over are exact — a forecast total and an actual total are both
     * whole thousandths, and the comparison between them is never a float
     * comparison.
     */
    fun scoreFold(
        method: ForecastMethod,
        units: List<Int>,
        fold: Fold,
    ): FoldScore {
        require(units.size >= fold.trainDays + fold.horizon) {
            "A series of ${units.size} days cannot hold a fold that trains on ${fold.trainDays} and forecasts ${fold.horizon}."
        }

        val training = units.take(fold.trainDays)
        val actual = units.subList(fold.trainDays, fold.trainDays + fold.horizon).map { it * UNIT_SCALE }

        val forecast =
            method
                .fit(DemandHistory(training.map { Quantity(it * UNIT_SCALE) }))
                .forecast(fold.horizon)
        val predicted = forecast.perDay.map { it.scaledUnits }

        val absoluteErrorScaled = predicted.zip(actual).sumOf { (f, y) -> abs(f - y) }
        val actualTotal = actual.sum()
        val forecastTotal = predicted.sum()

        return FoldScore(
            mae = absoluteErrorScaled.toDouble() / UNIT_SCALE / fold.horizon,
            naiveScale = naiveScale(training),
            signedErrorScaled = forecastTotal - actualTotal,
            shortfallScaled = maxOf(0L, actualTotal - forecastTotal),
            excessScaled = maxOf(0L, forecastTotal - actualTotal),
            actualTotalScaled = actualTotal,
            days = fold.horizon,
        )
    }

    /**
     * The scale MASE divides by: the mean of `|y_t − y_{t−1}|` over the training
     * window, in units. Null when it is zero — a perfectly constant or all-zero
     * window has no natural scale, and dividing by it would make MASE
     * infinite or undefined, not small.
     */
    internal fun naiveScale(training: List<Int>): Double? {
        if (training.size < 2) return null
        val total = training.zipWithNext().sumOf { (before, after) -> abs(after - before) }
        return if (total == 0) null else total.toDouble() / (training.size - 1)
    }

    /** All folds of one series for one method. */
    fun scoreSeries(
        method: ForecastMethod,
        series: SyntheticSeries,
    ): List<FoldScore> = FOLDS.map { scoreFold(method, series.units, it) }

    /** Pools fold scores (all series and folds of one shape, one method) into one row of the table. */
    fun summarise(scores: List<FoldScore>): ShapeSummary {
        require(scores.isNotEmpty()) { "Nothing to summarise." }
        val withScale = scores.filter { it.naiveScale != null }
        val actualTotal = scores.sumOf { it.actualTotalScaled }
        val days = scores.sumOf { it.days }

        return ShapeSummary(
            folds = scores.size,
            mae = scores.map { it.mae }.average(),
            mase = if (withScale.isEmpty()) null else withScale.map { it.mase!! }.average(),
            maseUndefinedFolds = scores.size - withScale.size,
            biasPerDay = scores.sumOf { it.signedErrorScaled }.toDouble() / UNIT_SCALE / days,
            stockoutShare = scores.count { it.shortfallScaled > 0 }.toDouble() / scores.size,
            shortfallOfDemand = ratio(scores.sumOf { it.shortfallScaled }, actualTotal),
            overstockShare = scores.count { it.excessScaled > 0 }.toDouble() / scores.size,
            excessOfDemand = ratio(scores.sumOf { it.excessScaled }, actualTotal),
        )
    }

    /** Null when there was no real demand at all in the group, where a percentage of it means nothing. */
    private fun ratio(
        part: Long,
        whole: Long,
    ): Double? = if (whole == 0L) null else part.toDouble() / whole

    private const val UNIT_SCALE = 1000L
}

/** One method on one fold of one series. */
data class FoldScore(
    /** Mean absolute error, in units a day. */
    val mae: Double,
    /** The in-sample one-step naive error, or null when it is zero. */
    val naiveScale: Double?,
    /** Forecast total minus actual total, scaled by 1000. Positive means over-forecast. */
    val signedErrorScaled: Long,
    val shortfallScaled: Long,
    val excessScaled: Long,
    val actualTotalScaled: Long,
    val days: Int,
) {
    val mase: Double? get() = naiveScale?.let { mae / it }
}

/** One cell group of the report: every fold of every series of one shape, for one method. */
data class ShapeSummary(
    val folds: Int,
    val mae: Double,
    val mase: Double?,
    val maseUndefinedFolds: Int,
    val biasPerDay: Double,
    /** Share of folds where real demand beat the forecast total. */
    val stockoutShare: Double,
    /** Units short as a share of real demand; null if nothing was demanded. */
    val shortfallOfDemand: Double?,
    val overstockShare: Double,
    val excessOfDemand: Double?,
)
