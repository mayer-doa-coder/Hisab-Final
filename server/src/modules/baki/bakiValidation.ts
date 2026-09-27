import type { BakiEntry } from '../../domain/bakiEntry.js'
import { money } from '../../domain/money.js'
import { ALREADY_REVERSED } from '../sales/saleValidation.js'

/** Language-neutral codes a hand-written baki entry can be refused with (D011). */
export const INVALID_PAYLOAD = 'INVALID_PAYLOAD'
export const BAKI_ENTRY_NOT_FOUND = 'BAKI_ENTRY_NOT_FOUND'
export const NOT_REVERSIBLE = 'NOT_REVERSIBLE'
export { ALREADY_REVERSED }

/**
 * Checks that a hand-written baki entry — a credit, a payment, or an undo of
 * either — is one the rules could have produced. The server never takes a
 * phone's, or a caller's, arithmetic on trust. Returns null when it is sound,
 * or the code to refuse it with.
 *
 * `credit_sale` and `reversal` are refused here even though they are entries
 * of a real type: those two only ever travel embedded in a Sale event,
 * written together with the sale and its stock (D021, D038) — a standalone
 * one is not something the rules could have produced.
 *
 * For an `entry_reversal`, `original` is the entry it names, already loaded
 * from this shop, or null if no such entry exists. Whether `original` has
 * *already* been undone is a question about other stored rows, so it is the
 * caller's to ask (D043) — this only checks that the pair, if both existed
 * alone, would be a sound undo.
 */
export function checkPushedBakiEntry(entry: BakiEntry, original: BakiEntry | null): string | null {
  switch (entry.type) {
    case 'credit':
      if (entry.reference !== null) return INVALID_PAYLOAD
      return entry.amountDelta > 0 ? null : INVALID_PAYLOAD

    case 'payment':
      if (entry.reference !== null) return INVALID_PAYLOAD
      return entry.amountDelta < 0 ? null : INVALID_PAYLOAD

    case 'entry_reversal': {
      if (entry.reference === null || entry.dueDate !== null) return INVALID_PAYLOAD
      if (original === null) return BAKI_ENTRY_NOT_FOUND
      if (original.type !== 'credit' && original.type !== 'payment') return NOT_REVERSIBLE
      if (original.customerId !== entry.customerId) return INVALID_PAYLOAD
      return entry.amountDelta === money(-original.amountDelta) ? null : INVALID_PAYLOAD
    }

    case 'credit_sale':
    case 'reversal':
      return INVALID_PAYLOAD
  }
}
