package com.hisab.app.domain.forecast

// EWMA and the seasonal methods (Step 86).
//
// EWMA — simple exponential smoothing — is the method to beat. It keeps one
// number per product and updates it with one multiply and one add per day. In
// the M5 retail competition only 7.5% of teams beat the best exponential-
// smoothing benchmark. That is evidence about retail data in general, not about
// this app's shops — Step 88 is where it is tested here. For a phone that must
// answer for 200 products without a server, one number per product is hard to
// argue with.
//
// The seasonal pair is here because a shop's week is not flat. Which day is
// busy is a property of the shop and its neighbourhood, so neither method below
// is told anything about weekdays: both simply repeat with a period of seven
// days and let the shop's own history say what the shape is.

/**
 * EWMA (simple exponential smoothing): a level that each new day pulls toward
 * itself by [alpha].
 *
 * ```text
 * level = level + alpha x (today - level)
 * forecast = level, every day
 * ```
 *
 * [alpha] is how fast it forgets. At 0.1 the level is roughly a 19-day average;
 * at 0.5 it is a 3-day one. The default is 0.3, chosen as a mid-range starting
 * point and **not** as a result — `docs/RESEARCH_PLAN.md` requires smoothing
 * constants to be tuned only on days before the final test window, which is
 * Step 88's job, not this file's.
 *
 * The level starts at the mean of the first week rather than at the first day's
 * demand. Classic exponential smoothing starts at the first observation, which
 * is fine for smooth series and poor here: on intermittent daily demand the
 * first day is as likely as not to be a zero, and a level that starts at zero
 * takes weeks to climb back. The same initialisation is used for every series,
 * so no series is advantaged by a choice made about it.
 *
 * State: one number, forever, whatever the history's length.
 */
class EwmaMethod(
    private val alpha: Double = DEFAULT_ALPHA,
    private val initialDays: Int = DEFAULT_INITIAL_DAYS,
) : ForecastMethod {
    init {
        require(alpha > 0.0 && alpha <= 1.0) { "EWMA's alpha is above 0 and at most 1, got $alpha." }
        require(initialDays >= 1) { "EWMA needs at least one day to start its level from, got $initialDays." }
    }

    override val id: String = "${ForecastFamily.EWMA.code}(a=$alpha)"

    override val family: ForecastFamily = ForecastFamily.EWMA

    override val minimumDays: Int = 1

    override val stateBytes: Int = BYTES_PER_DOUBLE

    override fun fit(history: DemandHistory): FittedForecast {
        val daily = history.scaled()
        val basis = basisFor(history, minimumDays)
        if (daily.isEmpty()) return FlatForecast(this, basis, 0.0)

        val start = daily.take(initialDays)
        var level = start.sum() / start.size
        daily.drop(start.size).forEach { today ->
            level += alpha * (today - level)
        }
        return FlatForecast(this, basis, level)
    }

    companion object {
        const val DEFAULT_ALPHA = 0.3

        /** One week, so the level starts from a whole week's average rather than one day of it. */
        const val DEFAULT_INITIAL_DAYS = 7
    }
}

/**
 * Seasonal naive: next Tuesday sells what last Tuesday sold.
 *
 * The only method here whose forecast is not flat — it repeats the most recent
 * complete [period] days, in order, for as long as the horizon runs.
 *
 * It is a baseline, and a deliberately brittle one: every weekday's prediction
 * rests on exactly one observation, so one unusual Friday is carried into every
 * future Friday. That is the honest cost of the simplest possible seasonal
 * method, and it is why [SeasonalEwmaMethod] sits next to it.
 *
 * With less than a full period of history there is no weekday to copy, so it
 * falls back to the mean of what exists and reports
 * [ForecastBasis.SHORT_HISTORY]. Inventing a weekly shape from four days would
 * be worse than admitting there isn't one.
 *
 * State: the last [period] days.
 */
class SeasonalNaiveMethod(
    private val period: Int = DEFAULT_PERIOD,
) : ForecastMethod {
    init {
        require(period >= 2) { "A season is at least two days long, got $period." }
    }

    override val id: String = "${ForecastFamily.SEASONAL_NAIVE.code}(p=$period)"

    override val family: ForecastFamily = ForecastFamily.SEASONAL_NAIVE

    override val minimumDays: Int = period

    override val stateBytes: Int = BYTES_PER_DOUBLE * period

    override fun fit(history: DemandHistory): FittedForecast {
        val daily = history.scaled()
        val basis = basisFor(history, minimumDays)
        if (daily.size < period) {
            return FlatForecast(this, basis, if (daily.isEmpty()) 0.0 else daily.sum() / daily.size)
        }
        return SeasonalCycleForecast(this, basis, daily.takeLast(period))
    }

    companion object {
        /** A week. A shop's rhythm is weekly — market days, pay days, Friday. */
        const val DEFAULT_PERIOD = 7
    }
}

/**
 * The fitted form of [SeasonalNaiveMethod]: one cycle of demand, replayed.
 *
 * [cycle] is in calendar order and ends with the most recent day, so the first
 * forecast day continues straight on from it.
 */
internal class SeasonalCycleForecast(
    override val method: ForecastMethod,
    override val basis: ForecastBasis,
    private val cycle: List<Double>,
) : FittedForecast {
    override fun forecast(horizon: Int): Forecast {
        requireHorizon(horizon)
        return Forecast(
            method.id,
            basis,
            List(horizon) { day -> quantityOf(cycle[day % cycle.size]) },
        )
    }
}

/**
 * Seasonal EWMA: one smoothed level per day of the week.
 *
 * Each weekday keeps its own EWMA level and is updated only by its own days, so
 * a Friday spike teaches the model about Fridays and nothing else:
 *
 * ```text
 * level[weekday] = level[weekday] + alpha x (today - level[weekday])
 * forecast for a future day = level[that day's weekday]
 * ```
 *
 * This is not in the PRD's required list (section 15) — it is an addition, and
 * the argument for it is specific. Seasonal naive captures a shop's weekly
 * shape from a single week and is therefore at the mercy of one odd day;
 * ordinary EWMA is robust but flattens the week away entirely. Keeping seven
 * levels instead of one costs 56 bytes per product and gets both: a weekly
 * shape, averaged over every week of history. Whether it earns its place is
 * Step 88's answer, not this file's — it is here so the comparison can be made
 * from this shop's data instead of from a recommendation.
 *
 * A weekday never seen in the history starts at the overall mean rather than at
 * zero: "we have never traded on a Sunday yet" is not evidence that Sundays
 * sell nothing.
 */
class SeasonalEwmaMethod(
    private val alpha: Double = EwmaMethod.DEFAULT_ALPHA,
    private val period: Int = SeasonalNaiveMethod.DEFAULT_PERIOD,
) : ForecastMethod {
    init {
        require(alpha > 0.0 && alpha <= 1.0) { "Seasonal EWMA's alpha is above 0 and at most 1, got $alpha." }
        require(period >= 2) { "A season is at least two days long, got $period." }
    }

    override val id: String = "${ForecastFamily.SEASONAL_EWMA.code}(a=$alpha,p=$period)"

    override val family: ForecastFamily = ForecastFamily.SEASONAL_EWMA

    override val minimumDays: Int = period

    override val stateBytes: Int = BYTES_PER_DOUBLE * period

    override fun fit(history: DemandHistory): FittedForecast {
        val daily = history.scaled()
        val basis = basisFor(history, minimumDays)
        if (daily.isEmpty()) return FlatForecast(this, basis, 0.0)

        val mean = daily.sum() / daily.size
        val levels = DoubleArray(period) { Double.NaN }
        daily.forEachIndexed { index, today ->
            val slot = index % period
            levels[slot] =
                if (levels[slot].isNaN()) today else levels[slot] + alpha * (today - levels[slot])
        }
        for (slot in levels.indices) {
            if (levels[slot].isNaN()) levels[slot] = mean
        }

        // The next day continues the same rotation the history was walked in,
        // so slot arithmetic here must start where fitting stopped.
        val fromNextDay = List(period) { offset -> levels[(daily.size + offset) % period] }
        return SeasonalCycleForecast(this, basis, fromNextDay)
    }
}
