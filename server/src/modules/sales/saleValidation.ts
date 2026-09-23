import { BAKI_ENTRY_TYPES, type BakiEntry } from '../../domain/bakiEntry.js'
import type { EntityId } from '../../domain/id.js'
import { money } from '../../domain/money.js'
import { quantity } from '../../domain/quantity.js'
import {
  calculateSaleTotal,
  checkSaleTransaction,
  SALE_PAYMENTS,
  type Sale,
  type SaleItem,
  type SaleTransaction,
} from '../../domain/sale.js'
import { STOCK_MOVEMENT_TYPES, type StockMovement } from '../../domain/stock.js'
import type { TransactionTime } from '../../domain/time.js'

/** Language-neutral codes a pushed sale can be refused with (D011). */
export const INVALID_PAYLOAD = 'INVALID_PAYLOAD'
export const SALE_NOT_FOUND = 'SALE_NOT_FOUND'
export const ALREADY_REVERSED = 'ALREADY_REVERSED'
export const CANNOT_REVERSE_A_REVERSAL = 'CANNOT_REVERSE_A_REVERSAL'

type Json = Record<string, unknown>

const isObject = (value: unknown): value is Json =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
const isId = (value: unknown): value is string => typeof value === 'string' && value.trim() !== ''
const isInteger = (value: unknown): value is number => Number.isSafeInteger(value)
const isIsoInstant = (value: unknown): value is string =>
  typeof value === 'string' && !Number.isNaN(Date.parse(value))
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/

/**
 * The server keeps its own clock for arrival. Whatever the phone put in
 * serverReceivedAt is ignored — it is filled in here, on insert (D019).
 */
function parseTime(value: unknown): TransactionTime | null {
  if (!isObject(value) || !isIsoInstant(value.occurredAt)) return null
  return { occurredAt: new Date(value.occurredAt).toISOString(), serverReceivedAt: null }
}

function parseMovement(value: unknown): StockMovement | null {
  if (!isObject(value)) return null
  const { id, productId, type, quantityDelta, sourceReference } = value
  const time = parseTime(value.time)
  if (!isId(id) || !isId(productId) || time === null) return null
  if (typeof type !== 'string' || !(STOCK_MOVEMENT_TYPES as readonly string[]).includes(type)) {
    return null
  }
  if (!isInteger(quantityDelta)) return null
  if (
    sourceReference !== null &&
    sourceReference !== undefined &&
    typeof sourceReference !== 'string'
  ) {
    return null
  }
  return {
    id: id as EntityId,
    productId: productId as EntityId,
    type: type as StockMovement['type'],
    quantityDelta: quantity(quantityDelta),
    sourceReference: (sourceReference as string | null | undefined) ?? null,
    time,
  }
}

/**
 * Reads a stock movement a phone pushed on its own — a restock, damage,
 * customer return or shelf count. Returns null for anything malformed, and
 * for a movement type that only a sale may create.
 *
 * The sign has to match the type: a restock that takes stock away is not a
 * restock (the same rule `restock()` enforces on the phone).
 */
export function parseStandaloneMovement(payload: unknown): StockMovement | null {
  const movement = parseMovement(payload)
  if (movement === null) return null
  switch (movement.type) {
    case 'restock':
    case 'return':
      return movement.quantityDelta > 0 ? movement : null
    case 'damage':
      return movement.quantityDelta < 0 ? movement : null
    case 'correction':
      return movement
    case 'sale':
      // A sale movement without its sale would be stock leaving for no
      // recorded reason; it only ever arrives inside a Sale event.
      return null
  }
}

function parseBaki(value: unknown): BakiEntry | null {
  if (!isObject(value)) return null
  const { id, customerId, amountDelta, type, reference, dueDate } = value
  const time = parseTime(value.time)
  if (!isId(id) || !isId(customerId) || time === null || !isInteger(amountDelta)) return null
  if (typeof type !== 'string' || !(BAKI_ENTRY_TYPES as readonly string[]).includes(type))
    return null
  if (reference !== null && reference !== undefined && typeof reference !== 'string') return null
  if (dueDate !== null && dueDate !== undefined) {
    if (
      typeof dueDate !== 'string' ||
      !DATE_ONLY.test(dueDate) ||
      Number.isNaN(Date.parse(dueDate))
    ) {
      return null
    }
  }
  return {
    id: id as EntityId,
    customerId: customerId as EntityId,
    amountDelta: money(amountDelta),
    type: type as BakiEntry['type'],
    reference: (reference as string | null | undefined) ?? null,
    dueDate: (dueDate as string | null | undefined) ?? null,
    time,
  }
}

/**
 * Reads a whole sale transaction a phone pushed: the sale, its lines, its
 * stock movements and its baki entry, in the same shape `domain/sale.ts`
 * builds (D026). Returns null if any part is missing or the wrong type.
 *
 * Shape only — whether the numbers are right is `checkPushedSale`'s job.
 */
export function parseSaleTransaction(payload: unknown): SaleTransaction | null {
  if (!isObject(payload) || !isObject(payload.sale)) return null
  const raw = payload.sale
  const time = parseTime(raw.time)
  if (!isId(raw.id) || time === null || !isInteger(raw.total)) return null
  if (
    typeof raw.payment !== 'string' ||
    !(SALE_PAYMENTS as readonly string[]).includes(raw.payment)
  ) {
    return null
  }
  const customerId = raw.customerId ?? null
  const reversesSaleId = raw.reversesSaleId ?? null
  if (customerId !== null && !isId(customerId)) return null
  if (reversesSaleId !== null && !isId(reversesSaleId)) return null

  const sale: Sale = {
    id: raw.id as EntityId,
    // Never taken from the payload: the caller replaces it with the shop in
    // the session token (D015).
    shopId: '',
    total: money(raw.total),
    payment: raw.payment as Sale['payment'],
    customerId: customerId as EntityId | null,
    reversesSaleId: reversesSaleId as EntityId | null,
    time,
  }

  if (!Array.isArray(payload.items) || !Array.isArray(payload.stockMovements)) return null

  const items: SaleItem[] = []
  for (const item of payload.items) {
    if (!isObject(item) || !isId(item.productId)) return null
    if (!isInteger(item.quantity) || !isInteger(item.unitPrice)) return null
    items.push({
      saleId: sale.id,
      productId: item.productId as EntityId,
      quantity: quantity(item.quantity),
      unitPrice: money(item.unitPrice),
    })
  }

  const stockMovements: StockMovement[] = []
  for (const value of payload.stockMovements) {
    const movement = parseMovement(value)
    if (movement === null) return null
    stockMovements.push(movement)
  }

  let bakiEntry: BakiEntry | null = null
  if (payload.bakiEntry !== null && payload.bakiEntry !== undefined) {
    bakiEntry = parseBaki(payload.bakiEntry)
    if (bakiEntry === null) return null
  }

  return { sale, items, stockMovements, bakiEntry }
}

/**
 * Checks that a pushed sale is one the rules could have produced — the server
 * never takes a phone's arithmetic on trust. Returns null when it is sound,
 * or the code to refuse it with.
 *
 * For an ordinary sale: every line has a positive quantity; the total is the
 * sum of the line totals by the shared rounding rule (D032); there is one
 * `sale` movement per line taking exactly that quantity out; and a credit
 * sale owes exactly its total, to the customer it names (D021).
 *
 * For a reversal, `original` is the sale being undone, already loaded from
 * this shop. Every line must be the original line negated at the original
 * price, stock must come back as `return` movements, and the baki entry (for
 * credit) must subtract exactly what the original added.
 */
export function checkPushedSale(
  transaction: SaleTransaction,
  original: SaleTransaction | null,
): string | null {
  try {
    checkSaleTransaction(transaction)
  } catch {
    return INVALID_PAYLOAD
  }

  const { sale, items, stockMovements, bakiEntry } = transaction
  const reversal = sale.reversesSaleId !== null

  if (new Set(items.map((item) => item.productId)).size !== items.length) return INVALID_PAYLOAD
  if (new Set(stockMovements.map((movement) => movement.id)).size !== stockMovements.length) {
    return INVALID_PAYLOAD
  }
  if (items.some((item) => item.unitPrice < 0)) return INVALID_PAYLOAD
  if (items.some((item) => (reversal ? item.quantity >= 0 : item.quantity <= 0))) {
    return INVALID_PAYLOAD
  }

  let total: number
  try {
    total = calculateSaleTotal(items)
  } catch {
    return INVALID_PAYLOAD
  }
  if (total !== sale.total) return INVALID_PAYLOAD

  // Each line's stock movement: the right product, the right direction, and
  // exactly the line's quantity.
  const expectedType = reversal ? 'return' : 'sale'
  const expectedReference = reversal ? sale.reversesSaleId : sale.id
  const byProduct = new Map(stockMovements.map((movement) => [movement.productId, movement]))
  if (byProduct.size !== stockMovements.length) return INVALID_PAYLOAD
  for (const item of items) {
    const movement = byProduct.get(item.productId)
    if (movement === undefined) return INVALID_PAYLOAD
    if (movement.type !== expectedType) return INVALID_PAYLOAD
    if (movement.quantityDelta !== -item.quantity) return INVALID_PAYLOAD
    if (movement.sourceReference !== expectedReference) return INVALID_PAYLOAD
  }

  if (bakiEntry !== null) {
    const expectedBakiType = reversal ? 'reversal' : 'credit_sale'
    if (bakiEntry.type !== expectedBakiType) return INVALID_PAYLOAD
    if (bakiEntry.reference !== expectedReference) return INVALID_PAYLOAD
  }

  if (!reversal) return null

  // --- A reversal must be the exact opposite of the sale it undoes. ---
  if (original === null) return SALE_NOT_FOUND
  if (original.sale.reversesSaleId !== null) return CANNOT_REVERSE_A_REVERSAL
  if (original.sale.payment !== sale.payment) return INVALID_PAYLOAD
  if (original.sale.customerId !== sale.customerId) return INVALID_PAYLOAD
  if (sale.total !== -original.sale.total) return INVALID_PAYLOAD
  if (items.length !== original.items.length) return INVALID_PAYLOAD

  const originalLines = new Map(original.items.map((item) => [item.productId, item]))
  for (const item of items) {
    const line = originalLines.get(item.productId)
    if (line === undefined) return INVALID_PAYLOAD
    if (item.quantity !== -line.quantity || item.unitPrice !== line.unitPrice)
      return INVALID_PAYLOAD
  }
  return null
}
