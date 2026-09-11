package com.hisab.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class QuantityTest {
    // Same documented examples as PRD.md section 24 / DECISIONS.md D019,
    // and the same cases used in server/src/domain/quantity.test.ts.

    @Test
    fun `fromDecimal matches the documented examples`() {
        assertEquals(500L, Quantity.fromDecimal(0.5).scaledUnits) // 0.5 kg
        assertEquals(1250L, Quantity.fromDecimal(1.25).scaledUnits) // 1.25 kg
        assertEquals(2500L, Quantity.fromDecimal(2.5).scaledUnits) // 2.5 litre
        assertEquals(3000L, Quantity.fromDecimal(3.0).scaledUnits) // 3 pieces
    }

    @Test
    fun `toDecimal is the inverse of fromDecimal`() {
        assertEquals(1.25, Quantity.fromDecimal(1.25).toDecimal(), 0.0001)
    }

    @Test
    fun `plus and sum work in scaled integer space`() {
        assertEquals(Quantity(1750), Quantity(500) + Quantity(1250))
        assertEquals(Quantity(2000), listOf(Quantity(500), Quantity(500), Quantity(1000)).sum())
    }

    @Test
    fun `a negative quantity delta such as a sale is allowed`() {
        assertEquals(-500L, Quantity(-500).scaledUnits)
    }
}
