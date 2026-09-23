import { test } from 'node:test'
import assert from 'node:assert/strict'
import { generateId } from '../../domain/id.js'
import { money } from '../../domain/money.js'
import { quantity } from '../../domain/quantity.js'
import {
  completeCashSale,
  completeCreditSale,
  reverseSale,
  type SaleLine,
  type SaleTransaction,
} from '../../domain/sale.js'
import { damage, restock, sell } from '../../domain/stock.js'
import {
  CANNOT_REVERSE_A_REVERSAL,
  checkPushedSale,
  INVALID_PAYLOAD,
  parseSaleTransaction,
  parseStandaloneMovement,
  SALE_NOT_FOUND,
} from './saleValidation.js'

const AT = new Date('2026-09-17T10:00:00.000Z')
const rice = generateId()
const oil = generateId()

function cart(): SaleLine[] {
  return [
    { productId: rice, quantity: quantity(3000), unitPrice: money(5000) },
    { productId: oil, quantity: quantity(2000), unitPrice: money(2500) },
  ]
}

/** What arrives over the wire: plain JSON, as a phone sends it. */
function wire(value: unknown): ReturnType<typeof JSON.parse> {
  return JSON.parse(JSON.stringify(value))
}

function parsed(value: unknown): SaleTransaction {
  const transaction = parseSaleTransaction(value)
  assert.ok(transaction !== null, 'expected the payload to parse')
  return transaction
}

// --- A sound sale passes, whichever way it was built. ---

test('a cash sale built by the shared rules passes', () => {
  const sale = parsed(wire(completeCashSale('shop-1', cart(), AT)))
  assert.equal(checkPushedSale(sale, null), null)
})

test('a credit sale passes, and keeps its due date', () => {
  const sale = parsed(wire(completeCreditSale('shop-1', generateId(), cart(), AT, '2026-10-15')))
  assert.equal(checkPushedSale(sale, null), null)
  assert.equal(sale.bakiEntry?.dueDate, '2026-10-15')
})

test('a reversal of a cash sale passes against the sale it undoes', () => {
  const original = completeCashSale('shop-1', cart(), AT)
  const reversal = parsed(wire(reverseSale(original, AT)))
  assert.equal(checkPushedSale(reversal, original), null)
})

test('a reversal of a credit sale passes, clearing exactly what was owed', () => {
  const original = completeCreditSale('shop-1', generateId(), cart(), AT)
  const reversal = parsed(wire(reverseSale(original, AT)))
  assert.equal(checkPushedSale(reversal, original), null)
})

test('the shop in the payload is never used — it is blanked for the caller to fill from the session', () => {
  const sale = parsed(wire(completeCashSale('shop-2', cart(), AT)))
  assert.equal(sale.sale.shopId, '')
})

test('a serverReceivedAt sent by the phone is ignored', () => {
  const payload = wire(completeCashSale('shop-1', cart(), AT))
  payload.sale.time.serverReceivedAt = '2020-01-01T00:00:00.000Z'
  assert.equal(parsed(payload).sale.time.serverReceivedAt, null)
})

// --- The numbers are checked, not trusted. ---

test('a total that does not match its lines is refused', () => {
  const payload = wire(completeCashSale('shop-1', cart(), AT))
  payload.sale.total = 1
  assert.equal(checkPushedSale(parsed(payload), null), INVALID_PAYLOAD)
})

test('a line price changed after the total was worked out is refused', () => {
  const payload = wire(completeCashSale('shop-1', cart(), AT))
  payload.items[0].unitPrice = 1
  assert.equal(checkPushedSale(parsed(payload), null), INVALID_PAYLOAD)
})

test('a stock movement that takes out a different amount than the line sold is refused', () => {
  const payload = wire(completeCashSale('shop-1', cart(), AT))
  payload.stockMovements[0].quantityDelta = -1
  assert.equal(checkPushedSale(parsed(payload), null), INVALID_PAYLOAD)
})

test('a sale whose stock movement is missing is refused', () => {
  const payload = wire(completeCashSale('shop-1', cart(), AT))
  payload.stockMovements.pop()
  assert.equal(checkPushedSale(parsed(payload), null), INVALID_PAYLOAD)
})

test('a sale movement pointing at another sale is refused', () => {
  const payload = wire(completeCashSale('shop-1', cart(), AT))
  payload.stockMovements[0].sourceReference = generateId()
  assert.equal(checkPushedSale(parsed(payload), null), INVALID_PAYLOAD)
})

test('a sale movement of the wrong type is refused', () => {
  const payload = wire(completeCashSale('shop-1', cart(), AT))
  payload.stockMovements[0].type = 'damage'
  assert.equal(checkPushedSale(parsed(payload), null), INVALID_PAYLOAD)
})

test('a credit sale owing a different amount than its total is refused (D021)', () => {
  const payload = wire(completeCreditSale('shop-1', generateId(), cart(), AT))
  payload.bakiEntry.amountDelta = 100
  assert.equal(checkPushedSale(parsed(payload), null), INVALID_PAYLOAD)
})

test('a credit sale with no baki entry is refused (D021)', () => {
  const payload = wire(completeCreditSale('shop-1', generateId(), cart(), AT))
  payload.bakiEntry = null
  assert.equal(checkPushedSale(parsed(payload), null), INVALID_PAYLOAD)
})

test('a cash sale carrying a baki entry is refused', () => {
  const credit = wire(completeCreditSale('shop-1', generateId(), cart(), AT))
  const payload = wire(completeCashSale('shop-1', cart(), AT))
  payload.bakiEntry = credit.bakiEntry
  assert.equal(checkPushedSale(parsed(payload), null), INVALID_PAYLOAD)
})

test('a sale listing the same product twice is refused', () => {
  const payload = wire(completeCashSale('shop-1', cart(), AT))
  payload.items[1].productId = payload.items[0].productId
  assert.equal(checkPushedSale(parsed(payload), null), INVALID_PAYLOAD)
})

test('an ordinary sale with a negative line is refused', () => {
  const payload = wire(completeCashSale('shop-1', cart(), AT))
  payload.items[0].quantity = -3000
  payload.stockMovements[0].quantityDelta = 3000
  assert.equal(checkPushedSale(parsed(payload), null), INVALID_PAYLOAD)
})

// --- A reversal must be the exact opposite of what it undoes. ---

test('a reversal of a sale this shop does not have is refused', () => {
  const original = completeCashSale('shop-1', cart(), AT)
  const reversal = parsed(wire(reverseSale(original, AT)))
  assert.equal(checkPushedSale(reversal, null), SALE_NOT_FOUND)
})

test('a reversal cannot itself be reversed', () => {
  const original = completeCashSale('shop-1', cart(), AT)
  const reversal = reverseSale(original, AT)
  // Build what reversing the reversal would look like by hand, since the
  // domain function itself refuses to.
  const payload = wire(reverseSale(original, AT))
  payload.sale.reversesSaleId = reversal.sale.id
  payload.stockMovements.forEach(
    (m: Record<string, unknown>) => (m.sourceReference = reversal.sale.id),
  )
  assert.equal(checkPushedSale(parsed(payload), reversal), CANNOT_REVERSE_A_REVERSAL)
})

test('a reversal that gives back less than was sold is refused', () => {
  const original = completeCashSale('shop-1', cart(), AT)
  const payload = wire(reverseSale(original, AT))
  payload.items[0].quantity = -1000
  payload.stockMovements[0].quantityDelta = 1000
  assert.equal(checkPushedSale(parsed(payload), original), INVALID_PAYLOAD)
})

test('a reversal that clears baki but claims the sale was cash is refused', () => {
  const original = completeCreditSale('shop-1', generateId(), cart(), AT)
  const payload = wire(reverseSale(original, AT))
  payload.sale.payment = 'cash'
  payload.sale.customerId = null
  payload.bakiEntry = null
  assert.equal(checkPushedSale(parsed(payload), original), INVALID_PAYLOAD)
})

test('a credit reversal that restores stock but leaves the baki standing is refused (D021)', () => {
  const original = completeCreditSale('shop-1', generateId(), cart(), AT)
  const payload = wire(reverseSale(original, AT))
  payload.bakiEntry = null
  assert.equal(checkPushedSale(parsed(payload), original), INVALID_PAYLOAD)
})

test('a reversal whose stock comes back as a sale instead of a return is refused', () => {
  const original = completeCashSale('shop-1', cart(), AT)
  const payload = wire(reverseSale(original, AT))
  payload.stockMovements[0].type = 'sale'
  assert.equal(checkPushedSale(parsed(payload), original), INVALID_PAYLOAD)
})

// --- Shape. ---

test('a payload missing parts, or with the wrong types, does not parse', () => {
  const good = wire(completeCashSale('shop-1', cart(), AT))
  const broken = [
    null,
    'a sale',
    { ...good, sale: undefined },
    { ...good, items: 'none' },
    { ...good, sale: { ...good.sale, total: 12.5 } },
    { ...good, sale: { ...good.sale, payment: 'barter' } },
    { ...good, sale: { ...good.sale, time: { occurredAt: 'yesterday' } } },
    { ...good, items: [{ productId: rice, quantity: 1.5, unitPrice: 100 }] },
    {
      ...good,
      bakiEntry: {
        id: 'x',
        customerId: 'y',
        amountDelta: 1,
        type: 'gift',
        time: { occurredAt: AT },
      },
    },
  ]
  for (const payload of broken) {
    assert.equal(parseSaleTransaction(payload), null, JSON.stringify(payload)?.slice(0, 80))
  }
})

test('a due date that is not a plain date does not parse', () => {
  const payload = wire(completeCreditSale('shop-1', generateId(), cart(), AT, '2026-10-15'))
  payload.bakiEntry.dueDate = '15/10/2026'
  assert.equal(parseSaleTransaction(payload), null)
})

// --- Stand-alone stock movements. ---

test('a restock, a damage and a shelf count parse on their own', () => {
  assert.ok(parseStandaloneMovement(wire(restock(rice, quantity(5000), AT))))
  assert.ok(parseStandaloneMovement(wire(damage(rice, quantity(1000), AT))))
  const count = wire(restock(rice, quantity(5000), AT))
  count.type = 'correction'
  count.quantityDelta = -250
  assert.ok(parseStandaloneMovement(count))
})

test('a sale movement is refused on its own — it only travels inside a sale', () => {
  assert.equal(parseStandaloneMovement(wire(sell(rice, quantity(1000), generateId(), AT))), null)
})

test('a restock that takes stock away, or a damage that adds it, is refused', () => {
  const backwardsRestock = wire(restock(rice, quantity(5000), AT))
  backwardsRestock.quantityDelta = -5000
  assert.equal(parseStandaloneMovement(backwardsRestock), null)

  const backwardsDamage = wire(damage(rice, quantity(1000), AT))
  backwardsDamage.quantityDelta = 1000
  assert.equal(parseStandaloneMovement(backwardsDamage), null)
})
