package com.hisab.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyTest {
    @Test
    fun `the default currency is BDT`() {
        assertEquals(Currency.BDT, DEFAULT_CURRENCY)
    }
}
