/**
 * Baki, as a ledger (Step 49). The Kotlin twin is `android/.../domain/Baki.kt`,
 * and the two are kept honest by `fixtures/m3_baki.tsv`, which both suites read.
 *
 * Nothing here touches the database or the network. The one rule everything
 * follows from: what a customer owes is never a number anyone edits. It is the
 * sum of their entries (D001, PRD section 10). Every entry says what happened,
 * and once written it is never changed — a mistake is put right by writing an
 * opposite entry, not by rewriting the first one (CLAUDE.md, "never change or
 * delete confirmed transaction history").
 */
import type { BakiEntry } from './bakiEntry.js'
import { generateId, type EntityId } from './id.js'
import { money, ZERO_MONEY, sumMoney, type Money } from './money.js'
import { transactionTimeAtCreation } from './time.js'

/**
 * yyyy-MM-dd, and a day that exists. Dates are compared as text, which only
 * works if they all have this shape. `Date.parse` alone is not enough: it
 * quietly turns 2026-02-30 into March 2, where Kotlin's `LocalDate` refuses it,
 * so the date is round-tripped and must come back unchanged.
 */
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/

function requireDate(value: string, what: string): void {
  const parsed = DATE_ONLY.test(value) ? new Date(`${value}T00:00:00.000Z`) : null
  if (
    parsed === null ||
    Number.isNaN(parsed.getTime()) ||
    parsed.toISOString().slice(0, 10) !== value
  ) {
    throw new RangeError(`${what} must be a yyyy-MM-dd date, got "${value}".`)
  }
}

/**
 * Baki added by hand: the customer owes more, with no sale behind it (the Add
 * Baki screen, Step 54). Credit given as part of a sale is not made here —
 * `completeCreditSale` writes that entry, so the sale, its stock and its baki
 * are always produced together (D021).
 *
 * `amount` is how much more is owed, as a positive amount. Baki that shrinks is
 * a `receivePayment`, and saying so explicitly keeps the ledger readable months
 * later.
 */
export function addCredit(
  customerId: EntityId,
  amount: Money,
  occurredAt: Date,
  dueDate: string | null = null,
  id: EntityId = generateId(),
): BakiEntry {
  if (amount <= 0) {
    throw new RangeError(
      `Baki added must be a positive amount, got ${amount} poisha. ` +
        'Use receivePayment() to take away from what is owed.',
    )
  }
  if (dueDate !== null) requireDate(dueDate, 'A due date')

  return {
    id,
    customerId,
    amountDelta: amount,
    type: 'credit',
    reference: null,
    dueDate,
    time: transactionTimeAtCreation(occurredAt),
  }
}

/**
 * The customer paying something back.
 *
 * `amount` is how much they paid, as a positive amount; the entry stores it
 * negative, because it takes away from what is owed.
 *
 * A payment larger than what is owed is recorded, not refused. The money is in
 * the shopkeeper's hand whether or not the ledger agrees, so refusing would
 * only make the ledger wrong about the real world — the same reasoning as a
 * sale that is never blocked by stock (D031). The balance simply goes negative,
 * meaning the shop holds the customer's money in advance (D041).
 */
export function receivePayment(
  customerId: EntityId,
  amount: Money,
  occurredAt: Date,
  id: EntityId = generateId(),
): BakiEntry {
  if (amount <= 0) {
    throw new RangeError(
      `A payment must be a positive amount, got ${amount} poisha. ` +
        'Enter how much was paid; the entry stores it as a reduction.',
    )
  }

  return {
    id,
    customerId,
    amountDelta: money(-amount),
    type: 'payment',
    reference: null,
    dueDate: null,
    time: transactionTimeAtCreation(occurredAt),
  }
}

/**
 * Undoes a `credit` or `payment` entered by mistake, by writing its opposite —
 * never by deleting it (PRD section 10, CLAUDE.md). The new entry references
 * the original, so "has this already been undone?" is answered from stored rows
 * (Step 57), not from a flag on history.
 *
 * Two things cannot be reversed here, on purpose:
 * - A credit sale's entry. Its baki, stock and sale are undone together by
 *   `reverseSale`. Reversing only the baki would leave the customer clear while
 *   the goods stay gone — the partial state D021 forbids.
 * - A reversal. Undoing an undo would leave the history unreadable. Record a new
 *   entry instead.
 */
export function reverseEntry(
  original: BakiEntry,
  occurredAt: Date,
  id: EntityId = generateId(),
): BakiEntry {
  switch (original.type) {
    case 'credit':
    case 'payment':
      break
    case 'credit_sale':
      throw new RangeError(
        "A credit sale's baki is undone together with its sale and stock, with reverseSale() (D021).",
      )
    case 'reversal':
    case 'entry_reversal':
      throw new RangeError('A reversal cannot be reversed. Record a new entry instead.')
  }

  return {
    id,
    customerId: original.customerId,
    amountDelta: money(-original.amountDelta),
    type: 'entry_reversal',
    reference: original.id,
    dueDate: null,
    time: transactionTimeAtCreation(occurredAt),
  }
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
export function calculateBalance(entries: readonly BakiEntry[], customerId: EntityId): Money {
  return sumMoney(entries.filter((e) => e.customerId === customerId).map((e) => e.amountDelta))
}

/** Plain code-unit order, which is what Kotlin's String comparison does — so every phone and the server sort the same. */
function compareText(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0
}

/**
 * How much of what a customer owes is past its due date as of `today`
 * (yyyy-MM-dd).
 *
 * The balance is one number, but a due date belongs to one credit, so overdue
 * needs more than the balance. The rule, in the order it applies:
 *
 * 1. A credit that was undone is not owed, so it cannot be overdue. Each
 *    reversal cancels the entry it references, and both drop out.
 * 2. Payments settle the oldest debt first. Everything paid is spread over the
 *    remaining credits from the earliest to the latest, by when each happened
 *    (ties broken by id, so every phone agrees).
 * 3. What is left unpaid on a credit is overdue when that credit has a due date
 *    and the date is before `today`. Due today is not yet overdue.
 *
 * Credits with no due date never become overdue, and a customer who has paid
 * everything, or more, has nothing overdue. `today` is passed in rather than
 * read from the clock, because "today" is the shop's day (D019) and because a
 * rule that reads the clock cannot be tested.
 */
export function overdueAmount(
  entries: readonly BakiEntry[],
  customerId: EntityId,
  today: string,
): Money {
  requireDate(today, 'Today')

  const mine = entries.filter((e) => e.customerId === customerId)
  const referencesOf = (type: BakiEntry['type']): Set<string> =>
    new Set(
      mine
        .filter((e) => e.type === type)
        .map((e) => e.reference)
        .filter((reference): reference is string => reference !== null),
    )
  const undoneSales = referencesOf('reversal')
  const undoneEntries = referencesOf('entry_reversal')

  const standing = mine.filter((e) => {
    switch (e.type) {
      case 'reversal':
      case 'entry_reversal':
        return false
      case 'credit_sale':
        return e.reference === null || !undoneSales.has(e.reference)
      case 'credit':
      case 'payment':
        return !undoneEntries.has(e.id)
    }
  })

  let paid = standing.filter((e) => e.type === 'payment').reduce((sum, e) => sum - e.amountDelta, 0)
  let overdue = 0

  const credits = standing
    .filter((e) => e.type === 'credit_sale' || e.type === 'credit')
    .sort((a, b) => compareText(a.time.occurredAt, b.time.occurredAt) || compareText(a.id, b.id))

  for (const credit of credits) {
    const owed = credit.amountDelta
    const settled = Math.min(paid, owed)
    paid -= settled
    if (owed > settled && credit.dueDate !== null && credit.dueDate < today) {
      overdue += owed - settled
    }
  }
  return overdue === 0 ? ZERO_MONEY : money(overdue)
}

/** Whether anything this customer owes is past its due date — see `overdueAmount` for the rule. */
export function isOverdue(
  entries: readonly BakiEntry[],
  customerId: EntityId,
  today: string,
): boolean {
  return overdueAmount(entries, customerId, today) > 0
}
