package com.hisab.app.domain

import kotlin.math.roundToLong

/**
 * Quantity as an integer scaled by 1000 (3 decimal places), so 0.5 kg = 500,
 * 3 pieces = 3000. Never a float — see PRD.md section 24, DECISIONS.md D019.
 *
 * Chosen over a plain integer because shops sell fractional quantities
 * (0.5 kg, 1.25 kg, 2.5 litre), not just whole pieces.
 */
private const val SCALE = 1000

@JvmInline
value class Quantity(
    val scaledUnits: Long,
) {
    operator fun plus(other: Quantity): Quantity = Quantity(scaledUnits + other.scaledUnits)

    operator fun minus(other: Quantity): Quantity = Quantity(scaledUnits - other.scaledUnits)

    /** The decimal amount a person would read, e.g. 0.5. */
    fun toDecimal(): Double = scaledUnits.toDouble() / SCALE

    companion object {
        val ZERO = Quantity(0)

        /** Converts a human-entered decimal amount (e.g. 0.5) into a Quantity. */
        fun fromDecimal(decimalAmount: Double): Quantity = Quantity((decimalAmount * SCALE).roundToLong())
    }
}

/** Sums a list of Quantity values (e.g. StockMovement.quantity_delta). Empty list sums to zero. */
fun List<Quantity>.sum(): Quantity = fold(Quantity.ZERO) { acc, value -> acc + value }
