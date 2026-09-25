package com.hisab.app.domain

import java.time.LocalDate

/**
 * One line of a customer's credit ledger. Fields follow `docs/DATA_MODEL.md`.
 *
 * A customer's baki is never a number anyone edits — it is the sum of these
 * entries (D001, PRD section 10). Like a stock movement, an entry is never
 * changed once written; a mistake is undone by writing an opposite entry.
 *
 * This file is the shape. The rules that build and read entries are in
 * `Baki.kt` (`addCredit`, `receivePayment`, `reverseEntry`, `calculateBalance`,
 * `isOverdue`), except the two entries a credit sale and its reversal write,
 * which come from `Sale.kt` so the sale, its stock and its baki are always made
 * together (D021).
 *
 * `reference` is never free text. It names the thing an entry is about — the
 * sale for a credit sale and its reversal, the original entry for an
 * [ENTRY_REVERSAL] — or is null. That keeps "which entries belong to this
 * sale" a question about ids, not about what someone typed (D041).
 */
enum class BakiEntryType {
    /** The customer took goods on credit. Increases what is owed. Written only by a credit sale. */
    CREDIT_SALE,

    /** A credit sale was undone. Decreases what is owed by exactly what it added. Written only by [reverseSale]. */
    REVERSAL,

    /** Baki added by hand, with no sale behind it. Increases what is owed. */
    CREDIT,

    /** The customer paid something back. Decreases what is owed. */
    PAYMENT,

    /** A [CREDIT] or [PAYMENT] entered by mistake was undone. Exactly cancels the entry it references. */
    ENTRY_REVERSAL,
}

data class BakiEntry(
    val id: EntityId,
    val customerId: EntityId,
    /** Signed: positive is more owed, negative is less owed. */
    val amountDelta: Money,
    val type: BakiEntryType,
    /** The sale id (credit sale, sale reversal), the original entry id (entry reversal), or null. Never free text. */
    val reference: String?,
    /** Optional: when the shopkeeper expects to be paid back (PRD section 10). */
    val dueDate: LocalDate?,
    /** When it happened on the phone, kept apart from when the server saw it (D019). */
    val time: TransactionTime,
)
