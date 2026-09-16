package com.hisab.app.domain

import java.time.Instant
import java.time.LocalDate
import kotlin.math.absoluteValue

// Sales, as plain functions (Step 36).
//
// Nothing here touches the screen, the database or the network. A completed
// sale is a single value holding everything the sale caused — the sale, its
// lines, its stock movements, and (on credit) its baki entry — so a caller
// cannot accidentally save one part without the others. That is D021's
// atomicity made structural rather than remembered: the parts are produced
// together, and `HisabDatabase.withTransaction` saves them together.

/** How the customer paid. Stored as the name, so it is language-neutral (D011). */
enum class SalePayment {
    CASH,

    /** On baki — the customer owes it, and a BakiEntry records that. */
    CREDIT,
}

/**
 * One completed sale. Fields follow `docs/DATA_MODEL.md`, plus three the
 * table there did not name but PRD section 8 requires: which way it was paid,
 * which customer owes it, and — for a reversal — which sale it undoes.
 *
 * It carries no `revision`: a sale is never edited in place. A mistake is
 * undone by [reverseSale], which writes a second, opposite sale rather than
 * touching this one (CLAUDE.md, "never change or delete confirmed
 * transaction history").
 */
data class Sale(
    val id: EntityId,
    /** Never taken from a request body — the server derives it from the session (D015). */
    val shopId: String,
    val total: Money,
    val payment: SalePayment,
    /** Set for a credit sale, null for cash. */
    val customerId: EntityId?,
    /**
     * Null for a normal sale. On a reversal, the id of the sale being undone.
     *
     * This is what keeps a day's takings right without editing history: a
     * reversal is a sale with a negative total, so summing `total` over a day
     * already excludes what was reversed.
     */
    val reversesSaleId: EntityId?,
    /** When it happened on the phone, kept apart from when the server saw it (D019). */
    val time: TransactionTime,
)

/**
 * One line of a sale. Fields follow `docs/DATA_MODEL.md`: a line is
 * identified by its sale and its product together, so one product appears at
 * most once in a sale.
 *
 * [unitPrice] is copied onto the line rather than read from the product
 * later. A price the shopkeeper changes next month must never quietly change
 * what last month's receipt said.
 */
data class SaleItem(
    val saleId: EntityId,
    val productId: EntityId,
    val quantity: Quantity,
    val unitPrice: Money,
)

/** What the cart holds before the sale is confirmed and has an id. */
data class SaleLine(
    val productId: EntityId,
    val quantity: Quantity,
    val unitPrice: Money,
)

/**
 * Everything one sale caused, together.
 *
 * The checks in `init` are the point of this type: a credit sale always has a
 * customer and a baki entry, a cash sale never has either, and there is one
 * stock movement per line. A caller cannot hold a half-formed sale, so it
 * cannot save one (D021).
 */
data class SaleTransaction(
    val sale: Sale,
    val items: List<SaleItem>,
    val stockMovements: List<StockMovement>,
    val bakiEntry: BakiEntry?,
) {
    init {
        require(items.isNotEmpty()) { "A sale has at least one line." }
        require(stockMovements.size == items.size) {
            "Every sale line moves stock: ${items.size} lines but ${stockMovements.size} movements."
        }
        when (sale.payment) {
            SalePayment.CASH -> {
                require(sale.customerId == null) { "A cash sale has no customer." }
                require(bakiEntry == null) { "A cash sale creates no baki entry — nothing is owed." }
            }

            SalePayment.CREDIT -> {
                require(sale.customerId != null) { "A credit sale must say who owes it." }
                require(bakiEntry != null) { "A credit sale must create a baki entry (D021)." }
                require(bakiEntry.customerId == sale.customerId) {
                    "The baki entry must be owed by the customer the sale names."
                }
                require(bakiEntry.amountDelta == sale.total) {
                    "A credit sale owes exactly what it totalled: sale ${sale.total.minorUnits}, " +
                        "baki ${bakiEntry.amountDelta.minorUnits}."
                }
            }
        }
    }
}

/**
 * What one line costs.
 *
 * ```text
 * lineTotal = quantityScaled x unitPricePoisha / 1000
 * ```
 *
 * The division is the only place a sale can land between two poisha — 1.25 kg
 * at 90.50 taka is 113.125 taka. It is resolved by rounding half away from
 * zero, so a reversed line gives back exactly what the line charged: rounding
 * half upward instead would turn +113.13 into a -113.12 refund and leave one
 * poisha behind on every reversed half-poisha line.
 *
 * All of it is integer arithmetic. Money and quantity are never floats
 * (D019), so no rounding error can build up across a day's sales.
 */
fun calculateLineTotal(
    quantity: Quantity,
    unitPrice: Money,
): Money {
    require(unitPrice.minorUnits >= 0) {
        "A unit price cannot be negative, got ${unitPrice.minorUnits} poisha."
    }
    return Money(divideRoundingHalfAwayFromZero(quantity.scaledUnits * unitPrice.minorUnits, QUANTITY_SCALE))
}

/**
 * What the whole sale costs: each line rounded on its own, then added.
 *
 * Rounding per line, not once at the end, is what makes the printed lines add
 * up to the printed total — a shopkeeper who checks the arithmetic by hand
 * has to get the same answer.
 */
fun calculateSaleTotal(items: List<SaleItem>): Money = items.map { calculateLineTotal(it.quantity, it.unitPrice) }.sum()

/**
 * A cash sale: the sale, its lines, and one stock movement per line.
 *
 * Nothing is checked against current stock. The goods are being handed over;
 * refusing to record that would only make the ledger wrong about the real
 * world (D031). A screen warns first, using `stockShortfall`.
 */
fun completeCashSale(
    shopId: String,
    lines: List<SaleLine>,
    occurredAt: Instant,
    saleId: EntityId = generateId(),
): SaleTransaction {
    val items = toItems(saleId, lines)
    return SaleTransaction(
        sale =
            Sale(
                id = saleId,
                shopId = shopId,
                total = calculateSaleTotal(items),
                payment = SalePayment.CASH,
                customerId = null,
                reversesSaleId = null,
                time = TransactionTime.atCreation(occurredAt),
            ),
        items = items,
        stockMovements = items.map { sell(it.productId, it.quantity, saleId, occurredAt) },
        bakiEntry = null,
    )
}

/**
 * A credit sale: everything a cash sale produces, plus the baki entry saying
 * the customer owes the total.
 *
 * The amount owed is never typed separately — it is the sale total, so the
 * two can never disagree.
 */
fun completeCreditSale(
    shopId: String,
    customerId: EntityId,
    lines: List<SaleLine>,
    occurredAt: Instant,
    dueDate: LocalDate? = null,
    saleId: EntityId = generateId(),
): SaleTransaction {
    val items = toItems(saleId, lines)
    val total = calculateSaleTotal(items)
    return SaleTransaction(
        sale =
            Sale(
                id = saleId,
                shopId = shopId,
                total = total,
                payment = SalePayment.CREDIT,
                customerId = customerId,
                reversesSaleId = null,
                time = TransactionTime.atCreation(occurredAt),
            ),
        items = items,
        stockMovements = items.map { sell(it.productId, it.quantity, saleId, occurredAt) },
        bakiEntry =
            BakiEntry(
                id = generateId(),
                customerId = customerId,
                amountDelta = total,
                type = BakiEntryType.CREDIT_SALE,
                reference = saleId.value,
                dueDate = dueDate,
                time = TransactionTime.atCreation(occurredAt),
            ),
    )
}

/**
 * Undoes a sale by writing its opposite, never by deleting it (PRD section 8,
 * CLAUDE.md).
 *
 * What comes back is a second, complete sale: a negative total, negative
 * lines, stock going back in as RETURN movements referencing the original
 * sale, and — for a credit sale — a baki entry that subtracts exactly what
 * the sale added. The three move together because they arrive together, in
 * one value (D021). There is no arrangement of these functions that restores
 * stock while leaving the customer still owing.
 *
 * A reversal cannot itself be reversed: undoing an undo is a new sale, and
 * calling it a reversal would leave the history unreadable. Whether a sale
 * has *already* been reversed is a question about what is stored, not about
 * these values, so the repository answers it (Step 43).
 */
fun reverseSale(
    original: SaleTransaction,
    occurredAt: Instant,
    saleId: EntityId = generateId(),
): SaleTransaction {
    require(original.sale.reversesSaleId == null) {
        "A reversal cannot be reversed. Record a new sale instead."
    }

    val items =
        original.items.map {
            SaleItem(
                saleId = saleId,
                productId = it.productId,
                quantity = Quantity.ZERO - it.quantity,
                unitPrice = it.unitPrice,
            )
        }

    val reversedTotal = Money.ZERO - original.sale.total
    return SaleTransaction(
        sale =
            Sale(
                id = saleId,
                shopId = original.sale.shopId,
                total = reversedTotal,
                payment = original.sale.payment,
                customerId = original.sale.customerId,
                reversesSaleId = original.sale.id,
                time = TransactionTime.atCreation(occurredAt),
            ),
        items = items,
        stockMovements =
            original.items.map {
                returnStock(
                    productId = it.productId,
                    quantity = it.quantity,
                    occurredAt = occurredAt,
                    sourceReference = original.sale.id.value,
                )
            },
        bakiEntry =
            original.bakiEntry?.let { owed ->
                BakiEntry(
                    id = generateId(),
                    customerId = owed.customerId,
                    amountDelta = reversedTotal,
                    type = BakiEntryType.REVERSAL,
                    reference = original.sale.id.value,
                    dueDate = null,
                    time = TransactionTime.atCreation(occurredAt),
                )
            },
    )
}

private const val QUANTITY_SCALE = 1000L

private fun toItems(
    saleId: EntityId,
    lines: List<SaleLine>,
): List<SaleItem> {
    require(lines.isNotEmpty()) { "A sale needs at least one line." }
    require(lines.distinctBy { it.productId }.size == lines.size) {
        "A product appears at most once in a sale — add up the quantities into one line first."
    }
    lines.forEach {
        require(it.quantity.scaledUnits > 0) {
            "A sale line must have a positive quantity, got ${it.quantity.scaledUnits}."
        }
    }
    return lines.map { SaleItem(saleId, it.productId, it.quantity, it.unitPrice) }
}

/**
 * Integer division that rounds a half away from zero, so +0.5 becomes +1 and
 * -0.5 becomes -1. Kotlin's `/` truncates toward zero instead, which would
 * round a reversal the wrong way — see [calculateLineTotal].
 */
private fun divideRoundingHalfAwayFromZero(
    numerator: Long,
    denominator: Long,
): Long {
    val whole = numerator / denominator
    val remainder = numerator % denominator
    if (remainder.absoluteValue * 2 < denominator) return whole
    return if (numerator < 0) whole - 1 else whole + 1
}
