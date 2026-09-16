/**
 * Stock, as a ledger — the backend half of Step 35 (Step 37).
 *
 * Android is Kotlin and this is TypeScript, so the code is not shared. The
 * rules are, and so are the numbers: both sides are checked against the same
 * file, `fixtures/m2_sale_stock.tsv`. The Kotlin twin of this file is
 * `android/app/src/main/java/com/hisab/app/domain/Stock.kt` — keep the two
 * readable side by side.
 *
 * The one rule everything else follows from: a product's stock is never a
 * number anyone edits. It is the sum of its movements (D002, D020, PRD
 * section 9). Every movement says what happened and why, and once written it
 * is never changed — a mistake is fixed by writing an opposite movement, not
 * by rewriting the first one.
 */
import { generateId, type EntityId } from './id.js'
import { quantity, sumQuantity, ZERO_QUANTITY, type Quantity } from './quantity.js'
import { transactionTimeAtCreation, type TransactionTime } from './time.js'

/**
 * The five kinds of movement the PRD requires (section 9). Stored as the
 * code itself, so it is language-neutral and the label shown to a shopkeeper
 * is translated separately (D011).
 */
export const STOCK_MOVEMENT_TYPES = [
  /** Goods arriving from a supplier. Always increases stock. */
  'restock',
  /** Goods leaving in a sale. Always decreases stock. */
  'sale',
  /** Goods coming back — a customer return, or a sale that was undone. Always increases stock. */
  'return',
  /** Goods lost, broken or expired. Always decreases stock. */
  'damage',
  /** The difference between a real shelf count and what the ledger said. Either direction. */
  'correction',
] as const

export type StockMovementType = (typeof STOCK_MOVEMENT_TYPES)[number]

/**
 * One line of the stock ledger. Fields follow `docs/DATA_MODEL.md`.
 *
 * It carries no `revision`: a movement is never edited in place, so there is
 * nothing for two devices to disagree about (D017).
 */
export interface StockMovement {
  readonly id: EntityId
  readonly productId: EntityId
  readonly type: StockMovementType
  /** Signed: negative takes stock away, positive puts it back. */
  readonly quantityDelta: Quantity
  /** What caused this — a sale id for a sale, a supplier note for a restock, null if nothing. */
  readonly sourceReference: string | null
  /** When it happened on the device, kept apart from when the server saw it (D019). */
  readonly time: TransactionTime
}

function requirePositive(value: Quantity, what: string): void {
  if (value <= 0) {
    throw new RangeError(
      `A ${what} must be a positive quantity, got ${value}. ` +
        'Use correctStock() to move stock the other way.',
    )
  }
}

function movement(
  productId: EntityId,
  type: StockMovementType,
  quantityDelta: Quantity,
  sourceReference: string | null,
  occurredAt: Date,
  id: EntityId,
): StockMovement {
  return {
    id,
    productId,
    type,
    quantityDelta,
    sourceReference,
    time: transactionTimeAtCreation(occurredAt),
  }
}

/**
 * Goods arriving from a supplier.
 *
 * `amount` is how much arrived, as a positive quantity — a restock that takes
 * stock away is not a restock, it is a damage or a correction, and saying so
 * explicitly is what keeps the ledger readable months later.
 */
export function restock(
  productId: EntityId,
  amount: Quantity,
  occurredAt: Date,
  sourceReference: string | null = null,
  id: EntityId = generateId(),
): StockMovement {
  requirePositive(amount, 'restock')
  return movement(productId, 'restock', amount, sourceReference, occurredAt, id)
}

/**
 * Goods leaving in a sale. `amount` is how much was sold, positive; the
 * movement it produces is negative.
 *
 * This does not check whether there is enough stock, on purpose — see
 * `stockShortfall` and D031.
 */
export function sell(
  productId: EntityId,
  amount: Quantity,
  saleId: EntityId,
  occurredAt: Date,
  id: EntityId = generateId(),
): StockMovement {
  requirePositive(amount, 'sale')
  return movement(productId, 'sale', quantity(-amount), saleId, occurredAt, id)
}

/**
 * Goods coming back into stock: a customer returning an item, or a sale that
 * was undone (see `reverseSale`). `sourceReference` says which.
 */
export function returnStock(
  productId: EntityId,
  amount: Quantity,
  occurredAt: Date,
  sourceReference: string | null = null,
  id: EntityId = generateId(),
): StockMovement {
  requirePositive(amount, 'return')
  return movement(productId, 'return', amount, sourceReference, occurredAt, id)
}

/** Goods lost, broken or expired. `amount` is how much was lost, positive; the movement is negative. */
export function damage(
  productId: EntityId,
  amount: Quantity,
  occurredAt: Date,
  sourceReference: string | null = null,
  id: EntityId = generateId(),
): StockMovement {
  requirePositive(amount, 'damage')
  return movement(productId, 'damage', quantity(-amount), sourceReference, occurredAt, id)
}

/**
 * A shelf count.
 *
 * The shopkeeper does not type a difference — they say what is actually on
 * the shelf (`countedQuantity`), and this works out the difference from what
 * the ledger currently says (`recordedQuantity`, which comes from
 * `calculateCurrentStock`). That way the number a person enters is one they
 * can see, and the ledger still records why stock changed.
 *
 * A count that agrees with the ledger still writes a movement, of zero: it is
 * a record that the shelf was checked on that day, which is worth keeping.
 */
export function correctStock(
  productId: EntityId,
  countedQuantity: Quantity,
  recordedQuantity: Quantity,
  occurredAt: Date,
  sourceReference: string | null = null,
  id: EntityId = generateId(),
): StockMovement {
  if (countedQuantity < 0) {
    throw new RangeError(`A shelf count cannot be negative, got ${countedQuantity}.`)
  }
  const delta = quantity(countedQuantity - recordedQuantity)
  return movement(productId, 'correction', delta, sourceReference, occurredAt, id)
}

/**
 * What is on the shelf right now, according to the ledger: the sum of every
 * movement for that product.
 *
 * A product with no movements has no stock, not "unknown" — that is what
 * makes a brand-new product start at zero without anything having to write a
 * starting row.
 */
export function calculateCurrentStock(
  movements: readonly StockMovement[],
  productId?: EntityId,
): Quantity {
  const mine =
    productId === undefined ? movements : movements.filter((m) => m.productId === productId)
  return sumQuantity(mine.map((m) => m.quantityDelta))
}

/**
 * How much would be missing if `wanted` were sold out of `available`, and
 * zero when there is enough.
 *
 * A screen uses this to warn before a sale is confirmed. It never blocks the
 * sale (D031) — see `sell`.
 */
export function stockShortfall(available: Quantity, wanted: Quantity): Quantity {
  const missing = wanted - available
  return missing > 0 ? quantity(missing) : ZERO_QUANTITY
}

export function hasEnoughStock(available: Quantity, wanted: Quantity): boolean {
  return stockShortfall(available, wanted) === ZERO_QUANTITY
}
