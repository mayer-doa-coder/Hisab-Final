package com.hisab.app.ui.customer

import com.hisab.app.domain.BakiEntry
import com.hisab.app.domain.BakiEntryType
import com.hisab.app.domain.Money
import com.hisab.app.domain.parseTaka
import com.hisab.app.domain.sum

// One customer's ledger as it is shown (Steps 53–55), and the arithmetic the
// two forms preview before anything is written.
//
// The running balance is the sum of the entries up to and including each one,
// so it is never a second number that could disagree with the balance on top
// of the screen: the last line's balance *is* that balance (D001).

/** One entry with what the customer owed straight after it. */
data class LedgerLine(
    val entry: BakiEntry,
    val balanceAfter: Money,
    /** An opposite entry for this one is already in the ledger (Step 57). */
    val undone: Boolean = false,
) {
    /**
     * Only a hand-written credit or payment that has not been undone already. A
     * credit sale's baki goes with its sale and stock (D021), and a reversal is
     * never itself reversed (D041).
     */
    val canUndo: Boolean
        get() = !undone && (entry.type == BakiEntryType.CREDIT || entry.type == BakiEntryType.PAYMENT)
}

/**
 * Entries newest first, each with the balance after it.
 *
 * The balance is built by adding the entries up in the order they happened
 * (ties broken by id, so every phone agrees), then the list is turned round so
 * the latest is at the top, where a shopkeeper looks first.
 */
fun buildLedger(entries: List<BakiEntry>): List<LedgerLine> {
    val inOrder = entries.sortedWith(compareBy({ it.time.occurredAt }, { it.id.value }))
    val undoneIds = entries.filter { it.type == BakiEntryType.ENTRY_REVERSAL }.mapNotNull { it.reference }.toSet()
    var running = Money.ZERO
    return inOrder
        .map { entry ->
            running += entry.amountDelta
            LedgerLine(entry, running, undone = entry.id.value in undoneIds)
        }.asReversed()
}

/** The balance shown on a customer's screen: the sum of the whole ledger. */
fun ledgerBalance(lines: List<LedgerLine>): Money = lines.map { it.entry.amountDelta }.sum()

/**
 * An amount typed into Add Baki or Receive Payment. Only a positive amount is
 * a valid one: zero records nothing, and a negative number would silently turn
 * a credit into a payment. Null means "say so on the field" — never guess.
 */
fun parseBakiAmount(text: String): Money? = parseTaka(text)?.takeIf { it.minorUnits > 0 }

/** What the customer would owe after this much more is added; null while the amount is not valid. */
fun balanceAfterCredit(
    current: Money,
    amount: Money?,
): Money? = amount?.let { current + it }

/** What the customer would owe after paying this much; null while the amount is not valid. */
fun balanceAfterPayment(
    current: Money,
    amount: Money?,
): Money? = amount?.let { current - it }

/**
 * What the customer would owe once this entry is undone: the balance with the
 * entry's own effect taken back out. Undoing a credit lowers it and undoing a
 * payment raises it, which is exactly what the opposite entry written on
 * confirm adds up to.
 */
fun balanceAfterUndo(
    current: Money,
    entry: BakiEntry,
): Money = current - entry.amountDelta

/**
 * Whether a payment is more than is owed. That includes any payment from
 * someone who owes nothing. It is recorded, never refused — the money is in the
 * shopkeeper's hand either way (D041) — but the screen says what will happen.
 */
fun isOverpayment(
    current: Money,
    amount: Money?,
): Boolean = amount != null && amount.minorUnits > maxOf(current.minorUnits, 0L)
