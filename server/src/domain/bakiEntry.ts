/**
 * One line of a customer's credit ledger. Fields follow `docs/DATA_MODEL.md`.
 * The Kotlin twin is `android/.../domain/BakiEntry.kt`.
 *
 * A customer's baki is never a number anyone edits — it is the sum of these
 * entries (D001, PRD section 10). Like a stock movement, an entry is never
 * changed once written; a mistake is undone by writing an opposite entry.
 *
 * Only the shape lives here for now, plus the entries a credit sale and its
 * reversal produce (`sale.ts`). The baki functions themselves — `addCredit`,
 * `receivePayment`, `calculateBalance`, `isOverdue` — and the table are M3
 * (docs/PHASE_GUIDE.md Steps 48–51). A credit sale still has to create its
 * entry correctly here, though: reversing a credit sale must undo the sale,
 * the stock and the baki together, and it cannot do that if the entry only
 * appears a milestone later (D021, Step 40).
 */
import type { EntityId } from './id.js'
import type { Money } from './money.js'
import type { TransactionTime } from './time.js'

export const BAKI_ENTRY_TYPES = [
  /** The customer took goods on credit. Increases what is owed. */
  'credit_sale',
  /** A credit sale was undone. Decreases what is owed by exactly what it added. */
  'reversal',
] as const

export type BakiEntryType = (typeof BAKI_ENTRY_TYPES)[number]

export interface BakiEntry {
  readonly id: EntityId
  readonly customerId: EntityId
  /** Signed: positive is more owed, negative is less owed. */
  readonly amountDelta: Money
  readonly type: BakiEntryType
  /** What caused this — the sale id, for both a credit sale and its reversal. */
  readonly reference: string | null
  /** Optional ISO-8601 date: when the shopkeeper expects to be paid back (PRD section 10). */
  readonly dueDate: string | null
  /** When it happened on the device, kept apart from when the server saw it (D019). */
  readonly time: TransactionTime
}
