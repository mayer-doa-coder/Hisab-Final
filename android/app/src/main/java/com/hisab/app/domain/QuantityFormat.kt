package com.hisab.app.domain

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

private const val QUANTITY_DECIMAL_PLACES = 3

private val QUANTITY_PATTERN = Regex("""^\d+(\.\d{1,3})?$""")

/**
 * The amount as a person reads it, in the given language's own digits — so a
 * Bangla screen shows ৩ and an English one 3. Trailing zeros are dropped,
 * because a shopkeeper selling three pieces should see "3", not "3.000",
 * while half a kilo still reads "0.5".
 *
 * Built from the integer scaled units with BigDecimal, never floating point
 * (CLAUDE.md).
 */
fun Quantity.toDisplayString(locale: Locale): String {
    val amount = BigDecimal(scaledUnits).movePointLeft(QUANTITY_DECIMAL_PLACES).stripTrailingZeros()
    val decimals = maxOf(amount.scale(), 0)
    val format =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = QUANTITY_DECIMAL_PLACES
        }
    return format.format(amount)
}

/**
 * The amount as it goes into a text field for editing: plain ASCII digits, a
 * dot, and no grouping, so it reads back exactly through parseQuantity.
 */
fun Quantity.toEditableQuantity(): String =
    BigDecimal(scaledUnits)
        .movePointLeft(QUANTITY_DECIMAL_PLACES)
        .stripTrailingZeros()
        .toPlainString()

/**
 * Reads an amount a shopkeeper typed. Accepts Bangla digits (১.৫) as readily
 * as ASCII ones, ignores spaces and thousands commas, and allows at most
 * three decimals — the precision a Quantity can actually hold (D019).
 *
 * Returns null for anything else: empty text, a negative sign, letters, or
 * more precision than the scale holds. Silently rounding a typed quantity
 * would change what a customer is charged for.
 */
fun parseQuantity(input: String): Quantity? {
    val normalized =
        normalizeDigits(input)
            .replace(",", "")
            .replace(" ", "")
            .trim()
    if (!QUANTITY_PATTERN.matches(normalized)) return null
    return Quantity(BigDecimal(normalized).movePointRight(QUANTITY_DECIMAL_PLACES).toLong())
}
