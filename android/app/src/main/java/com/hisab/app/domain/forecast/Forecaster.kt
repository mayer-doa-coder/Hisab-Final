package com.hisab.app.domain.forecast

import com.hisab.app.domain.Quantity
import com.hisab.app.domain.sum
import kotlin.math.max
import kotlin.math.roundToLong

// The one interface every forecasting method answers to (Steps 85–87).
//
//   fit(history)      — read a demand series, keep whatever the method needs
//   forecast(horizon) — say how much will sell on each of the next N days
//
// Two operations, split across two types on purpose: fitting reads history and
// produces a fitted model, and forecasting reads only that model. So a fitted
// model cannot quietly reach back into the history it came from, which is the
// mistake that makes a walk-forward evaluation (Step 88) lie — a method that
// peeks past its training window scores well and would fail in a shop.
//
// Every method below is exact about three things a research comparison needs
// and most forecasting code leaves vague:
//
//   * how many days of history it *needs* before its answer means anything
//     ([ForecastMethod.minimumDays]), rather than silently producing a number
//     from two days of data;
//   * how much it must remember between days ([ForecastMethod.stateBytes]) —
//     RQ4's "state/model size", and the reason a phone can run all of these;
//   * whether the answer it just gave rests on enough history
//     ([ForecastBasis]), so Step 95 can say "not enough data yet" instead of
//     guessing.
//
// Arithmetic note. Money and quantity are integers everywhere else in this
// codebase and never floats (CLAUDE.md, D019). Smoothing needs fractions — a
// seven-day average of 3 units is 0.428…/day — so these methods work in
// `Double` internally and round to a `Quantity` (integer, scaled by 1000) at
// the boundary. That is not a hole in the rule: the rule exists so recorded
// money and stock cannot drift, and a forecast is never recorded. No forecast
// value is ever written to the ledger — D050.

/** Which family a method belongs to, for grouping rows in the Step 88 table. */
enum class ForecastFamily(
    val code: String,
) {
    NAIVE("naive"),
    MOVING_AVERAGE("moving_average"),
    EWMA("ewma"),
    SEASONAL_NAIVE("seasonal_naive"),
    SEASONAL_EWMA("seasonal_ewma"),
    CROSTON("croston"),
    SBA("sba"),
    TSB("tsb"),
    ADIDA("adida"),
    COMBINATION("combination"),
}

/** How much history the answer rests on. Step 95 turns this into words. */
enum class ForecastBasis {
    /** At least [ForecastMethod.minimumDays] of history, and whatever else the method needs. */
    ENOUGH_HISTORY,

    /** Some history, but less than the method needs. The numbers are produced anyway, and must be labelled. */
    SHORT_HISTORY,

    /** No history at all. The forecast is zero, which is an admission, not a prediction. */
    NO_HISTORY,
}

/**
 * Chronological daily demand, oldest day first, one entry per calendar day
 * with no gaps — what [DemandSeries.history] produces.
 *
 * The gaplessness is the whole contract: every method here counts positions in
 * this list as days, so a list with quiet days left out would be read as a
 * different series entirely.
 */
data class DemandHistory(
    val daily: List<Quantity>,
) {
    init {
        require(daily.all { it.scaledUnits >= 0 }) {
            "Demand cannot be negative. A reversal is netted against the day the sale happened."
        }
    }

    val days: Int get() = daily.size

    val total: Quantity get() = daily.sum()

    /** The most recent [count] days, or all of them if there are fewer. */
    fun last(count: Int): DemandHistory = DemandHistory(daily.takeLast(count))

    internal fun scaled(): List<Double> = daily.map { it.scaledUnits.toDouble() }

    companion object {
        val EMPTY = DemandHistory(emptyList())

        /** Handy in tests: `DemandHistory.ofUnits(3, 0, 0, 5)` is 3 pieces, two quiet days, 5 pieces. */
        fun ofUnits(vararg units: Int): DemandHistory = DemandHistory(units.map { Quantity.fromDecimal(it.toDouble()) })

        /** Handy in tests: scaled units straight through, so fractions can be written exactly. */
        fun ofScaled(vararg scaledUnits: Long): DemandHistory = DemandHistory(scaledUnits.map { Quantity(it) })
    }
}

/** What a method predicts for the next [Forecast.perDay] `.size` days, in order. */
data class Forecast(
    val method: String,
    val basis: ForecastBasis,
    val perDay: List<Quantity>,
) {
    /** What the whole horizon adds up to — what the reorder engine asks for (Step 89). */
    val total: Quantity get() = perDay.sum()
}

/**
 * A forecasting method: a rule, plus its settings, that can be fitted to a
 * demand history.
 *
 * Implementations hold settings only (a window length, a smoothing constant)
 * and no fitted state, so one instance is safe to reuse across every product
 * in a shop.
 */
interface ForecastMethod {
    /** The method and its settings, e.g. `moving_average(w=7)`. One row of the Step 88 table. */
    val id: String

    val family: ForecastFamily

    /**
     * The fewest days of history from which this method's formula produces an
     * informed answer.
     *
     * It is deliberately the formula's own requirement, not a judgement about
     * how much history a shopkeeper should have — that is one number for the
     * whole app (`DemandSeries.RESEARCH_MINIMUM_DAYS`), not one per method.
     */
    val minimumDays: Int

    /**
     * Bytes that must be carried from one day to the next to keep forecasting
     * without re-reading history.
     *
     * This is the honest measure of a method's footprint on a phone, and it is
     * what separates these methods from a model: EWMA needs 8 bytes forever,
     * Croston 20, while a 28-day moving average must keep its 28-day window.
     * Doubles are counted at 8 bytes and counters at 4, ignoring object
     * overhead, so the numbers compare methods rather than describe the JVM.
     */
    val stateBytes: Int

    fun fit(history: DemandHistory): FittedForecast
}

/** One method, fitted to one product's history. Holds no reference back to that history. */
interface FittedForecast {
    val method: ForecastMethod

    val basis: ForecastBasis

    fun forecast(horizon: Int): Forecast
}

/**
 * A fitted method that predicts the same amount every day — which is all of
 * them except seasonal naive.
 *
 * That is not a simplification: none of naive, moving average, EWMA, Croston,
 * SBA or TSB carries any notion of *when* in the future a day falls, so any
 * shape one of them appeared to produce would be invented here rather than
 * learned from the shop.
 */
internal class FlatForecast(
    override val method: ForecastMethod,
    override val basis: ForecastBasis,
    private val dailyRate: Double,
) : FittedForecast {
    override fun forecast(horizon: Int): Forecast {
        requireHorizon(horizon)
        val perDay = quantityOf(dailyRate)
        return Forecast(method.id, basis, List(horizon) { perDay })
    }
}

internal fun requireHorizon(horizon: Int) {
    require(horizon >= 1) { "A forecast horizon is at least one day, got $horizon." }
}

/**
 * Rounds a rate in scaled units to a [Quantity], never below zero.
 *
 * The floor is not defensive tidying: a negative prediction is meaningless for
 * demand, and a seasonal or combined method could otherwise carry one through
 * from arithmetic on the way.
 */
internal fun quantityOf(scaledRate: Double): Quantity = Quantity(max(0.0, scaledRate).roundToLong())

/** Empty history, some history, or enough — the same judgement for every method. */
internal fun basisFor(
    history: DemandHistory,
    minimumDays: Int,
): ForecastBasis =
    when {
        history.days == 0 -> ForecastBasis.NO_HISTORY
        history.days < minimumDays -> ForecastBasis.SHORT_HISTORY
        else -> ForecastBasis.ENOUGH_HISTORY
    }

internal const val BYTES_PER_DOUBLE = 8

internal const val BYTES_PER_COUNTER = 4
