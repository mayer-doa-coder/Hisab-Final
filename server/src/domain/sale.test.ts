import { test } from 'node:test'
import assert from 'node:assert/strict'
import { generateId } from './id.js'
import { money } from './money.js'
import { quantity } from './quantity.js'
import { calculateCurrentStock } from './stock.js'
import {
  calculateLineTotal,
  calculateSaleTotal,
  checkSaleTransaction,
  completeCashSale,
  completeCreditSale,
  reverseSale,
  type SaleLine,
} from './sale.js'

const AT = new Date('2026-09-15T10:00:00.000Z')
const LATER = new Date('2026-09-15T18:30:00.000Z')
const SHOP = 'shop-1'

const rice = generateId()
const oil = generateId()

function cart(): SaleLine[] {
  return [
    { productId: rice, quantity: quantity(3000), unitPrice: money(5000) },
    { productId: oil, quantity: quantity(2000), unitPrice: money(2500) },
  ]
}

// The arithmetic itself is checked against fixtures/m2_sale_stock.tsv, which
// Kotlin runs too (Step 37). These tests cover what a shared number cannot:
// what a completed sale is made of, and what a reversal leaves behind.

test('a line total is quantity times price, in integer poisha', () => {
  assert.equal(calculateLineTotal(quantity(3000), money(5000)), 15_000)
})

test('a half poisha rounds away from zero, in both directions, so a reversal cancels', () => {
  const charged = calculateLineTotal(quantity(1250), money(9050))
  const refunded = calculateLineTotal(quantity(-1250), money(9050))
  assert.equal(charged, 11_313)
  assert.equal(refunded, -11_313)
  assert.equal(charged + refunded, 0, 'no poisha is left behind')
})

test('a negative price is refused — a discount is a lower price, not a negative one', () => {
  assert.throws(() => calculateLineTotal(quantity(1000), money(-1)), RangeError)
})

test('a sale total rounds each line first, then adds', () => {
  const saleId = generateId()
  const items = [
    { saleId, productId: rice, quantity: quantity(1250), unitPrice: money(9050) },
    { saleId, productId: oil, quantity: quantity(1250), unitPrice: money(9050) },
  ]
  assert.equal(calculateSaleTotal(items), 22_626, 'not 22625, which rounding once at the end gives')
})

test('a cash sale records the sale, its lines and one stock movement each', () => {
  const sale = completeCashSale(SHOP, cart(), AT)

  assert.equal(sale.sale.total, 20_000)
  assert.equal(sale.sale.payment, 'cash')
  assert.equal(sale.items.length, 2)
  assert.equal(sale.stockMovements.length, 2)
  assert.equal(calculateCurrentStock(sale.stockMovements), -5000, 'stock left the shop')
})

test('a cash sale owes nobody anything', () => {
  const sale = completeCashSale(SHOP, cart(), AT)
  assert.equal(sale.bakiEntry, null)
  assert.equal(sale.sale.customerId, null)
})

test('every stock movement points back at the sale that caused it', () => {
  const sale = completeCashSale(SHOP, cart(), AT)
  for (const movement of sale.stockMovements) {
    assert.equal(movement.sourceReference, sale.sale.id)
    assert.equal(movement.type, 'sale')
  }
})

test('a sale records when it happened, not when the server will see it', () => {
  const sale = completeCashSale(SHOP, cart(), AT)
  assert.equal(sale.sale.time.occurredAt, AT.toISOString())
  assert.equal(sale.sale.time.serverReceivedAt, null)
})

test('a credit sale owes exactly what it totalled', () => {
  const customer = generateId()
  const sale = completeCreditSale(SHOP, customer, cart(), AT)

  assert.equal(sale.sale.payment, 'credit')
  assert.equal(sale.sale.customerId, customer)
  assert.equal(sale.bakiEntry?.amountDelta, sale.sale.total)
  assert.equal(sale.bakiEntry?.type, 'credit_sale')
  assert.equal(sale.bakiEntry?.reference, sale.sale.id)
})

test('a credit sale can carry a due date, and does not have to', () => {
  const customer = generateId()
  assert.equal(completeCreditSale(SHOP, customer, cart(), AT).bakiEntry?.dueDate, null)
  assert.equal(
    completeCreditSale(SHOP, customer, cart(), AT, '2026-10-15').bakiEntry?.dueDate,
    '2026-10-15',
  )
})

test('a credit sale moves stock exactly like a cash sale', () => {
  const cash = completeCashSale(SHOP, cart(), AT)
  const credit = completeCreditSale(SHOP, generateId(), cart(), AT)
  assert.equal(
    calculateCurrentStock(credit.stockMovements),
    calculateCurrentStock(cash.stockMovements),
  )
})

test('an empty cart is not a sale', () => {
  assert.throws(() => completeCashSale(SHOP, [], AT), RangeError)
  assert.throws(() => completeCreditSale(SHOP, generateId(), [], AT), RangeError)
})

test('a zero or negative quantity is not a sale line', () => {
  for (const amount of [0, -1000]) {
    assert.throws(
      () =>
        completeCashSale(
          SHOP,
          [{ productId: rice, quantity: quantity(amount), unitPrice: money(5000) }],
          AT,
        ),
      RangeError,
    )
  }
})

test('the same product twice in one cart is refused, so the lines cannot disagree with the total', () => {
  assert.throws(
    () =>
      completeCashSale(
        SHOP,
        [
          { productId: rice, quantity: quantity(1000), unitPrice: money(5000) },
          { productId: rice, quantity: quantity(2000), unitPrice: money(5000) },
        ],
        AT,
      ),
    RangeError,
  )
})

test('reversing a cash sale gives the stock back and nets the money to zero', () => {
  const sale = completeCashSale(SHOP, cart(), AT)
  const undone = reverseSale(sale, LATER)

  assert.equal(undone.sale.total, -sale.sale.total)
  assert.equal(undone.sale.reversesSaleId, sale.sale.id)
  assert.equal(undone.bakiEntry, null, 'a cash sale owed nothing, so nothing is cleared')
  assert.equal(
    calculateCurrentStock([...sale.stockMovements, ...undone.stockMovements]),
    0,
    'stock is back where it started',
  )
})

test('a reversal is stock coming back in, pointing at the sale it undoes', () => {
  const sale = completeCashSale(SHOP, cart(), AT)
  const undone = reverseSale(sale, LATER)

  for (const movement of undone.stockMovements) {
    assert.equal(movement.type, 'return')
    assert.equal(movement.sourceReference, sale.sale.id)
    assert.ok(movement.quantityDelta > 0)
  }
})

test('a reversal happens when it happens, not when the sale did', () => {
  const sale = completeCashSale(SHOP, cart(), AT)
  const undone = reverseSale(sale, LATER)
  assert.equal(undone.sale.time.occurredAt, LATER.toISOString())
})

// D021: the sale, its stock and its baki move together, or not at all.
test('reversing a credit sale undoes the sale, the stock and the baki together', () => {
  const customer = generateId()
  const sale = completeCreditSale(SHOP, customer, cart(), AT)
  const undone = reverseSale(sale, LATER)

  assert.equal(sale.sale.total + undone.sale.total, 0, 'money nets to zero')
  assert.equal(
    calculateCurrentStock([...sale.stockMovements, ...undone.stockMovements]),
    0,
    'stock nets to zero',
  )
  assert.equal(
    (sale.bakiEntry?.amountDelta ?? 0) + (undone.bakiEntry?.amountDelta ?? 0),
    0,
    'baki nets to zero',
  )
  assert.equal(undone.bakiEntry?.customerId, customer)
  assert.equal(undone.bakiEntry?.type, 'reversal')
})

// The point of D021 is that there is no way to get a partial reversal. This
// is the test that says so: the three parts arrive in one value, and the
// value refuses to exist without all of them.
test('a credit sale reversal cannot be built with the stock restored but the baki left standing', () => {
  const customer = generateId()
  const sale = completeCreditSale(SHOP, customer, cart(), AT)
  const undone = reverseSale(sale, LATER)

  assert.throws(
    () => checkSaleTransaction({ ...undone, bakiEntry: null }),
    RangeError,
    'dropping the baki entry from a credit reversal must be impossible',
  )
  assert.throws(
    () => checkSaleTransaction({ ...undone, stockMovements: [] }),
    RangeError,
    'dropping the stock movements must be impossible',
  )
  assert.throws(
    () =>
      checkSaleTransaction({
        ...undone,
        bakiEntry: { ...undone.bakiEntry!, amountDelta: money(-1) },
      }),
    RangeError,
    'clearing a different amount than the sale totalled must be impossible',
  )
})

test('a reversal cannot itself be reversed', () => {
  const sale = completeCashSale(SHOP, cart(), AT)
  const undone = reverseSale(sale, LATER)
  assert.throws(() => reverseSale(undone, LATER), RangeError)
})

test("a day's takings already exclude a reversed sale, without editing history", () => {
  const kept = completeCashSale(SHOP, cart(), AT)
  const mistake = completeCashSale(SHOP, cart(), AT)
  const undone = reverseSale(mistake, LATER)

  const takings = [kept.sale, mistake.sale, undone.sale].reduce((sum, sale) => sum + sale.total, 0)
  assert.equal(takings, kept.sale.total)
})
