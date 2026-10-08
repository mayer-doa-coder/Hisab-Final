package com.hisab.app.domain.forecast.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The generator, checked for what it promises: the same seed gives the same
 * series, and each shape actually has the property its name claims. A shape
 * that quietly stopped looking like its name would make a whole table in the
 * report mean something else.
 */
class SyntheticDemandTest {
    private val all = SyntheticDemand.generate()

    private fun ofShape(shape: SyntheticShape) = all.filter { it.shape == shape }

    private fun mean(
        series: List<SyntheticSeries>,
        days: IntRange,
    ): Double = series.map { s -> days.map { s.units[it - 1] }.average() }.average()

    @Test
    fun `the random stream matches the published SplitMix64 reference value`() {
        // The first output of SplitMix64 seeded with 0 — the standard test vector.
        assertEquals(0xE220A8397B1DCDAFUL.toLong(), SplitMix64(0).nextLong())
    }

    @Test
    fun `Poisson draws average out to their mean`() {
        val random = SplitMix64(7)
        val mean = List(20_000) { random.poisson(4.0) }.average()
        assertEquals(4.0, mean, 0.1)
    }

    @Test
    fun `the same seed gives the same series and a different seed does not`() {
        assertEquals(all, SyntheticDemand.generate())
        assertNotEquals(all, SyntheticDemand.generate(seed = SyntheticDemand.DEVELOPMENT_SEED + 1))
    }

    @Test
    fun `eight shapes of a hundred series, each fifty-six days, with no negative day`() {
        assertEquals(800, all.size)
        assertEquals(SyntheticShape.entries.toSet(), all.map { it.shape }.toSet())
        SyntheticShape.entries.forEach { assertEquals(SyntheticDemand.SERIES_PER_SHAPE, ofShape(it).size) }
        assertTrue(all.all { it.units.size == SyntheticDemand.DAYS })
        assertTrue(all.all { series -> series.units.all { it >= 0 } })
        assertEquals(all.size, all.map { it.id }.toSet().size)
    }

    @Test
    fun `adding series to a shape does not change the shapes before it`() {
        val fewer = SyntheticDemand.generate(seriesPerShape = 5)
        assertEquals(fewer.filter { it.shape == SyntheticShape.STEADY }, ofShape(SyntheticShape.STEADY).take(5))
    }

    @Test
    fun `intermittent series are mostly quiet days`() {
        val zeroShare = ofShape(SyntheticShape.INTERMITTENT).map { s -> s.units.count { it == 0 }.toDouble() / s.units.size }
        assertTrue("zero share ${zeroShare.average()}", zeroShare.average() > 0.6)
    }

    @Test
    fun `the festival week is about three times an ordinary week`() {
        val series = ofShape(SyntheticShape.FESTIVAL_SPIKE)
        val ratio = mean(series, SyntheticDemand.FESTIVAL_DAYS) / mean(series, 1..37)
        assertEquals(3.0, ratio, 0.4)
    }

    @Test
    fun `a stopped product sells nothing for the last sixteen days, and sold before that`() {
        val series = ofShape(SyntheticShape.STOPPED_SELLING)
        assertTrue(series.all { s -> s.units.takeLast(16).all { it == 0 } })
        assertTrue(mean(series, 1..20) > 2.0)
    }

    @Test
    fun `a new product demand grows over its first weeks`() {
        val series = ofShape(SyntheticShape.RAMP_UP)
        assertTrue(mean(series, 36..56) > 2.5 * mean(series, 1..7))
    }

    @Test
    fun `weekly series repeat a seven-day pattern and steady ones do not`() {
        // Correlation of each day with the day a week earlier: positive for a
        // series built on a weekday profile, near zero for plain noise.
        fun lagSevenCorrelation(units: List<Int>): Double {
            val a = units.dropLast(7).map { it.toDouble() }
            val b = units.drop(7).map { it.toDouble() }
            val ma = a.average()
            val mb = b.average()
            val cov = a.zip(b).sumOf { (x, y) -> (x - ma) * (y - mb) }
            val va = a.sumOf { (it - ma) * (it - ma) }
            val vb = b.sumOf { (it - mb) * (it - mb) }
            return if (va == 0.0 || vb == 0.0) 0.0 else cov / sqrt(va * vb)
        }

        val weekly = ofShape(SyntheticShape.WEEKLY).map { lagSevenCorrelation(it.units) }.average()
        val steady = ofShape(SyntheticShape.STEADY).map { lagSevenCorrelation(it.units) }.average()
        assertTrue("weekly $weekly vs steady $steady", weekly > steady + 0.15)
    }
}
