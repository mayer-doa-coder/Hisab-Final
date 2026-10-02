package com.hisab.app.domain.forecast

// Naive, moving average, and the running mean (Step 85).
//
// These are the baselines, and they are in the comparison for a reason that is
// easy to forget: a method that cannot beat them is not worth its code. In the
// M5 retail competition about 92.5% of 5,507 teams failed to beat its best
// simple benchmark (an exponential-smoothing method), and the winner's
// advantage over univariate exponential smoothing fell from 22.4% overall to
// 3.4% at the most granular, noisiest level — which is the level a corner shop
// forecasts at. (Makridakis et al., 2022; see research/forecasting/METHODS.md.)
//
// All three keep a flat forecast. None of them models a trend, and that is a
// choice: on a 28-day window of one shop's daily sales, a fitted trend mostly
// extrapolates noise, and a trend that runs demand negative in two weeks is
// worse than no trend at all.

/**
 * Naive: tomorrow sells what today sold.
 *
 * On smooth daily demand this is a respectable baseline. On intermittent demand
 * it is a bad one by construction — most days sell nothing, so most of its
 * forecasts are zero — and that failure is the measurement Step 88 needs, not
 * a reason to leave it out.
 *
 * Cheapest possible state: one number.
 */
class NaiveMethod : ForecastMethod {
    override val id: String = ForecastFamily.NAIVE.code

    override val family: ForecastFamily = ForecastFamily.NAIVE

    override val minimumDays: Int = 1

    override val stateBytes: Int = BYTES_PER_DOUBLE

    override fun fit(history: DemandHistory): FittedForecast =
        FlatForecast(
            method = this,
            basis = basisFor(history, minimumDays),
            dailyRate =
                history.daily
                    .lastOrNull()
                    ?.scaledUnits
                    ?.toDouble() ?: 0.0,
        )
}

/**
 * Moving average: the mean of the last [window] days.
 *
 * Given fewer than [window] days it averages what there is rather than
 * refusing, and says so through [ForecastBasis.SHORT_HISTORY]. Averaging five
 * days when seven were asked for is a weaker answer, not a wrong one.
 *
 * The window is the method's whole memory, so it is also its whole cost: a
 * 28-day average must keep 28 numbers per product, where EWMA keeps one. For a
 * shop with 200 products that is the difference between 45 KB and 1.6 KB, which
 * is why the window length is a research variable here and not a constant.
 */
class MovingAverageMethod(
    private val window: Int,
) : ForecastMethod {
    init {
        require(window >= 1) { "A moving average needs a window of at least one day, got $window." }
    }

    override val id: String = "${ForecastFamily.MOVING_AVERAGE.code}(w=$window)"

    override val family: ForecastFamily = ForecastFamily.MOVING_AVERAGE

    override val minimumDays: Int = window

    override val stateBytes: Int = BYTES_PER_DOUBLE * window

    override fun fit(history: DemandHistory): FittedForecast {
        val recent = history.last(window).scaled()
        return FlatForecast(
            method = this,
            basis = basisFor(history, minimumDays),
            dailyRate = if (recent.isEmpty()) 0.0 else recent.sum() / recent.size,
        )
    }
}

/**
 * The mean of every day of history, however long it is.
 *
 * Worth its own method rather than a moving average with a very long window,
 * because its cost is different in kind: a running sum and a day count, 12
 * bytes, whatever the history's length. It is the limit EWMA approaches as its
 * smoothing constant goes to zero, and on intermittent demand — where the
 * signal is the long-run rate and almost everything else is noise — it is a
 * stubbornly hard baseline to beat.
 *
 * Its weakness is the other side of the same coin: it never forgets. A product
 * whose sales have halved since Ramadan will be over-forecast by this for
 * months, which is what the smoothing methods in Step 86 exist to fix.
 */
class CumulativeMeanMethod : ForecastMethod {
    override val id: String = "${ForecastFamily.MOVING_AVERAGE.code}(w=all)"

    override val family: ForecastFamily = ForecastFamily.MOVING_AVERAGE

    override val minimumDays: Int = 1

    /** A running sum and a day count, and never any more than that. */
    override val stateBytes: Int = BYTES_PER_DOUBLE + BYTES_PER_COUNTER

    override fun fit(history: DemandHistory): FittedForecast {
        val all = history.scaled()
        return FlatForecast(
            method = this,
            basis = basisFor(history, minimumDays),
            dailyRate = if (all.isEmpty()) 0.0 else all.sum() / all.size,
        )
    }
}
