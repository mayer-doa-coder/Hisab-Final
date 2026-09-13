package com.hisab.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class MoneyFormatTest {
    @Test
    fun readsAPlainAmountAsPoisha() {
        assertEquals(Money(1250), parseTaka("12.50"))
    }

    @Test
    fun readsAWholeNumber() {
        assertEquals(Money(8500), parseTaka("85"))
    }

    @Test
    fun readsOneDecimalPlace() {
        assertEquals(Money(1250), parseTaka("12.5"))
    }

    // A shopkeeper typing on a Bangla keyboard gets Bangla digits.
    @Test
    fun readsBanglaDigits() {
        assertEquals(Money(1250), parseTaka("১২.৫০"))
    }

    @Test
    fun readsAMixOfBanglaAndAsciiDigits() {
        assertEquals(Money(1205), parseTaka("১2.05"))
    }

    @Test
    fun ignoresThousandsCommasAndSpaces() {
        assertEquals(Money(120000), parseTaka(" 1,200 "))
    }

    @Test
    fun rejectsMorePrecisionThanPoisha() {
        assertNull(parseTaka("12.567"))
    }

    @Test
    fun rejectsNegativeAmounts() {
        assertNull(parseTaka("-5"))
    }

    @Test
    fun rejectsLettersAndEmptyText() {
        assertNull(parseTaka("abc"))
        assertNull(parseTaka("12tk"))
        assertNull(parseTaka(""))
        assertNull(parseTaka("   "))
    }

    @Test
    fun showsEnglishDigitsInEnglish() {
        assertEquals("12.50", Money(1250).toTakaString(Locale.ENGLISH))
    }

    @Test
    fun showsBanglaDigitsInBangla() {
        assertEquals("১২.৫০", Money(1250).toTakaString(Locale("bn")))
    }

    @Test
    fun alwaysShowsBothPoishaDigits() {
        assertEquals("85.00", Money(8500).toTakaString(Locale.ENGLISH))
        assertEquals("0.05", Money(5).toTakaString(Locale.ENGLISH))
    }

    // What goes back into a text field must read back as the same amount.
    @Test
    fun editableFormRoundTripsThroughParsing() {
        val amounts = listOf(Money(0), Money(5), Money(1250), Money(120000))
        amounts.forEach { amount ->
            assertEquals(amount, parseTaka(amount.toEditableTaka()))
        }
    }

    @Test
    fun leavesNonDigitCharactersAloneWhenNormalizing() {
        assertEquals("12.50kg", normalizeDigits("১২.৫০kg"))
    }
}
