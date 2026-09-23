/**
 * A customer, as the rest of the system talks about it. Customer is a mutable
 * entity like Product, so it carries a revision, updatedAt and deletedAt
 * (D017) — but in M2 it is only ever created, by a credit sale. Editing and
 * the customer screens are M3.
 *
 * What a customer owes is not here, and never will be: it is always the sum
 * of their baki entries (D001).
 */
export interface Customer {
  readonly id: string
  readonly shopId: string
  readonly name: string
  readonly phone: string | null
  readonly revision: number
  /** ISO-8601, so the shape is the same on the wire as in code. */
  readonly updatedAt: string
  readonly deletedAt: string | null
}

/** Language-neutral status code — never localized (D011). */
export const CUSTOMER_NOT_FOUND = 'CUSTOMER_NOT_FOUND'
