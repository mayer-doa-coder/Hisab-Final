/**
 * One line of a customer's credit ledger. Fields follow `docs/DATA_MODEL.md`.
 * The Kotlin twin is `android/.../domain/BakiEntry.kt`.
 *
 * A customer's baki is never a number anyone edits — it is the sum of these
 * entries (D001, PRD section 10). Like a stock movement, an entry is never
 * changed once written; a mistake is undone by writing an opposite entry.
 *
 * This file is the shape. The rules that build and read entries are in
 * `baki.ts` (`addCredit`, `receivePayment`, `reverseEntry`, `calculateBalance`,
 * `isOverdue`), except the two entries a credit sale and its reversal write,
 * which come from `sale.ts` so the sale, its stock and its baki are always
 * made together (D021).
 *
 * `reference` is never free text. It names the thing an entry is about — the
 * sale for a credit sale and its reversal, the original entry for an
 * `entry_reversal` — or is null. That keeps "which entries belong to this
 * sale" a question about ids, not about what someone typed (D041).
 */
import type { EntityId } from './id.js'
import type { Money } from './money.js'
import type { TransactionTime } from './time.js'

export const BAKI_ENTRY_TYPES = [
  /** The customer took goods on credit. Increases what is owed. Written only by a credit sale. */
  'credit_sale',
  /** A credit sale was undone. Decreases what is owed by exactly what it added. Written only by `reverseSale`. */
  'reversal',
  /** Baki added by hand, with no sale behind it. Increases what is owed. */
  'credit',
  /** The customer paid something back. Decreases what is owed. */
  'payment',
  /** A `credit` or `payment` entered by mistake was undone. Exactly cancels the entry it references. */
  'entry_reversal',
] as const

export type BakiEntryType = (typeof BAKI_ENTRY_TYPES)[number]

export interface BakiEntry {
  readonly id: EntityId
  readonly customerId: EntityId
  /** Signed: positive is more owed, negative is less owed. */
  readonly amountDelta: Money
  readonly type: BakiEntryType
  /** The sale id (credit sale, sale reversal), the original entry id (entry reversal), or null. Never free text. */
  readonly reference: string | null
  /** Optional ISO-8601 date: when the shopkeeper expects to be paid back (PRD section 10). */
  readonly dueDate: string | null
  /** When it happened on the device, kept apart from when the server saw it (D019). */
  readonly time: TransactionTime
}
