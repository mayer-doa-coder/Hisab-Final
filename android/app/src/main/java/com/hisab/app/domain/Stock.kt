package com.hisab.app.domain

import java.time.Instant

// Stock, as a ledger (Step 35).
//
// Nothing here touches the screen, the database or the network — these are
// plain functions over plain values, which is what makes every rule in this
// file testable without a phone.
//
// The one rule everything else follows from: a product's stock is never a
// number anyone edits. It is the sum of its movements (D002, D020, PRD
// section 9). Every movement says what happened and why, and once written it
// is never changed — a mistake is fixed by writing an opposite movement, not
// by rewriting the first one (CLAUDE.md, "never change or delete confirmed
// transaction history").

/**
 * The five kinds of movement the PRD requires (section 9). Stored as the
 * name, so the code is language-neutral and the label shown to a shopkeeper
 * is translated separately (D011).
 */
enum class StockMovementType {
    /** Goods arriving from a supplier. Always increases stock. */
    RESTOCK,

    /** Goods leaving in a sale. Always decreases stock. */
    SALE,

    /** Goods coming back — a customer return, or a sale that was undone. Always increases stock. */
    RETURN,

    /** Goods lost, broken or expired. Always decreases stock. */
    DAMAGE,

    /** The difference between a real shelf count and what the ledger said. Either direction. */
    CORRECTION,
}

/**
 * One line of the stock ledger. Fields follow `docs/DATA_MODEL.md`.
 *
 * It carries no `revision`: a movement is never edited in place, so there is
 * nothing for two devices to disagree about (D017).
 */
data class StockMovement(
    val id: EntityId,
    val productId: EntityId,
    val type: StockMovementType,
    /** Signed: negative takes stock away, positive puts it back. */
    val quantityDelta: Quantity,
    /** What caused this — a sale id for a sale, a supplier note for a restock, null if nothing. */
    val sourceReference: String?,
    /** When it happened on the phone, kept apart from when the server saw it (D019). */
    val time: TransactionTime,
)

/**
 * Goods arriving from a supplier.
 *
 * [quantity] is how much arrived, as a positive amount — a restock that takes
 * stock away is not a restock, it is a damage or a correction, and saying so
 * explicitly is what keeps the ledger readable months later.
 */
fun restock(
    productId: EntityId,
    quantity: Quantity,
    occurredAt: Instant,
    sourceReference: String? = null,
    id: EntityId = generateId(),
): StockMovement {
    requirePositive(quantity, "restock")
    return StockMovement(
        id = id,
        productId = productId,
        type = StockMovementType.RESTOCK,
        quantityDelta = quantity,
        sourceReference = sourceReference,
        time = TransactionTime.atCreation(occurredAt),
    )
}

/**
 * Goods leaving in a sale. [quantity] is how much was sold, positive; the
 * movement it produces is negative.
 *
 * This does not check whether there is enough stock, on purpose. The
 * shopkeeper is standing at the counter handing over goods that are
 * physically there; refusing to record it would only make the ledger wrong
 * about the real world. Stock that was never restocked simply goes negative,
 * which is a visible signal that something is missing from the ledger — see
 * [stockShortfall], which a screen uses to warn before confirming (D031).
 */
fun sell(
    productId: EntityId,
    quantity: Quantity,
    saleId: EntityId,
    occurredAt: Instant,
    id: EntityId = generateId(),
): StockMovement {
    requirePositive(quantity, "sale")
    return StockMovement(
        id = id,
        productId = productId,
        type = StockMovementType.SALE,
        quantityDelta = Quantity.ZERO - quantity,
        sourceReference = saleId.value,
        time = TransactionTime.atCreation(occurredAt),
    )
}

/**
 * Goods coming back into stock: a customer returning an item, or a sale that
 * was undone (see `reverseSale`). [sourceReference] says which.
 */
fun returnStock(
    productId: EntityId,
    quantity: Quantity,
    occurredAt: Instant,
    sourceReference: String? = null,
    id: EntityId = generateId(),
): StockMovement {
    requirePositive(quantity, "return")
    return StockMovement(
        id = id,
        productId = productId,
        type = StockMovementType.RETURN,
        quantityDelta = quantity,
        sourceReference = sourceReference,
        time = TransactionTime.atCreation(occurredAt),
    )
}

/** Goods lost, broken or expired. [quantity] is how much was lost, positive; the movement is negative. */
fun damage(
    productId: EntityId,
    quantity: Quantity,
    occurredAt: Instant,
    sourceReference: String? = null,
    id: EntityId = generateId(),
): StockMovement {
    requirePositive(quantity, "damage")
    return StockMovement(
        id = id,
        productId = productId,
        type = StockMovementType.DAMAGE,
        quantityDelta = Quantity.ZERO - quantity,
        sourceReference = sourceReference,
        time = TransactionTime.atCreation(occurredAt),
    )
}

/**
 * A shelf count.
 *
 * The shopkeeper does not type a difference — they say what is actually on
 * the shelf ([countedQuantity]), and this works out the difference from what
 * the ledger currently says ([recordedQuantity], which comes from
 * [calculateCurrentStock]). That way the number a person enters is one they
 * can see, and the ledger still records why stock changed.
 *
 * A count that agrees with the ledger still writes a movement, of zero: it is
 * a record that the shelf was checked on that day, which is worth keeping.
 */
fun correctStock(
    productId: EntityId,
    countedQuantity: Quantity,
    recordedQuantity: Quantity,
    occurredAt: Instant,
    sourceReference: String? = null,
    id: EntityId = generateId(),
): StockMovement {
    require(countedQuantity.scaledUnits >= 0) {
        "A shelf count cannot be negative, got ${countedQuantity.scaledUnits}."
    }
    return StockMovement(
        id = id,
        productId = productId,
        type = StockMovementType.CORRECTION,
        quantityDelta = countedQuantity - recordedQuantity,
        sourceReference = sourceReference,
        time = TransactionTime.atCreation(occurredAt),
    )
}

/**
 * What is on the shelf right now, according to the ledger: the sum of every
 * movement for that product.
 *
 * A product with no movements has no stock, not "unknown" — that is what
 * makes a brand-new product start at zero without anything having to write a
 * starting row.
 */
fun calculateCurrentStock(movements: List<StockMovement>): Quantity = movements.map { it.quantityDelta }.sum()

/** The same sum, for one product out of a mixed list of movements. */
fun calculateCurrentStock(
    movements: List<StockMovement>,
    productId: EntityId,
): Quantity = calculateCurrentStock(movements.filter { it.productId == productId })

/**
 * How much would be missing if [wanted] were sold out of [available], and
 * [Quantity.ZERO] when there is enough.
 *
 * A screen uses this to warn before a sale is confirmed. It never blocks the
 * sale (D031) — see [sell].
 */
fun stockShortfall(
    available: Quantity,
    wanted: Quantity,
): Quantity {
    val missing = wanted - available
    return if (missing.scaledUnits > 0) missing else Quantity.ZERO
}

fun hasEnoughStock(
    available: Quantity,
    wanted: Quantity,
): Boolean = stockShortfall(available, wanted) == Quantity.ZERO

private fun requirePositive(
    quantity: Quantity,
    what: String,
) {
    require(quantity.scaledUnits > 0) {
        "A $what must be a positive quantity, got ${quantity.scaledUnits}. " +
            "Use correctStock() to move stock the other way."
    }
}
