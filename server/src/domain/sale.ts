/**
 * Sales, as plain functions — the backend half of Step 36 (Step 37).
 *
 * The Kotlin twin is `android/app/src/main/java/com/hisab/app/domain/Sale.kt`;
 * both are checked against the same `fixtures/m2_sale_stock.tsv`.
 *
 * A completed sale is a single value holding everything the sale caused — the
 * sale, its lines, its stock movements, and (on credit) its baki entry — so a
 * caller cannot accidentally store one part without the others. That is
 * D021's atomicity made structural rather than remembered.
 */
import { type BakiEntry } from './bakiEntry.js'
import { generateId, type EntityId } from './id.js'
import { money, sumMoney, type Money } from './money.js'
import { quantity, type Quantity } from './quantity.js'
import { returnStock, sell, type StockMovement } from './stock.js'
import { transactionTimeAtCreation, type TransactionTime } from './time.js'

/** How the customer paid. Stored as the code itself, so it is language-neutral (D011). */
export const SALE_PAYMENTS = ['cash', 'credit'] as const
export type SalePayment = (typeof SALE_PAYMENTS)[number]

/**
 * One completed sale. Fields follow `docs/DATA_MODEL.md`, plus three the
 * table there did not name but PRD section 8 requires: which way it was paid,
 * which customer owes it, and — for a reversal — which sale it undoes.
 *
 * It carries no `revision`: a sale is never edited in place. A mistake is
 * undone by `reverseSale`, which writes a second, opposite sale rather than
 * touching this one.
 */
export interface Sale {
  readonly id: EntityId
  /** Never taken from a request body — the server derives it from the session (D015). */
  readonly shopId: string
  readonly total: Money
  readonly payment: SalePayment
  /** Set for a credit sale, null for cash. */
  readonly customerId: EntityId | null
  /**
   * Null for a normal sale. On a reversal, the id of the sale being undone.
   *
   * This is what keeps a day's takings right without editing history: a
   * reversal is a sale with a negative total, so summing `total` over a day
   * already excludes what was reversed.
   */
  readonly reversesSaleId: EntityId | null
  readonly time: TransactionTime
}

/**
 * One line of a sale. A line is identified by its sale and its product
 * together, so one product appears at most once in a sale.
 *
 * `unitPrice` is copied onto the line rather than read from the product
 * later. A price the shopkeeper changes next month must never quietly change
 * what last month's receipt said.
 */
export interface SaleItem {
  readonly saleId: EntityId
  readonly productId: EntityId
  readonly quantity: Quantity
  readonly unitPrice: Money
}

/** What the cart holds before the sale is confirmed and has an id. */
export interface SaleLine {
  readonly productId: EntityId
  readonly quantity: Quantity
  readonly unitPrice: Money
}

/** Everything one sale caused, together. Built only by the functions below. */
export interface SaleTransaction {
  readonly sale: Sale
  readonly items: readonly SaleItem[]
  readonly stockMovements: readonly StockMovement[]
  readonly bakiEntry: BakiEntry | null
}

/**
 * The checks that make the type above mean something: a credit sale always
 * has a customer and a baki entry, a cash sale never has either, and there is
 * one stock movement per line.
 *
 * Every builder below ends by calling this, so a half-formed sale is never
 * handed back and therefore never stored (D021). Kotlin says the same thing
 * in `SaleTransaction`'s `init` block; TypeScript has no such block, so it is
 * a function that runs at the same moment.
 */
export function checkSaleTransaction(transaction: SaleTransaction): SaleTransaction {
  const { sale, items, stockMovements, bakiEntry } = transaction

  if (items.length === 0) throw new RangeError('A sale has at least one line.')
  if (stockMovements.length !== items.length) {
    throw new RangeError(
      `Every sale line moves stock: ${items.length} lines but ${stockMovements.length} movements.`,
    )
  }

  if (sale.payment === 'cash') {
    if (sale.customerId !== null) throw new RangeError('A cash sale has no customer.')
    if (bakiEntry !== null) {
      throw new RangeError('A cash sale creates no baki entry — nothing is owed.')
    }
    return transaction
  }

  if (sale.customerId === null) throw new RangeError('A credit sale must say who owes it.')
  if (bakiEntry === null) {
    throw new RangeError('A credit sale must create a baki entry (D021).')
  }
  if (bakiEntry.customerId !== sale.customerId) {
    throw new RangeError('The baki entry must be owed by the customer the sale names.')
  }
  if (bakiEntry.amountDelta !== sale.total) {
    throw new RangeError(
      `A credit sale owes exactly what it totalled: sale ${sale.total}, baki ${bakiEntry.amountDelta}.`,
    )
  }
  return transaction
}

const QUANTITY_SCALE = 1000

/**
 * Integer division that rounds a half away from zero, so +0.5 becomes +1 and
 * -0.5 becomes -1. Truncation toward zero would round a reversal the wrong
 * way — see `calculateLineTotal`.
 */
function divideRoundingHalfAwayFromZero(numerator: number, denominator: number): number {
  const whole = Math.trunc(numerator / denominator)
  const remainder = numerator % denominator
  if (Math.abs(remainder) * 2 < denominator) return whole
  return numerator < 0 ? whole - 1 : whole + 1
}

/**
 * What one line costs.
 *
 * ```text
 * lineTotal = quantityScaled x unitPricePoisha / 1000
 * ```
 *
 * The division is the only place a sale can land between two poisha — 1.25 kg
 * at 90.50 taka is 113.125 taka. It is resolved by rounding half away from
 * zero, so a reversed line gives back exactly what the line charged: rounding
 * half upward instead would turn +113.13 into a -113.12 refund and leave one
 * poisha behind on every reversed half-poisha line.
 *
 * All of it is integer arithmetic. Money and quantity are never floats
 * (D019), so no rounding error can build up across a day's sales.
 */
export function calculateLineTotal(lineQuantity: Quantity, unitPrice: Money): Money {
  if (unitPrice < 0) {
    throw new RangeError(`A unit price cannot be negative, got ${unitPrice} poisha.`)
  }
  return money(divideRoundingHalfAwayFromZero(lineQuantity * unitPrice, QUANTITY_SCALE))
}

/**
 * What the whole sale costs: each line rounded on its own, then added.
 *
 * Rounding per line, not once at the end, is what makes the printed lines add
 * up to the printed total — a shopkeeper who checks the arithmetic by hand
 * has to get the same answer.
 */
export function calculateSaleTotal(items: readonly SaleItem[]): Money {
  return sumMoney(items.map((item) => calculateLineTotal(item.quantity, item.unitPrice)))
}

function toItems(saleId: EntityId, lines: readonly SaleLine[]): SaleItem[] {
  if (lines.length === 0) throw new RangeError('A sale needs at least one line.')

  const products = new Set(lines.map((line) => line.productId))
  if (products.size !== lines.length) {
    throw new RangeError(
      'A product appears at most once in a sale — add up the quantities into one line first.',
    )
  }

  for (const line of lines) {
    if (line.quantity <= 0) {
      throw new RangeError(`A sale line must have a positive quantity, got ${line.quantity}.`)
    }
  }

  return lines.map((line) => ({
    saleId,
    productId: line.productId,
    quantity: line.quantity,
    unitPrice: line.unitPrice,
  }))
}

/**
 * A cash sale: the sale, its lines, and one stock movement per line.
 *
 * Nothing is checked against current stock. The goods are being handed over;
 * refusing to record that would only make the ledger wrong about the real
 * world (D031). A screen warns first, using `stockShortfall`.
 */
export function completeCashSale(
  shopId: string,
  lines: readonly SaleLine[],
  occurredAt: Date,
  saleId: EntityId = generateId(),
): SaleTransaction {
  const items = toItems(saleId, lines)
  return checkSaleTransaction({
    sale: {
      id: saleId,
      shopId,
      total: calculateSaleTotal(items),
      payment: 'cash',
      customerId: null,
      reversesSaleId: null,
      time: transactionTimeAtCreation(occurredAt),
    },
    items,
    stockMovements: items.map((item) => sell(item.productId, item.quantity, saleId, occurredAt)),
    bakiEntry: null,
  })
}

/**
 * A credit sale: everything a cash sale produces, plus the baki entry saying
 * the customer owes the total.
 *
 * The amount owed is never typed separately — it is the sale total, so the
 * two can never disagree.
 */
export function completeCreditSale(
  shopId: string,
  customerId: EntityId,
  lines: readonly SaleLine[],
  occurredAt: Date,
  dueDate: string | null = null,
  saleId: EntityId = generateId(),
): SaleTransaction {
  const items = toItems(saleId, lines)
  const total = calculateSaleTotal(items)
  const time: TransactionTime = transactionTimeAtCreation(occurredAt)

  return checkSaleTransaction({
    sale: {
      id: saleId,
      shopId,
      total,
      payment: 'credit',
      customerId,
      reversesSaleId: null,
      time,
    },
    items,
    stockMovements: items.map((item) => sell(item.productId, item.quantity, saleId, occurredAt)),
    bakiEntry: {
      id: generateId(),
      customerId,
      amountDelta: total,
      type: 'credit_sale',
      reference: saleId,
      dueDate,
      time,
    },
  })
}

/**
 * Undoes a sale by writing its opposite, never by deleting it (PRD section 8).
 *
 * What comes back is a second, complete sale: a negative total, negative
 * lines, stock going back in as `return` movements referencing the original
 * sale, and — for a credit sale — a baki entry that subtracts exactly what
 * the sale added. The three move together because they arrive together, in
 * one value (D021). There is no arrangement of these functions that restores
 * stock while leaving the customer still owing.
 *
 * A reversal cannot itself be reversed: undoing an undo is a new sale, and
 * calling it a reversal would leave the history unreadable. Whether a sale
 * has *already* been reversed is a question about what is stored, not about
 * these values, so the repository answers it (Step 43).
 */
export function reverseSale(
  original: SaleTransaction,
  occurredAt: Date,
  saleId: EntityId = generateId(),
): SaleTransaction {
  if (original.sale.reversesSaleId !== null) {
    throw new RangeError('A reversal cannot be reversed. Record a new sale instead.')
  }

  const reversedTotal = money(-original.sale.total)
  const time = transactionTimeAtCreation(occurredAt)

  return checkSaleTransaction({
    sale: {
      id: saleId,
      shopId: original.sale.shopId,
      total: reversedTotal,
      payment: original.sale.payment,
      customerId: original.sale.customerId,
      reversesSaleId: original.sale.id,
      time,
    },
    items: original.items.map((item) => ({
      saleId,
      productId: item.productId,
      quantity: quantity(-item.quantity),
      unitPrice: item.unitPrice,
    })),
    stockMovements: original.items.map((item) =>
      returnStock(item.productId, item.quantity, occurredAt, original.sale.id),
    ),
    bakiEntry:
      original.bakiEntry === null
        ? null
        : {
            id: generateId(),
            customerId: original.bakiEntry.customerId,
            amountDelta: reversedTotal,
            type: 'reversal',
            reference: original.sale.id,
            dueDate: null,
            time,
          },
  })
}
