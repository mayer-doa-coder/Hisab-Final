package com.hisab.app.domain.forecast

import com.hisab.app.domain.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of a demand series: how often it sells, and how alike the sales are.
 *
 * Both numbers feed Step 88 rather than changing any forecast today, and the
 * four names they produce are the Syntetos–Boylan–Croston quadrants. What is
 * tested here is that a series of each shape is actually recognised as that
 * shape — the cut-offs themselves are published constants, not this project's
 * to verify.
 */
class DemandProfileTest {
    private fun history(vararg units: Double): DemandHistory = DemandHistory(units.map { Quantity.fromDecimal(it) })

    private fun everyNthDay(
        days: Int,
        interval: Int,
        vararg sizes: Double,
    ): DemandHistory {
        var seen = 0
        return DemandHistory(
            (0 until days).map { index ->
                if (index % interval == interval - 1) {
                    Quantity.fromDecimal(sizes[seen++ % sizes.size])
                } else {
                    Quantity.ZERO
                }
            },
        )
    }

    @Test
    fun `selling the same amount every day is smooth`() {
        val profile = profileOf(DemandHistory(List(20) { Quantity.fromDecimal(5.0) }))

        assertEquals(20, profile.demandDays)
        assertEquals(1.0, profile.averageDemandInterval, 0.0)
        assertEquals(0.0, profile.squaredCoefficientOfVariation, 0.0)
        assertEquals(DemandPattern.SMOOTH, profile.pattern)
    }

    @Test
    fun `selling every fifth day in equal amounts is intermittent`() {
        val profile = profileOf(everyNthDay(days = 20, interval = 5, 4.0))

        assertEquals(4, profile.demandDays)
        assertEquals(5.0, profile.averageDemandInterval, 0.0)
        assertEquals(0.0, profile.squaredCoefficientOfVariation, 0.0)
        assertEquals(DemandPattern.INTERMITTENT, profile.pattern)
    }

    @Test
    fun `selling every day in wildly different amounts is erratic`() {
        val profile = profileOf(DemandHistory((0 until 10).map { Quantity.fromDecimal(if (it % 2 == 0) 1.0 else 20.0) }))

        assertEquals(1.0, profile.averageDemandInterval, 0.0)
        assertTrue(profile.squaredCoefficientOfVariation > DemandProfile.VARIATION_CUTOFF)
        assertEquals(DemandPattern.ERRATIC, profile.pattern)
    }

    @Test
    fun `selling rarely and in wildly different amounts is lumpy - the hardest shape`() {
        val profile = profileOf(everyNthDay(days = 20, interval = 5, 1.0, 20.0))

        assertEquals(5.0, profile.averageDemandInterval, 0.0)
        assertTrue(profile.squaredCoefficientOfVariation > DemandProfile.VARIATION_CUTOFF)
        assertEquals(DemandPattern.LUMPY, profile.pattern)
    }

    @Test
    fun `a series that never sold has no shape, and is not called smooth`() {
        val profile = profileOf(DemandHistory(List(30) { Quantity.ZERO }))

        assertEquals(DemandPattern.NO_DEMAND, profile.pattern)
        assertEquals(0, profile.demandDays)
        assertEquals(0.0, profile.averageDemandInterval, 0.0)
    }

    @Test
    fun `an empty series has no shape either`() {
        assertEquals(DemandPattern.NO_DEMAND, profileOf(DemandHistory.EMPTY).pattern)
    }

    @Test
    fun `one sale says nothing about how much sales vary`() {
        // Variation over a single observation is undefined, so it is reported as
        // none rather than guessed at — and the series is still correctly seen as
        // an infrequent one.
        val profile = profileOf(history(0.0, 0.0, 0.0, 0.0, 7.0))

        assertEquals(1, profile.demandDays)
        assertEquals(0.0, profile.squaredCoefficientOfVariation, 0.0)
        assertEquals(5.0, profile.averageDemandInterval, 0.0)
        assertEquals(DemandPattern.INTERMITTENT, profile.pattern)
    }

    @Test
    fun `how often it sells and how much it varies are measured separately`() {
        // The same sale sizes, at two different frequencies: only the interval
        // moves. Mixing the zeros into the variation would move both, and the
        // two questions would stop being answerable apart — which is the same
        // separation Croston's method is built on.
        // Both series hold the same number of small and large sales (the days
        // are chosen so), and differ only in how far apart those sales are. The
        // sample variance divides by n - 1, so a different count still moves
        // CV² by a few hundredths — hence the tolerance.
        val often = profileOf(everyNthDay(days = 192, interval = 2, 1.0, 20.0))
        val rarely = profileOf(everyNthDay(days = 192, interval = 8, 1.0, 20.0))

        assertEquals(
            often.squaredCoefficientOfVariation,
            rarely.squaredCoefficientOfVariation,
            0.05,
        )
        assertEquals(2.0, often.averageDemandInterval, 0.0)
        assertEquals(8.0, rarely.averageDemandInterval, 0.0)
    }
}
