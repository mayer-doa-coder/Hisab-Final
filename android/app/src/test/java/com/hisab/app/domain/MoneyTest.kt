package com.hisab.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {
    // Kotlin enforces "never a float" for Money at compile time (Money(Long)
    // simply cannot accept a Double) — stronger than the runtime check needed
    // on the TypeScript side, where `number` doesn't distinguish int/float.
    // Same invariant, different enforcement mechanism per platform (see
    // docs/PHASE_GUIDE.md M2's note on shared invariants, not shared code).

    @Test
    fun `money accepts integers, including negative for reversals`() {
        assertEquals(100L, Money(100).minorUnits)
        assertEquals(-50L, Money(-50).minorUnits)
        assertEquals(0L, Money.ZERO.minorUnits)
    }

    @Test
    fun `plus and minus stay integer`() {
        assertEquals(Money(700), Money(500) + Money(200))
        assertEquals(Money(500), Money(700) - Money(200))
    }

    @Test
    fun `sum sums a list, empty list sums to zero`() {
        assertEquals(Money(600), listOf(Money(100), Money(200), Money(300)).sum())
        assertEquals(Money.ZERO, emptyList<Money>().sum())
    }
}
