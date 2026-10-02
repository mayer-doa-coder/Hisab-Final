package com.hisab.app.domain.forecast

// The Croston family (Step 87).
//
// Every method before this one answers the question "how much sells per day?"
// with a single smoothed number, and on a corner shop's shelf that question is
// usually the wrong one. Most products do not sell every day. A tin of
// condensed milk sells four at a time, twice a week, and nothing in between; a
// seven-day average of that is 1.14 a day, which is a number that never
// happens.
//
// Croston (1972) splits the question in two and smooths each half separately:
//
//   how big is a sale when one happens?   -> size
//   how many days between sales?          -> interval
//   forecast per day = size / interval
//
// The split is the whole idea, and it is why the two estimates are updated
// only when a sale happens — a quiet Tuesday is evidence about the interval,
// not about the size, and feeding it into a single average corrupts both.
//
// Three members are built here, because the literature is clear that plain
// Croston is not the one to ship:
//
//   CROSTON  the original. Biased upward — the expectation of size/interval is
//            not the ratio of the expectations — which matters because an
//            upward-biased demand forecast becomes an over-ordered shelf.
//   SBA      Syntetos & Boylan's correction: multiply by (1 - alpha/2). One
//            multiplication, no extra state, and it removes most of that bias.
//            The usual recommendation as the default of the family.
//   TSB      Teunter, Syntetos & Babai: smooth the *probability* of demand
//            instead of the interval, and update it every single day. This is
//            the one that notices a product has stopped selling, because its
//            probability decays on every quiet day while Croston's interval
//            estimate sits untouched waiting for a sale that is never coming.
//            Obsolescence is not a corner case here — `docs/PHASE_GUIDE.md`
//            Step 84 names discontinued products, and a shop's shelf is full
//            of lines that quietly died.

/**
 * Croston's two estimates, fitted to one history.
 *
 * [demandDays] is carried so the caller can tell an interval estimated from
 * many sales apart from one estimated from a single sale — with one sale there
 * is no gap to have measured, and the answer rests on the position of that one
 * day rather than on any observed rhythm.
 */
internal class CrostonEstimates(
    val size: Double,
    val interval: Double,
    val demandDays: Int,
) {
    val ratePerDay: Double get() = if (interval <= 0.0) 0.0 else size / interval
}

/**
 * Runs Croston's recursion over [daily], or returns null when nothing sold at
 * all.
 *
 * Initialisation: the size starts at the first sale's size, and the interval at
 * the number of days from the start of the history up to and including that
 * first sale. Both are then updated once per subsequent sale. Starting the
 * interval at 1 instead — as some implementations do — would claim daily demand
 * for a product whose one sale came after three quiet weeks.
 */
internal fun fitCroston(
    daily: List<Double>,
    alpha: Double,
): CrostonEstimates? {
    val demandDays = daily.indices.filter { daily[it] > 0.0 }
    if (demandDays.isEmpty()) return null

    var size = daily[demandDays.first()]
    var interval = (demandDays.first() + 1).toDouble()
    for (position in 1 until demandDays.size) {
        val gap = (demandDays[position] - demandDays[position - 1]).toDouble()
        size += alpha * (daily[demandDays[position]] - size)
        interval += alpha * (gap - interval)
    }
    return CrostonEstimates(size, interval, demandDays.size)
}

/**
 * Croston and SBA differ by one constant factor, so they are one class with two
 * factories — [croston] and [sba]. Writing them twice would let the shared
 * recursion drift between them, and the entire published difference between the
 * two methods is that factor.
 */
class CrostonMethod private constructor(
    private val alpha: Double,
    private val biasCorrected: Boolean,
) : ForecastMethod {
    init {
        require(alpha > 0.0 && alpha <= 1.0) { "Croston's alpha is above 0 and at most 1, got $alpha." }
    }

    override val family: ForecastFamily =
        if (biasCorrected) ForecastFamily.SBA else ForecastFamily.CROSTON

    override val id: String = "${family.code}(a=$alpha)"

    /**
     * Two days: the formula needs two sales to have measured one gap. That is a
     * weaker requirement than it sounds, and [fit] adds the one that matters —
     * an answer built from a single sale is reported as short history however
     * long the series is.
     */
    override val minimumDays: Int = 2

    /** A size, an interval, and the count of quiet days since the last sale. */
    override val stateBytes: Int = BYTES_PER_DOUBLE * 2 + BYTES_PER_COUNTER

    override fun fit(history: DemandHistory): FittedForecast {
        val estimates = fitCroston(history.scaled(), alpha)
        val basis =
            when {
                history.days == 0 -> ForecastBasis.NO_HISTORY

                // No sale at all, or only one: there is no observed rhythm yet.
                estimates == null || estimates.demandDays < 2 -> ForecastBasis.SHORT_HISTORY

                history.days < minimumDays -> ForecastBasis.SHORT_HISTORY

                else -> ForecastBasis.ENOUGH_HISTORY
            }

        val rate = estimates?.ratePerDay ?: 0.0
        val corrected = if (biasCorrected) rate * (1.0 - alpha / 2.0) else rate
        return FlatForecast(this, basis, corrected)
    }

    companion object {
        /**
         * Croston's classic smoothing constant. The original paper and the
         * inventory literature both use a slow value here: the estimates update
         * only on sale days, so at 0.3 a single unusual basket would move the
         * forecast much further than it should.
         */
        const val DEFAULT_ALPHA = 0.1

        /** The original method, biased upward. In the comparison as the reference point. */
        fun croston(alpha: Double = DEFAULT_ALPHA): CrostonMethod = CrostonMethod(alpha, biasCorrected = false)

        /** Syntetos & Boylan's correction. Same state, same cost, less bias. */
        fun sba(alpha: Double = DEFAULT_ALPHA): CrostonMethod = CrostonMethod(alpha, biasCorrected = true)
    }
}

/**
 * TSB (Teunter, Syntetos & Babai): smooth the size on sale days and the
 * *probability of a sale* on every day.
 *
 * ```text
 * a sale today:  size        = size + alpha x (today - size)
 *                probability = probability + beta x (1 - probability)
 * no sale today: probability = probability + beta x (0 - probability)
 * forecast per day = probability x size
 * ```
 *
 * The difference from Croston is the second line of the quiet-day case. Croston
 * learns nothing on a day with no sale; TSB learns that a sale was less likely
 * than it thought. So a product that has stopped selling decays toward zero
 * here, at a rate set by [beta], while Croston keeps predicting the rhythm it
 * last saw — for a product withdrawn from the shelf, forever.
 *
 * That property is why this method is in the comparison and not treated as an
 * exotic variant. Step 84's pipeline already takes the trailing zeros off a
 * discontinued product's series; TSB is what handles the far more common case
 * the pipeline cannot see, a product still nominally stocked that nobody has
 * asked for in three weeks.
 *
 * [beta] is deliberately slower than [alpha]. The probability is updated every
 * day, so it sees roughly [SeasonalNaiveMethod.DEFAULT_PERIOD] times as many
 * updates as the size does on a weekly-selling product; matching the two rates
 * would make the probability jumpy and the forecast with it.
 *
 * State: two numbers, and no history. The smallest footprint of any method here
 * that handles intermittent demand at all.
 */
class TsbMethod(
    private val alpha: Double = DEFAULT_ALPHA,
    private val beta: Double = DEFAULT_BETA,
) : ForecastMethod {
    init {
        require(alpha > 0.0 && alpha <= 1.0) { "TSB's alpha is above 0 and at most 1, got $alpha." }
        require(beta > 0.0 && beta <= 1.0) { "TSB's beta is above 0 and at most 1, got $beta." }
    }

    override val id: String = "${ForecastFamily.TSB.code}(a=$alpha,b=$beta)"

    override val family: ForecastFamily = ForecastFamily.TSB

    override val minimumDays: Int = 2

    /** A size and a probability. That is all, whatever the history's length. */
    override val stateBytes: Int = BYTES_PER_DOUBLE * 2

    override fun fit(history: DemandHistory): FittedForecast {
        val daily = history.scaled()
        val firstSale = daily.indexOfFirst { it > 0.0 }
        val basis =
            when {
                daily.isEmpty() -> ForecastBasis.NO_HISTORY
                firstSale < 0 -> ForecastBasis.SHORT_HISTORY
                daily.size < minimumDays -> ForecastBasis.SHORT_HISTORY
                else -> ForecastBasis.ENOUGH_HISTORY
            }
        if (firstSale < 0) return FlatForecast(this, basis, 0.0)

        var size = daily[firstSale]
        // The first sale arrived after `firstSale + 1` days, so that is the
        // plainest first estimate of how likely a sale is on any given day.
        var probability = 1.0 / (firstSale + 1)
        for (day in firstSale + 1 until daily.size) {
            val today = daily[day]
            if (today > 0.0) {
                size += alpha * (today - size)
                probability += beta * (1.0 - probability)
            } else {
                probability += beta * (0.0 - probability)
            }
        }
        return FlatForecast(this, basis, probability * size)
    }

    companion object {
        const val DEFAULT_ALPHA = 0.1

        /** Slower than alpha: the probability is updated every day, not only on sale days. */
        const val DEFAULT_BETA = 0.05
    }
}
