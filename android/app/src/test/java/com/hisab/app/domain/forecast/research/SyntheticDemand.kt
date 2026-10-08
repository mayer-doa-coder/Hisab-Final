package com.hisab.app.domain.forecast.research

import com.hisab.app.domain.Quantity
import com.hisab.app.domain.forecast.DemandHistory

// Seeded synthetic demand for the Step 88 development comparison.
//
// `docs/RESEARCH_PLAN.md` section 2 allows synthetic series "with known shapes
// that real data may not cover yet" and requires the generator and its seed to
// be saved so the exact same series can be rebuilt. This is that generator.
// The shapes and their parameters are fixed in `research/forecasting/PROTOCOL.md`
// and are written out here exactly once.
//
// The same seed gives the same series on every machine. Nothing here calls the
// platform's random generator, and the only transcendental function is
// `StrictMath.exp`, which — unlike `Math.exp` — is specified to give identical
// bits on every JVM.

/** The eight shapes in the protocol. [code] is what the saved CSV and the report print. */
enum class SyntheticShape(
    val code: String,
) {
    STEADY("steady"),
    TRENDING("trending"),
    WEEKLY("weekly"),
    INTERMITTENT("intermittent"),
    LUMPY("lumpy"),
    FESTIVAL_SPIKE("festival_spike"),
    RAMP_UP("ramp_up"),
    STOPPED_SELLING("stopped_selling"),
}

/** One generated series: whole units per day, oldest day first. */
data class SyntheticSeries(
    val id: String,
    val shape: SyntheticShape,
    val units: List<Int>,
) {
    fun history(): DemandHistory = DemandHistory(units.map { Quantity(it * UNIT_SCALE) })

    private companion object {
        const val UNIT_SCALE = 1000L
    }
}

object SyntheticDemand {
    /** The seed of the development set, v1. The final-test set must use a different one. */
    const val DEVELOPMENT_SEED = 20261002L

    const val SERIES_PER_SHAPE = 100

    /** The real-series minimum from `RESEARCH_PLAN.md`. */
    const val DAYS = 56

    /** The festival week of `festival_spike`: days 38 to 44, one-based, which is fold 2's forecast window. */
    val FESTIVAL_DAYS = 38..44

    private const val FESTIVAL_MULTIPLIER = 3.0

    private val WEEKDAY_PROFILE = listOf(0.7, 0.8, 0.9, 1.0, 1.1, 1.6, 0.9)

    /**
     * Every shape in a fixed order, [seriesPerShape] series each, drawn one
     * after another from a single stream — so adding a shape at the end never
     * changes the series before it.
     */
    fun generate(
        seed: Long = DEVELOPMENT_SEED,
        seriesPerShape: Int = SERIES_PER_SHAPE,
    ): List<SyntheticSeries> {
        val random = SplitMix64(seed)
        return SyntheticShape.entries.flatMap { shape ->
            (1..seriesPerShape).map { number ->
                SyntheticSeries(
                    id = "${shape.code}-${number.toString().padStart(3, '0')}",
                    shape = shape,
                    units = series(shape, random),
                )
            }
        }
    }

    private fun series(
        shape: SyntheticShape,
        random: SplitMix64,
    ): List<Int> =
        when (shape) {
            SyntheticShape.STEADY -> {
                val mean = random.between(2.0, 15.0)
                List(DAYS) { random.poisson(mean) }
            }

            SyntheticShape.TRENDING -> {
                val start = random.between(3.0, 10.0)
                val dailyRate = random.between(-0.012, 0.020)
                List(DAYS) { day -> random.poisson(maxOf(0.2, start * (1.0 + dailyRate * day))) }
            }

            SyntheticShape.WEEKLY -> {
                val mean = random.between(3.0, 12.0)
                val firstWeekday = random.below(WEEKDAY_PROFILE.size)
                List(DAYS) { day ->
                    random.poisson(mean * WEEKDAY_PROFILE[(day + firstWeekday) % WEEKDAY_PROFILE.size])
                }
            }

            SyntheticShape.INTERMITTENT -> {
                val saleChance = random.between(0.08, 0.35)
                val sizeMean = random.between(0.5, 3.0)
                List(DAYS) { if (random.nextDouble() < saleChance) 1 + random.poisson(sizeMean) else 0 }
            }

            SyntheticShape.LUMPY -> {
                val saleChance = random.between(0.08, 0.30)
                List(DAYS) {
                    when {
                        random.nextDouble() >= saleChance -> 0
                        random.nextDouble() < 0.7 -> 1 + random.poisson(1.0)
                        else -> 5 + random.poisson(10.0)
                    }
                }
            }

            SyntheticShape.FESTIVAL_SPIKE -> {
                val mean = random.between(3.0, 10.0)
                List(DAYS) { day ->
                    val inFestival = (day + 1) in FESTIVAL_DAYS
                    random.poisson(if (inFestival) mean * FESTIVAL_MULTIPLIER else mean)
                }
            }

            SyntheticShape.RAMP_UP -> {
                val finalMean = random.between(4.0, 12.0)
                List(DAYS) { day ->
                    val reached = minOf(1.0, day / 28.0)
                    random.poisson(finalMean * (0.15 + 0.85 * reached))
                }
            }

            SyntheticShape.STOPPED_SELLING -> {
                val mean = random.between(3.0, 10.0)
                val lastSellingDay = 25 + random.below(16)
                List(DAYS) { day -> if (day < lastSellingDay) random.poisson(mean) else 0 }
            }
        }
}

/**
 * SplitMix64 (Steele, Lea & Flood), written out here because it is eight lines
 * of integer arithmetic and so behaves identically everywhere. Not for
 * anything that needs unpredictability.
 */
internal class SplitMix64(
    seed: Long,
) {
    private var state = seed

    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_ONE
        z = (z xor (z ushr 27)) * MIX_TWO
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1), from the top 53 bits so every double in range is reachable. */
    fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

    fun between(
        low: Double,
        high: Double,
    ): Double = low + (high - low) * nextDouble()

    /** A whole number from 0 up to but not including [bound]. */
    fun below(bound: Int): Int = (nextDouble() * bound).toInt()

    /**
     * A Poisson draw by Knuth's method: multiply uniforms until the product
     * drops under e^-mean. Exact, and fast for the small means used here.
     */
    fun poisson(mean: Double): Int {
        val limit = StrictMath.exp(-mean)
        var count = 0
        var product = nextDouble()
        while (product > limit) {
            count += 1
            product *= nextDouble()
        }
        return count
    }

    private companion object {
        val GOLDEN_GAMMA = 0x9E3779B97F4A7C15UL.toLong()
        val MIX_ONE = 0xBF58476D1CE4E5B9UL.toLong()
        val MIX_TWO = 0x94D049BB133111EBUL.toLong()
    }
}
