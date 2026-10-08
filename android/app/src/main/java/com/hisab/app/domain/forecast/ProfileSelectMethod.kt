package com.hisab.app.domain.forecast

/**
 * Picks the method from the shape of the product's own history.
 *
 * Smooth and erratic series (something sells most days) go to [smooth],
 * intermittent and lumpy ones (long quiet gaps) go to [intermittent]. The shape
 * is the Syntetos–Boylan–Croston classification already worked out by
 * [profileOf], and the default pairing is the one that scheme recommends:
 * exponential smoothing where demand arrives most days, and the bias-corrected
 * Croston (SBA) where it does not.
 *
 * Why it is worth having next to the methods it chooses between: neither one
 * is good everywhere. EWMA averages quiet days in with sale days, which is
 * wrong for a product that sells every fifth day; SBA assumes a rhythm of
 * sales, which is needless for a product that sells every day. A shop has both
 * kinds of product on the same shelf, and one rule for the whole shelf is
 * wrong for half of it. Choosing per product costs one pass over the history
 * and no extra model.
 *
 * The choice is made once per fit from the whole history, so it can change as
 * a product's pattern changes; it is not a vote and not a blend.
 *
 * The mapping is fixed from the literature, **not** fitted to the project's
 * own results (`research/forecasting/PROTOCOL.md`).
 *
 * State: both members' state, because the shape can change from one day to the
 * next and either method may be the one needed tomorrow, plus the running
 * counts the shape is worked out from — days, sale days, a sum and a sum of
 * squares.
 */
class ProfileSelectMethod(
    private val smooth: ForecastMethod = EwmaMethod(),
    private val intermittent: ForecastMethod = CrostonMethod.sba(),
) : ForecastMethod {
    override val id: String = "${ForecastFamily.SELECTOR.code}(${smooth.family.code}/${intermittent.family.code})"

    override val family: ForecastFamily = ForecastFamily.SELECTOR

    override val minimumDays: Int = maxOf(smooth.minimumDays, intermittent.minimumDays)

    override val stateBytes: Int = smooth.stateBytes + intermittent.stateBytes + PROFILE_STATE_BYTES

    override fun fit(history: DemandHistory): FittedForecast = Selected(this, chosenFor(history).fit(history))

    /** What was chosen for this history, so a caller or a test can see the decision. */
    fun chosenFor(history: DemandHistory): ForecastMethod =
        when (profileOf(history).pattern) {
            DemandPattern.INTERMITTENT, DemandPattern.LUMPY -> intermittent
            DemandPattern.SMOOTH, DemandPattern.ERRATIC, DemandPattern.NO_DEMAND -> smooth
        }

    private class Selected(
        override val method: ForecastMethod,
        private val inner: FittedForecast,
    ) : FittedForecast {
        override val basis: ForecastBasis = inner.basis

        // Answers under this method's own id, so a comparison table names the
        // selector and not whichever method it happened to pick.
        override fun forecast(horizon: Int): Forecast = inner.forecast(horizon).copy(method = method.id)
    }

    private companion object {
        /** Days and sale days (4 each), a running sum and a running sum of squares (8 each). */
        const val PROFILE_STATE_BYTES = BYTES_PER_COUNTER * 2 + BYTES_PER_DOUBLE * 2
    }
}
