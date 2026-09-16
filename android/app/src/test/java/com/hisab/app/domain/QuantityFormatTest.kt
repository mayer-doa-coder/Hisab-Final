package com.hisab.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * Quantities as a shopkeeper reads and types them. The same rules as
 * MoneyFormat: the digits follow the language on screen, and anything that
 * is not a clean quantity is refused rather than silently rounded.
 */
class QuantityFormatTest {
    private val english = Locale.forLanguageTag("en")
    private val bangla = Locale.forLanguageTag("bn-BD")

    @Test
    fun `a whole number of pieces reads without decimals`() {
        assertEquals("3", Quantity(3000).toDisplayString(english))
        assertEquals("12", Quantity(12_000).toDisplayString(english))
    }

    @Test
    fun `a fraction keeps only the decimals it needs`() {
        assertEquals("0.5", Quantity(500).toDisplayString(english))
        assertEquals("1.25", Quantity(1250).toDisplayString(english))
        assertEquals("0.333", Quantity(333).toDisplayString(english))
    }

    @Test
    fun `nothing reads as zero, not as blank`() {
        assertEquals("0", Quantity.ZERO.toDisplayString(english))
    }

    @Test
    fun `a negative quantity keeps its sign`() {
        assertEquals("-3", Quantity(-3000).toDisplayString(english))
    }

    @Test
    fun `a Bangla screen shows Bangla digits`() {
        // ৩ is Bangla three: the digits follow the language, like money (D010).
        assertEquals("৩", Quantity(3000).toDisplayString(bangla))
        assertEquals("০.৫", Quantity(500).toDisplayString(bangla))
    }

    @Test
    fun `parseQuantity reads what a person would type`() {
        assertEquals(Quantity(3000), parseQuantity("3"))
        assertEquals(Quantity(500), parseQuantity("0.5"))
        assertEquals(Quantity(1250), parseQuantity("1.25"))
        assertEquals(Quantity(333), parseQuantity("0.333"))
    }

    @Test
    fun `parseQuantity reads Bangla digits too`() {
        assertEquals(Quantity(3000), parseQuantity("৩"))
        assertEquals(Quantity(1500), parseQuantity("১.৫"))
    }

    @Test
    fun `parseQuantity ignores spaces and grouping commas`() {
        assertEquals(Quantity(1_000_000), parseQuantity("1,000"))
        assertEquals(Quantity(2000), parseQuantity(" 2 "))
    }

    @Test
    fun `parseQuantity refuses anything that is not a clean quantity`() {
        assertNull("empty", parseQuantity(""))
        assertNull("letters", parseQuantity("two"))
        assertNull("negative", parseQuantity("-1"))
        assertNull("too precise for the scale", parseQuantity("0.3333"))
        assertNull("just a dot", parseQuantity("."))
    }

    @Test
    fun `toEditableQuantity reads back exactly through parseQuantity`() {
        for (scaled in listOf(0L, 500L, 1250L, 3000L, 333L, 12_000L)) {
            val quantity = Quantity(scaled)
            assertEquals(quantity, parseQuantity(quantity.toEditableQuantity()))
        }
    }

    @Test
    fun `toEditableQuantity is plain ASCII, whatever the screen language`() {
        assertEquals("1.25", Quantity(1250).toEditableQuantity())
        assertEquals("3", Quantity(3000).toEditableQuantity())
    }
}
