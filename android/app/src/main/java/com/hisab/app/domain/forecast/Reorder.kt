package com.hisab.app.domain.forecast

import com.hisab.app.domain.ProductUnits
import com.hisab.app.domain.Quantity

// The reorder engine (Step 89).
//
// Inputs: current stock, expected demand, lead time, safety margin.
// Output: a quantity to buy, and every number that went into it.
//
// ```text
// buy = expected demand + safety margin - stock, rounded up to a buyable amount
// ```
//
// Nothing here forecasts, reads the database or touches the screen. The expected
// demand arrives as a number, which is what lets the arithmetic be tested on a
// laptop and — more to the point — lets the explanation be checked: the sentence
// a shopkeeper reads is built from the same fields that produced the quantity,
// so the numbers in it cannot disagree with the answer.
//
// All of it is integer arithmetic on `Quantity` (scaled by 1000). A percentage is
// applied with whole numbers and rounded half up, never through a float
// (CLAUDE.md, D019).

/**
 * What the shopkeeper can configure about reordering (PRD section 16: "optional
 * lead time, configurable safety margin").
 *
 * Both defaults are **assumptions, not findings.** A week matches how a small
 * shop typically restocks and the 7-day horizon the Step 88 comparison scored;
 * 20% is a round number with no study behind it. Neither has been checked
 * against a real shop, and there is no settings screen yet, so today they are
 * parameters. The values a pilot shop actually uses should replace them.
 */
data class ReorderSettings(
    /** Days from placing an order to goods arriving: the stretch the stock must cover. */
    val leadTimeDays: Int = DEFAULT_LEAD_TIME_DAYS,
    /** Extra stock on top of expected demand, as a whole percentage of it. */
    val safetyPercent: Int = DEFAULT_SAFETY_PERCENT,
) {
    init {
        require(leadTimeDays in 1..MAX_LEAD_TIME_DAYS) {
            "Lead time is between 1 and $MAX_LEAD_TIME_DAYS days, got $leadTimeDays."
        }
        require(safetyPercent in 0..MAX_SAFETY_PERCENT) {
            "Safety margin is between 0% and $MAX_SAFETY_PERCENT%, got $safetyPercent."
        }
    }

    companion object {
        const val DEFAULT_LEAD_TIME_DAYS = 7
        const val DEFAULT_SAFETY_PERCENT = 20
        const val MAX_LEAD_TIME_DAYS = 60
        const val MAX_SAFETY_PERCENT = 200
    }
}

/**
 * A recommendation, with its arithmetic.
 *
 * When something is to be bought, these always hold exactly:
 *
 * ```text
 * countedStock + quantityToBuy = expectedDemand + safetyMargin + roundedUpBy
 * ```
 *
 * and the explanation shown to a shopkeeper prints these same fields, so what
 * they read adds up (Step 89's check). `init` refuses to build one that does
 * not.
 */
data class ReorderRecommendation(
    /** What the ledger says, which can be negative (D031). Shown as it is. */
    val currentStock: Quantity,
    /** What the arithmetic used: the ledger's figure, or zero if the ledger is below zero. */
    val countedStock: Quantity,
    /** The forecast over the lead time, to one decimal place. */
    val expectedDemand: Quantity,
    val safetyMargin: Quantity,
    /** What buying a whole, buyable amount added beyond what was needed. Zero when nothing needed rounding. */
    val roundedUpBy: Quantity,
    val quantityToBuy: Quantity,
    val leadTimeDays: Int,
    val safetyPercent: Int,
) {
    init {
        require(quantityToBuy.scaledUnits >= 0) { "A purchase is never negative." }
        require(roundedUpBy.scaledUnits >= 0) { "Rounding up never takes anything away." }
        if (quantityToBuy.scaledUnits > 0) {
            require(countedStock + quantityToBuy == expectedDemand + safetyMargin + roundedUpBy) {
                "The recommendation does not add up: stock ${countedStock.scaledUnits} + buy ${quantityToBuy.scaledUnits} " +
                    "is not demand ${expectedDemand.scaledUnits} + margin ${safetyMargin.scaledUnits} + " +
                    "rounding ${roundedUpBy.scaledUnits}."
            }
        } else {
            require(roundedUpBy == Quantity.ZERO) { "Nothing is bought, so nothing was rounded up." }
        }
    }

    /** The ledger read below zero, so the engine counted it as zero. A stock count would fix the ledger. */
    val stockWasNegative: Boolean get() = currentStock.scaledUnits < 0

    /** Nothing to buy: stock already covers the lead time and the margin. */
    val enoughStock: Boolean get() = quantityToBuy == Quantity.ZERO
}

/**
 * How much to buy.
 *
 * [expectedDemand] is what the product is expected to sell over
 * [ReorderSettings.leadTimeDays] (a forecast total). It is rounded to one decimal
 * place first, because a forecast is not good to three — and because every
 * number shown afterwards is then exact, so the sum a shopkeeper checks by hand
 * comes out right.
 *
 * A negative ledger stock is counted as zero. Stock goes negative when goods
 * were sold before they were recorded (D031); the true shelf cannot be below
 * zero, and treating the phantom shortfall as real would tell the shopkeeper to
 * buy extra to fill a hole that is only in the records.
 */
fun recommendReorder(
    currentStock: Quantity,
    expectedDemand: Quantity,
    unit: String,
    settings: ReorderSettings = ReorderSettings(),
): ReorderRecommendation {
    require(expectedDemand.scaledUnits >= 0) { "Expected demand is never negative." }

    val counted = if (currentStock.scaledUnits < 0) Quantity.ZERO else currentStock
    val expected = roundToTenth(expectedDemand)
    val margin = roundToTenth(percentOf(expected, settings.safetyPercent))
    val needed = expected + margin - counted

    if (needed.scaledUnits <= 0) {
        return ReorderRecommendation(
            currentStock = currentStock,
            countedStock = counted,
            expectedDemand = expected,
            safetyMargin = margin,
            roundedUpBy = Quantity.ZERO,
            quantityToBuy = Quantity.ZERO,
            leadTimeDays = settings.leadTimeDays,
            safetyPercent = settings.safetyPercent,
        )
    }

    val buy = roundUpToMultiple(needed, purchaseStep(unit))
    return ReorderRecommendation(
        currentStock = currentStock,
        countedStock = counted,
        expectedDemand = expected,
        safetyMargin = margin,
        roundedUpBy = buy - needed,
        quantityToBuy = buy,
        leadTimeDays = settings.leadTimeDays,
        safetyPercent = settings.safetyPercent,
    )
}

/**
 * The smallest amount worth buying, by unit: whole pieces, packets, bottles,
 * dozens and grams; half a kilo or half a litre for the two units sold loose by
 * weight or volume. A suggestion to buy 2.3 kg of sugar is not something a
 * shopkeeper can act on.
 *
 * A choice, not a finding — a shop that buys sugar by the 25 kg sack will want
 * a bigger step, and that belongs with a per-product setting later.
 */
fun purchaseStep(unit: String): Quantity =
    when (unit) {
        ProductUnits.KG, ProductUnits.LITRE -> Quantity(HALF_UNIT)
        else -> Quantity(WHOLE_UNIT)
    }

/** [percent] percent of [quantity], by whole-number arithmetic, rounded half up. */
internal fun percentOf(
    quantity: Quantity,
    percent: Int,
): Quantity = Quantity((quantity.scaledUnits * percent + 50) / 100)

/** To one decimal place, half up. Only meaningful for amounts that are not negative. */
internal fun roundToTenth(quantity: Quantity): Quantity = Quantity((quantity.scaledUnits + 50) / 100 * 100)

private fun roundUpToMultiple(
    quantity: Quantity,
    step: Quantity,
): Quantity = Quantity((quantity.scaledUnits + step.scaledUnits - 1) / step.scaledUnits * step.scaledUnits)

private const val WHOLE_UNIT = 1000L
private const val HALF_UNIT = 500L
