package com.hisab.app.domain.forecast

import com.hisab.app.domain.Quantity

/**
 * ADIDA — aggregate, forecast, disaggregate (Nikolopoulos, Syntetos, Boylan,
 * Petropoulos & Assimakopoulos, 2011).
 *
 * Not a forecasting method: a technique that wraps one. It adds up the history
 * into buckets of [bucketDays], fits [base] to the bucket series, forecasts one
 * bucket ahead, and spreads that bucket evenly back over its days.
 *
 * ```text
 * daily   0 0 4 0 0 0 3   0 2 0 0 0 5 0   ...
 * weekly  7               7               ...   -> forecast a week, divide by 7
 * ```
 *
 * The reason it helps is the reason intermittent demand is hard in the first
 * place. A daily series of a product that sells twice a week is mostly zeros,
 * and the zeros are what drag a smoothed level down and make an interval
 * estimate fragile. The same history seen a week at a time has no zeros at all
 * — it is an ordinary, smooth little series, which every method here is better
 * at. Nothing is thrown away: the aggregation is a sum, and the disaggregation
 * puts the whole of it back.
 *
 * It costs almost nothing to run and almost nothing to store — [base]'s state,
 * plus a running total for the bucket in progress — which is why it is worth
 * having in the comparison even though it is not in the PRD's required list
 * (section 15). What it cannot do is say *which* day inside the week the demand
 * lands on; spreading a bucket evenly is a deliberate admission of that, and a
 * reorder decision over a lead time of several days does not need the answer.
 *
 * Buckets are counted back from the most recent day, so it is the oldest
 * leftover days that are dropped rather than the newest. Ten days at a bucket of
 * seven means one bucket made of the last seven days; the three oldest days are
 * not enough to make a second and are left out rather than being averaged into a
 * short bucket that would read as a quiet week.
 */
class AdidaMethod(
    private val base: ForecastMethod,
    private val bucketDays: Int = SeasonalNaiveMethod.DEFAULT_PERIOD,
) : ForecastMethod {
    init {
        require(bucketDays >= 2) { "Aggregating over one day is not aggregating, got $bucketDays." }
    }

    override val id: String = "${ForecastFamily.ADIDA.code}(${base.id},m=$bucketDays)"

    override val family: ForecastFamily = ForecastFamily.ADIDA

    /**
     * [base]'s requirement, counted in days rather than buckets: a base that
     * needs seven observations needs seven *weeks* of history here.
     */
    override val minimumDays: Int = base.minimumDays * bucketDays

    /** Whatever the base keeps, plus the bucket being filled and how far into it we are. */
    override val stateBytes: Int = base.stateBytes + BYTES_PER_DOUBLE + BYTES_PER_COUNTER

    override fun fit(history: DemandHistory): FittedForecast {
        val bucketCount = history.days / bucketDays
        if (bucketCount == 0) {
            // Not even one whole bucket. Falling back to the base method on the
            // raw days is better than refusing, and the basis says plainly that
            // the aggregation this method is named for never happened.
            val fallback = base.fit(history)
            val basis =
                if (history.days == 0) ForecastBasis.NO_HISTORY else ForecastBasis.SHORT_HISTORY
            return FlatForecast(this, basis, dailyRateOf(fallback, days = 1))
        }

        val buckets =
            history.daily
                .takeLast(bucketCount * bucketDays)
                .chunked(bucketDays) { days -> Quantity(days.sumOf { it.scaledUnits }) }
        val fitted = base.fit(DemandHistory(buckets))

        val basis =
            when {
                history.days < minimumDays -> ForecastBasis.SHORT_HISTORY
                fitted.basis == ForecastBasis.ENOUGH_HISTORY -> ForecastBasis.ENOUGH_HISTORY
                else -> fitted.basis
            }
        return FlatForecast(this, basis, dailyRateOf(fitted, days = bucketDays))
    }

    /**
     * One bucket's forecast, spread evenly over its [days].
     *
     * The bucket forecast is read back as a `Quantity`, so it has already been
     * rounded to a thousandth of a unit before being divided. At a bucket of
     * seven days that is an error below 0.0002 units a day — far under the
     * rounding the answer itself gets — and it is the price of every method
     * answering to one interface instead of exposing its internal arithmetic.
     */
    private fun dailyRateOf(
        fitted: FittedForecast,
        days: Int,
    ): Double =
        fitted
            .forecast(1)
            .perDay
            .first()
            .scaledUnits
            .toDouble() / days
}
