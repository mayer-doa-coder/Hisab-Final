package com.hisab.app.domain

/**
 * Money as an integer in the smallest currency unit (poisha for BDT).
 * Never a float — see PRD.md section 24, DECISIONS.md D019.
 *
 * A value class so it's a distinct type from a plain Long at compile time,
 * with no runtime wrapper-object cost.
 */
@JvmInline
value class Money(
    val minorUnits: Long,
) {
    operator fun plus(other: Money): Money = Money(minorUnits + other.minorUnits)

    operator fun minus(other: Money): Money = Money(minorUnits - other.minorUnits)

    companion object {
        val ZERO = Money(0)
    }
}

/** Sums a list of Money values (e.g. SaleItem line totals). Empty list sums to zero. */
fun List<Money>.sum(): Money = fold(Money.ZERO) { acc, value -> acc + value }
