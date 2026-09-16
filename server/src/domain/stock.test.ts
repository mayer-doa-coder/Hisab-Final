import { test } from 'node:test'
import assert from 'node:assert/strict'
import { generateId } from './id.js'
import { quantity, ZERO_QUANTITY } from './quantity.js'
import {
  calculateCurrentStock,
  correctStock,
  damage,
  hasEnoughStock,
  restock,
  returnStock,
  sell,
  stockShortfall,
} from './stock.js'

const AT = new Date('2026-09-15T10:00:00.000Z')
const PRODUCT = generateId()

// The numbers here are also in fixtures/m2_sale_stock.tsv, which Kotlin runs
// against the same rules (Step 37). These tests cover what a shared number
// cannot: signs, types, references, timestamps and refusals.

test('restock adds stock and is typed as a restock', () => {
  const movement = restock(PRODUCT, quantity(10_000), AT)
  assert.equal(movement.type, 'restock')
  assert.equal(movement.quantityDelta, 10_000)
  assert.equal(movement.productId, PRODUCT)
})

test('sell takes stock away, and says which sale did it', () => {
  const saleId = generateId()
  const movement = sell(PRODUCT, quantity(3000), saleId, AT)
  assert.equal(movement.type, 'sale')
  assert.equal(movement.quantityDelta, -3000, 'a sale is a negative delta')
  assert.equal(movement.sourceReference, saleId)
})

test('returnStock puts stock back', () => {
  const movement = returnStock(PRODUCT, quantity(500), AT, 'sale-7')
  assert.equal(movement.type, 'return')
  assert.equal(movement.quantityDelta, 500)
  assert.equal(movement.sourceReference, 'sale-7')
})

test('damage takes stock away', () => {
  const movement = damage(PRODUCT, quantity(1000), AT)
  assert.equal(movement.type, 'damage')
  assert.equal(movement.quantityDelta, -1000)
})

test('every movement records when it happened, and not yet when the server saw it', () => {
  const movement = restock(PRODUCT, quantity(1000), AT)
  assert.equal(movement.time.occurredAt, AT.toISOString())
  assert.equal(movement.time.serverReceivedAt, null, 'nothing has reached the server yet (D019)')
})

test('a movement that only ever takes stock away refuses a zero or negative amount', () => {
  for (const amount of [0, -1000]) {
    assert.throws(() => restock(PRODUCT, quantity(amount), AT), RangeError)
    assert.throws(() => sell(PRODUCT, quantity(amount), generateId(), AT), RangeError)
    assert.throws(() => returnStock(PRODUCT, quantity(amount), AT), RangeError)
    assert.throws(() => damage(PRODUCT, quantity(amount), AT), RangeError)
  }
})

test('correctStock records the difference between the shelf and the ledger', () => {
  const found = correctStock(PRODUCT, quantity(8500), quantity(10_000), AT)
  assert.equal(found.type, 'correction')
  assert.equal(found.quantityDelta, -1500)

  const extra = correctStock(PRODUCT, quantity(12_000), quantity(10_000), AT)
  assert.equal(extra.quantityDelta, 2000)
})

test('a count that agrees still writes a movement, of zero', () => {
  const movement = correctStock(PRODUCT, quantity(10_000), quantity(10_000), AT)
  assert.equal(movement.quantityDelta, 0)
})

test('a shelf count cannot be negative — nobody counts minus three bottles', () => {
  assert.throws(() => correctStock(PRODUCT, quantity(-1), quantity(0), AT), RangeError)
})

test('current stock is the sum of the movements, and nothing else', () => {
  const movements = [
    restock(PRODUCT, quantity(10_000), AT),
    sell(PRODUCT, quantity(3000), generateId(), AT),
    restock(PRODUCT, quantity(2000), AT),
  ]
  assert.equal(calculateCurrentStock(movements), 9000)
})

test('a product with no movements has no stock', () => {
  assert.equal(calculateCurrentStock([]), ZERO_QUANTITY)
})

test('current stock counts only the product asked about', () => {
  const other = generateId()
  const movements = [
    restock(PRODUCT, quantity(10_000), AT),
    restock(other, quantity(4000), AT),
    sell(PRODUCT, quantity(3000), generateId(), AT),
  ]
  assert.equal(calculateCurrentStock(movements, PRODUCT), 7000)
  assert.equal(calculateCurrentStock(movements, other), 4000)
})

test('the order the movements are summed in does not change the answer', () => {
  const movements = [
    restock(PRODUCT, quantity(10_000), AT),
    sell(PRODUCT, quantity(3000), generateId(), AT),
    damage(PRODUCT, quantity(500), AT),
    returnStock(PRODUCT, quantity(1000), AT),
  ]
  const forwards = calculateCurrentStock(movements)
  const backwards = calculateCurrentStock([...movements].reverse())
  assert.equal(forwards, backwards)
  assert.equal(forwards, 7500)
})

test('selling more than was ever recorded goes negative rather than being refused (D031)', () => {
  const movements = [
    restock(PRODUCT, quantity(2000), AT),
    sell(PRODUCT, quantity(5000), generateId(), AT),
  ]
  assert.equal(calculateCurrentStock(movements), -3000)
})

test('stockShortfall says how much is missing, and nothing when there is enough', () => {
  assert.equal(stockShortfall(quantity(2000), quantity(5000)), 3000)
  assert.equal(stockShortfall(quantity(5000), quantity(5000)), ZERO_QUANTITY)
  assert.equal(stockShortfall(quantity(9000), quantity(5000)), ZERO_QUANTITY)
  assert.equal(stockShortfall(quantity(-3000), quantity(1000)), 4000, 'already oversold')

  assert.equal(hasEnoughStock(quantity(5000), quantity(5000)), true)
  assert.equal(hasEnoughStock(quantity(4999), quantity(5000)), false)
})

test('a correction brings the ledger back to what was counted', () => {
  const movements = [
    restock(PRODUCT, quantity(10_000), AT),
    sell(PRODUCT, quantity(3000), generateId(), AT),
  ]
  const recorded = calculateCurrentStock(movements)
  const counted = quantity(6500)

  const corrected = [...movements, correctStock(PRODUCT, counted, recorded, AT)]
  assert.equal(calculateCurrentStock(corrected), counted)
})
