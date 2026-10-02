package com.hisab.app.domain.forecast

import com.hisab.app.domain.Quantity

/**
 * The equal-weight average of several methods' forecasts.
 *
 * Combining forecasts is one of the oldest and most reliably reproduced results
 * in the field: an average of several reasonable methods is usually better than
 * the average member and often better than the best member, because each
 * method's mistakes are partly its own. It is also the cheapest possible way to
 * get that — no weights to fit, nothing to tune, and therefore nothing that can
 * be overfitted to this shop's last two months.
 *
 * Equal weights rather than fitted ones is the point, not a shortcut. Fitting
 * weights needs a validation window carved out of an already short history, and
 * the literature on combination weights is clear that equal weights are hard to
 * beat when the history is small.
 *
 * Its cost is the sum of its members' — this is the one entry in the catalogue
 * that is not nearly free, and Step 88 is where it has to justify that.
 */
class CombinationMethod(
    private val members: List<ForecastMethod>,
) : ForecastMethod {
    init {
        require(members.isNotEmpty()) { "A combination needs at least one method to combine." }
    }

    override val id: String =
        "${ForecastFamily.COMBINATION.code}(${members.joinToString("+") { it.family.code }})"

    override val family: ForecastFamily = ForecastFamily.COMBINATION

    override val minimumDays: Int = members.maxOf { it.minimumDays }

    override val stateBytes: Int = members.sumOf { it.stateBytes }

    override fun fit(history: DemandHistory): FittedForecast {
        val fitted = members.map { it.fit(history) }
        // The weakest member's basis, not the average of them: a combination
        // that includes a method running on too little history is itself
        // running on too little history.
        val basis = fitted.map { it.basis }.maxBy { it.ordinal }
        return CombinedForecast(this, basis, fitted)
    }
}

private class CombinedForecast(
    override val method: ForecastMethod,
    override val basis: ForecastBasis,
    private val members: List<FittedForecast>,
) : FittedForecast {
    override fun forecast(horizon: Int): Forecast {
        requireHorizon(horizon)
        val memberDays = members.map { it.forecast(horizon).perDay }
        val perDay =
            (0 until horizon).map { day ->
                Quantity(memberDays.sumOf { it[day].scaledUnits } / members.size)
            }
        return Forecast(method.id, basis, perDay)
    }
}

/**
 * The methods the Step 88 walk-forward comparison runs, and the settings they
 * run with.
 *
 * Every setting here is a **starting point, not a result.**
 * `docs/RESEARCH_PLAN.md` fixes two rules that this list exists to keep
 * honest: the metrics are frozen before the comparison runs, and a method's
 * settings may be tuned only on days before the final 28-day test window. So
 * the defaults below were chosen from the literature and from what each
 * formula means — never by trying values against results — and Step 88 tunes
 * them in the open, where the tuning is part of the record.
 *
 * The five the PRD requires (section 15) are naive, moving average, EWMA,
 * seasonal naive and a Croston-family method. Five more are here as research
 * candidates, each with its argument written on its own class: the running mean
 * (a baseline with O(1) state), seasonal EWMA, SBA and TSB (the Croston family's
 * two published corrections), ADIDA (temporal aggregation), and an equal-weight
 * combination. Markov-based forecasting is listed as optional by the PRD and is
 * deliberately not here — it needs a state space and a transition matrix per
 * product, which is the one thing in this area that would not be nearly free on
 * a cheap phone, and nothing in the comparison so far suggests the extra
 * accuracy is there to be had. `research/forecasting/METHODS.md` records that
 * reasoning where a reader will find it.
 */
object ForecastMethods {
    /** A week, and four weeks: the two window lengths a shopkeeper would recognise. */
    const val SHORT_WINDOW_DAYS = 7

    const val LONG_WINDOW_DAYS = 28

    /** The five the PRD requires, at their default settings. */
    fun required(): List<ForecastMethod> =
        listOf(
            NaiveMethod(),
            MovingAverageMethod(SHORT_WINDOW_DAYS),
            MovingAverageMethod(LONG_WINDOW_DAYS),
            EwmaMethod(),
            SeasonalNaiveMethod(),
            CrostonMethod.croston(),
        )

    /** The research candidates beyond the required list. */
    fun candidates(): List<ForecastMethod> =
        listOf(
            CumulativeMeanMethod(),
            SeasonalEwmaMethod(),
            CrostonMethod.sba(),
            TsbMethod(),
            AdidaMethod(base = EwmaMethod(), bucketDays = SHORT_WINDOW_DAYS),
            CombinationMethod(listOf(MovingAverageMethod(SHORT_WINDOW_DAYS), EwmaMethod(), CrostonMethod.sba())),
        )

    /** Everything, in a fixed order, so two runs of Step 88 produce the same table. */
    fun all(): List<ForecastMethod> = required() + candidates()
}
