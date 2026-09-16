package com.hisab.app.domain

import java.time.LocalDate

/**
 * One line of a customer's credit ledger. Fields follow `docs/DATA_MODEL.md`.
 *
 * A customer's baki is never a number anyone edits — it is the sum of these
 * entries (D001, PRD section 10). Like a stock movement, an entry is never
 * changed once written; a mistake is undone by writing an opposite entry.
 *
 * Only the shape lives here for now, plus the entries a credit sale and its
 * reversal produce (`Sale.kt`). The baki functions themselves — `addCredit`,
 * `receivePayment`, `calculateBalance`, `isOverdue` — and the table are M3
 * (docs/PHASE_GUIDE.md Steps 48–51). A credit sale still has to create its
 * entry correctly here, though: reversing a credit sale must undo the sale,
 * the stock and the baki together, and it cannot do that if the entry only
 * appears a milestone later (D021, Step 40).
 */
enum class BakiEntryType {
    /** The customer took goods on credit. Increases what is owed. */
    CREDIT_SALE,

    /** A credit sale was undone. Decreases what is owed by exactly what it added. */
    REVERSAL,
}

data class BakiEntry(
    val id: EntityId,
    val customerId: EntityId,
    /** Signed: positive is more owed, negative is less owed. */
    val amountDelta: Money,
    val type: BakiEntryType,
    /** What caused this — the sale id, for both a credit sale and its reversal. */
    val reference: String?,
    /** Optional: when the shopkeeper expects to be paid back (PRD section 10). */
    val dueDate: LocalDate?,
    /** When it happened on the phone, kept apart from when the server saw it (D019). */
    val time: TransactionTime,
)
