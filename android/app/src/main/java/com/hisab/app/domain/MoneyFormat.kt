package com.hisab.app.domain

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

private const val POISHA_DECIMAL_PLACES = 2
private const val BANGLA_ZERO = '০'
private const val BANGLA_NINE = '৯'

private val TAKA_PATTERN = Regex("""^\d+(\.\d{1,2})?$""")

/**
 * The amount as a person reads it, in the given language's own digits — so a
 * Bangla screen shows ১২.৫০ and an English one 12.50. Built from the integer
 * poisha with BigDecimal, never floating point (CLAUDE.md).
 */
fun Money.toTakaString(locale: Locale): String {
    val format =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = POISHA_DECIMAL_PLACES
            maximumFractionDigits = POISHA_DECIMAL_PLACES
        }
    return format.format(BigDecimal(minorUnits).movePointLeft(POISHA_DECIMAL_PLACES))
}

/**
 * The amount as it goes back into a text field for editing: plain ASCII
 * digits, a dot, and no grouping, so it reads back exactly through parseTaka.
 */
fun Money.toEditableTaka(): String = BigDecimal(minorUnits).movePointLeft(POISHA_DECIMAL_PLACES).toPlainString()

/**
 * Reads an amount a shopkeeper typed. Accepts Bangla digits (১২.৫০) as
 * readily as ASCII ones, ignores spaces and thousands commas, and allows at
 * most two decimals. Returns null for anything else — empty text, a negative
 * sign, letters, or more precision than poisha can hold — so bad input is
 * never silently rounded into a price.
 */
fun parseTaka(input: String): Money? {
    val normalized =
        normalizeDigits(input)
            .replace(",", "")
            .replace(" ", "")
            .trim()
    if (!TAKA_PATTERN.matches(normalized)) return null
    return Money(BigDecimal(normalized).movePointRight(POISHA_DECIMAL_PLACES).toLong())
}

/** Turns Bangla digits ০–৯ into 0–9 and leaves everything else alone. */
fun normalizeDigits(text: String): String =
    buildString(text.length) {
        for (character in text) {
            append(
                if (character in BANGLA_ZERO..BANGLA_NINE) {
                    '0' + (character - BANGLA_ZERO)
                } else {
                    character
                },
            )
        }
    }
