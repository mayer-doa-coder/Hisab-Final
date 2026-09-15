/**
 * A product, as the rest of the system talks about it. The database row is
 * mapped into this shape rather than assumed identical to it (Step 7), and
 * the phone's Room entity is mapped into the same shape on its side.
 *
 * Money is an integer number of poisha, never a float (CLAUDE.md). Stock is
 * not here on purpose: it is always summed from stock movements (D020).
 */
export interface Product {
  readonly id: string
  readonly shopId: string
  readonly name: string
  readonly aliases: string[]
  readonly unit: string
  readonly purchasePricePoisha: number | null
  readonly sellingPricePoisha: number
  readonly active: boolean
  readonly revision: number
  /** ISO-8601 timestamps, so the shape is the same on the wire as in code. */
  readonly updatedAt: string
  readonly deletedAt: string | null
}

/** What a caller may set. Everything else — id, shop, revision, timestamps — is decided here. */
export interface ProductInput {
  readonly name: string
  readonly aliases: string[]
  readonly unit: string
  readonly purchasePricePoisha: number | null
  readonly sellingPricePoisha: number
  readonly active: boolean
}

/** Language-neutral unit codes, the same list the app uses (D011). */
export const PRODUCT_UNITS = ['piece', 'kg', 'gram', 'litre', 'packet', 'bottle', 'dozen'] as const

export const DEFAULT_PRODUCT_UNIT = 'piece'

/** Language-neutral status code — never localized (D011). */
export const PRODUCT_NOT_FOUND = 'PRODUCT_NOT_FOUND'
