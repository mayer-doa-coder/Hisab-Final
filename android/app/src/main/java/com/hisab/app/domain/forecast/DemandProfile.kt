package com.hisab.app.domain.forecast

import kotlin.math.sqrt

// What shape a demand series has (Step 84).
//
// Two numbers describe a retail demand series well enough to say which family
// of method suits it, and both are cheap enough to work out on a phone in one
// pass:
//
//   ADI  — average demand interval: days per demand occurrence. 1.0 means
//          something sells every day; 5.0 means roughly once a week.
//   CV²  — the squared coefficient of variation of the demand *sizes* on the
//          days a sale happened. 0 means every sale is the same size.
//
// Syntetos, Boylan and Croston (2005) cut the plane at ADI = 1.32 and
// CV² = 0.49 and named the four quadrants: smooth, erratic, intermittent,
// lumpy. Those cut-offs come from comparing the error of Croston's method
// against the SBA correction, so they are not arbitrary — they are the points
// where the better method changes.
//
// This is here in Step 84, with the pipeline, because it is a property of the
// *data*, not a choice of method: it says what kind of series a shop's
// products actually produce, which is a research result in its own right (the
// M5 competition found 73% of retail product-store series intermittent and 17%
// lumpy). Which method wins on which quadrant is settled by the walk-forward
// comparison in Step 88, from this project's own numbers — not by importing
// someone else's recommendation.

/** The four shapes of the Syntetos–Boylan–Croston scheme, plus the empty case. */
enum class DemandPattern {
    /** Sells most days, in similar amounts. Ordinary exponential smoothing suits this. */
    SMOOTH,

    /** Sells most days, but in wildly different amounts. */
    ERRATIC,

    /** Long quiet gaps, similar amounts when it does sell. What Croston is for. */
    INTERMITTENT,

    /** Long quiet gaps *and* wildly different amounts — the hardest shape. */
    LUMPY,

    /** Never sold in the window. No interval and no size to describe. */
    NO_DEMAND,
}

/**
 * The shape of one demand series.
 *
 * [averageDemandInterval] and [squaredCoefficientOfVariation] are 0.0 when
 * [pattern] is [DemandPattern.NO_DEMAND] — there is nothing to average.
 */
data class DemandProfile(
    val dayCount: Int,
    val demandDays: Int,
    val averageDemandInterval: Double,
    val squaredCoefficientOfVariation: Double,
    val pattern: DemandPattern,
) {
    companion object {
        /** Syntetos, Boylan & Croston (2005). */
        const val INTERVAL_CUTOFF = 1.32

        /** Syntetos, Boylan & Croston (2005). */
        const val VARIATION_CUTOFF = 0.49
    }
}

/**
 * Works out the shape of [history] in one pass.
 *
 * The variation is measured over the *non-zero* days only. Including the zeros
 * would mix "how often it sells" into "how much it sells when it does", and
 * the whole point of the two numbers is that they are separate — that
 * separation is also exactly what Croston's method does with its two
 * estimates.
 *
 * With a single demand occurrence the spread of sizes is undefined, so CV² is
 * reported as 0 rather than guessed: one observation says nothing about
 * variation. The sample standard deviation (dividing by k − 1) is used when
 * there are two or more, which is what the source scheme uses.
 */
fun profileOf(history: DemandHistory): DemandProfile {
    val sizes = history.daily.map { it.scaledUnits }.filter { it > 0L }
    if (sizes.isEmpty()) {
        return DemandProfile(
            dayCount = history.days,
            demandDays = 0,
            averageDemandInterval = 0.0,
            squaredCoefficientOfVariation = 0.0,
            pattern = DemandPattern.NO_DEMAND,
        )
    }

    val interval = history.days.toDouble() / sizes.size
    val mean = sizes.sumOf { it.toDouble() } / sizes.size
    val variance =
        if (sizes.size < 2) {
            0.0
        } else {
            sizes.sumOf { (it - mean) * (it - mean) } / (sizes.size - 1)
        }
    val coefficient = if (mean == 0.0) 0.0 else sqrt(variance) / mean
    val cv2 = coefficient * coefficient

    val lumpy = interval >= DemandProfile.INTERVAL_CUTOFF
    val variable = cv2 >= DemandProfile.VARIATION_CUTOFF
    return DemandProfile(
        dayCount = history.days,
        demandDays = sizes.size,
        averageDemandInterval = interval,
        squaredCoefficientOfVariation = cv2,
        pattern =
            when {
                lumpy && variable -> DemandPattern.LUMPY
                lumpy -> DemandPattern.INTERMITTENT
                variable -> DemandPattern.ERRATIC
                else -> DemandPattern.SMOOTH
            },
    )
}
