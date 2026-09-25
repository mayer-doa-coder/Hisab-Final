package com.hisab.app.domain

import java.time.Instant
import java.time.LocalDate

// Baki, as a ledger (Step 48).
//
// Nothing here touches the screen, the database or the network. The one rule
// everything follows from: what a customer owes is never a number anyone
// edits. It is the sum of their entries (D001, PRD section 10). Every entry
// says what happened, and once written it is never changed — a mistake is put
// right by writing an opposite entry, not by rewriting the first one
// (CLAUDE.md, "never change or delete confirmed transaction history").

/**
 * Baki added by hand: the customer owes more, with no sale behind it (the Add
 * Baki screen, Step 54). Credit given as part of a sale is not made here —
 * [completeCreditSale] writes that entry, so the sale, its stock and its baki
 * are always produced together (D021).
 *
 * [amount] is how much more is owed, as a positive amount. Baki that shrinks is
 * a [receivePayment], and saying so explicitly keeps the ledger readable months
 * later.
 */
fun addCredit(
    customerId: EntityId,
    amount: Money,
    occurredAt: Instant,
    dueDate: LocalDate? = null,
    id: EntityId = generateId(),
): BakiEntry {
    require(amount.minorUnits > 0) {
        "Baki added must be a positive amount, got ${amount.minorUnits} poisha. " +
            "Use receivePayment() to take away from what is owed."
    }
    return BakiEntry(
        id = id,
        customerId = customerId,
        amountDelta = amount,
        type = BakiEntryType.CREDIT,
        reference = null,
        dueDate = dueDate,
        time = TransactionTime.atCreation(occurredAt),
    )
}

/**
 * The customer paying something back.
 *
 * [amount] is how much they paid, as a positive amount; the entry stores it
 * negative, because it takes away from what is owed.
 *
 * A payment larger than what is owed is recorded, not refused. The money is in
 * the shopkeeper's hand whether or not the ledger agrees, so refusing would
 * only make the ledger wrong about the real world — the same reasoning as a
 * sale that is never blocked by stock (D031). The balance simply goes negative,
 * meaning the shop holds the customer's money in advance (D041).
 */
fun receivePayment(
    customerId: EntityId,
    amount: Money,
    occurredAt: Instant,
    id: EntityId = generateId(),
): BakiEntry {
    require(amount.minorUnits > 0) {
        "A payment must be a positive amount, got ${amount.minorUnits} poisha. " +
            "Enter how much was paid; the entry stores it as a reduction."
    }
    return BakiEntry(
        id = id,
        customerId = customerId,
        amountDelta = Money.ZERO - amount,
        type = BakiEntryType.PAYMENT,
        reference = null,
        dueDate = null,
        time = TransactionTime.atCreation(occurredAt),
    )
}

/**
 * Undoes a [BakiEntryType.CREDIT] or [BakiEntryType.PAYMENT] entered by
 * mistake, by writing its opposite — never by deleting it (PRD section 10,
 * CLAUDE.md). The new entry references the original, so "has this already been
 * undone?" is answered from stored rows (Step 57), not from a flag on history.
 *
 * Two things cannot be reversed here, on purpose:
 * - **A credit sale's entry.** Its baki, stock and sale are undone together by
 *   [reverseSale]. Reversing only the baki would leave the customer clear while
 *   the goods stay gone — the partial state D021 forbids.
 * - **A reversal.** Undoing an undo would leave the history unreadable. Record a
 *   new entry instead.
 */
fun reverseEntry(
    original: BakiEntry,
    occurredAt: Instant,
    id: EntityId = generateId(),
): BakiEntry {
    when (original.type) {
        BakiEntryType.CREDIT, BakiEntryType.PAYMENT -> {
            Unit
        }

        BakiEntryType.CREDIT_SALE -> {
            throw IllegalArgumentException(
                "A credit sale's baki is undone together with its sale and stock, with reverseSale() (D021).",
            )
        }

        BakiEntryType.REVERSAL, BakiEntryType.ENTRY_REVERSAL -> {
            throw IllegalArgumentException("A reversal cannot be reversed. Record a new entry instead.")
        }
    }

    return BakiEntry(
        id = id,
        customerId = original.customerId,
        amountDelta = Money.ZERO - original.amountDelta,
        type = BakiEntryType.ENTRY_REVERSAL,
        reference = original.id.value,
        dueDate = null,
        time = TransactionTime.atCreation(occurredAt),
    )
}

/**
 * What one customer owes right now: the sum of their entries.
 *
 * A customer with no entries owes nothing, not "unknown" — that is what makes a
 * brand-new customer start at zero without anything writing a starting row.
 * Negative means the customer has paid more than they owed (D041).
 *
 * Only that customer's entries count, so a mixed list — every customer in the
 * shop — cannot leak one person's baki into another's balance.
 */
fun calculateBalance(
    entries: List<BakiEntry>,
    customerId: EntityId,
): Money = entries.filter { it.customerId == customerId }.map { it.amountDelta }.sum()

/**
 * How much of what a customer owes is past its due date as of [today].
 *
 * The balance is one number, but a due date belongs to one credit, so overdue
 * needs more than the balance. The rule, in the order it applies:
 *
 * 1. **A credit that was undone is not owed**, so it cannot be overdue. Each
 *    reversal cancels the entry it references, and both drop out.
 * 2. **Payments settle the oldest debt first.** Everything paid is spread over
 *    the remaining credits from the earliest to the latest, by when each
 *    happened (ties broken by id, so every phone agrees).
 * 3. **What is left unpaid on a credit is overdue** when that credit has a due
 *    date and the date is before [today]. Due *today* is not yet overdue.
 *
 * Credits with no due date never become overdue, and a customer who has paid
 * everything, or more, has nothing overdue. [today] is passed in rather than
 * read from the clock, because "today" is the shop's day (D019) and because a
 * rule that reads the clock cannot be tested.
 */
fun overdueAmount(
    entries: List<BakiEntry>,
    customerId: EntityId,
    today: LocalDate,
): Money {
    val mine = entries.filter { it.customerId == customerId }
    val undoneSales = mine.filter { it.type == BakiEntryType.REVERSAL }.mapNotNull { it.reference }.toSet()
    val undoneEntries = mine.filter { it.type == BakiEntryType.ENTRY_REVERSAL }.mapNotNull { it.reference }.toSet()

    val standing =
        mine.filter {
            when (it.type) {
                BakiEntryType.REVERSAL, BakiEntryType.ENTRY_REVERSAL -> false
                BakiEntryType.CREDIT_SALE -> it.reference !in undoneSales
                BakiEntryType.CREDIT, BakiEntryType.PAYMENT -> it.id.value !in undoneEntries
            }
        }

    var paid = standing.filter { it.type == BakiEntryType.PAYMENT }.sumOf { -it.amountDelta.minorUnits }
    var overdue = 0L

    val credits =
        standing
            .filter { it.type == BakiEntryType.CREDIT_SALE || it.type == BakiEntryType.CREDIT }
            .sortedWith(compareBy({ it.time.occurredAt }, { it.id.value }))

    for (credit in credits) {
        val owed = credit.amountDelta.minorUnits
        val settled = minOf(paid, owed)
        paid -= settled
        val due = credit.dueDate
        if (owed > settled && due != null && due.isBefore(today)) overdue += owed - settled
    }
    return Money(overdue)
}

/** Whether anything this customer owes is past its due date — see [overdueAmount] for the rule. */
fun isOverdue(
    entries: List<BakiEntry>,
    customerId: EntityId,
    today: LocalDate,
): Boolean = overdueAmount(entries, customerId, today).minorUnits > 0
