/** Step 49. The numbers shared with Android are in `bakiFixture.test.ts`; this is what is specific to one function. */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  addCredit,
  calculateBalance,
  isOverdue,
  overdueAmount,
  receivePayment,
  reverseEntry,
} from './baki.js'
import type { EntityId } from './id.js'
import { money, ZERO_MONEY } from './money.js'
import { quantity } from './quantity.js'
import { completeCreditSale, reverseSale } from './sale.js'

const AT = new Date('2026-09-15T10:00:00.000Z')
const TODAY = '2026-09-15'
const RAHIM = 'rahim' as EntityId
const KARIM = 'karim' as EntityId

const later = (seconds: number) => new Date(AT.getTime() + seconds * 1000)
const oneLineSale = (customer: EntityId, price: number, dueDate: string | null = null) =>
  completeCreditSale(
    'shop-1',
    customer,
    [{ productId: 'p' as EntityId, quantity: quantity(1000), unitPrice: money(price) }],
    AT,
    dueDate,
  )

// --- addCredit ---

test('addCredit makes a positive entry of type credit with no reference', () => {
  const entry = addCredit(RAHIM, money(50_000), AT, '2026-10-01', 'e1' as EntityId)

  assert.equal(entry.id, 'e1')
  assert.equal(entry.customerId, RAHIM)
  assert.equal(entry.amountDelta, 50_000)
  assert.equal(entry.type, 'credit')
  assert.equal(
    entry.reference,
    null,
    'a hand-written entry names nothing — reference is never free text',
  )
  assert.equal(entry.dueDate, '2026-10-01')
})

test('an entry records when it happened and is not yet known to the server', () => {
  const entry = addCredit(RAHIM, money(100), AT)
  assert.equal(entry.time.occurredAt, AT.toISOString())
  assert.equal(entry.time.serverReceivedAt, null)
})

test('addCredit has no due date unless one is given', () => {
  assert.equal(addCredit(RAHIM, money(100), AT).dueDate, null)
})

test('addCredit refuses zero and negative amounts, and points at receivePayment', () => {
  for (const poisha of [0, -1, -50_000]) {
    assert.throws(() => addCredit(RAHIM, money(poisha), AT), /receivePayment/)
  }
})

test('addCredit refuses a due date that is not a yyyy-MM-dd date', () => {
  for (const bad of [
    '15-10-2026',
    '2026-13-01',
    '2026-02-30',
    '2026-04-31',
    'tomorrow',
    '2026-1-5',
    '',
  ]) {
    assert.throws(() => addCredit(RAHIM, money(100), AT, bad), RangeError, bad)
  }
})

test('addCredit rejects a non-integer amount, because money is never a float', () => {
  assert.throws(() => money(10.5))
})

test('each entry gets its own id', () => {
  assert.notEqual(addCredit(RAHIM, money(1), AT).id, addCredit(RAHIM, money(1), AT).id)
})

// --- receivePayment ---

test('receivePayment stores the payment as a negative amount of type payment', () => {
  const entry = receivePayment(RAHIM, money(20_000), AT, 'p1' as EntityId)

  assert.equal(entry.id, 'p1')
  assert.equal(entry.amountDelta, -20_000)
  assert.equal(entry.type, 'payment')
  assert.equal(entry.reference, null)
  assert.equal(entry.dueDate, null, 'a payment has no due date')
})

test('receivePayment refuses zero and negative amounts', () => {
  for (const poisha of [0, -1, -20_000]) {
    assert.throws(() => receivePayment(RAHIM, money(poisha), AT), RangeError)
  }
})

// --- reverseEntry ---

test('reversing a credit writes the opposite entry, and references the original', () => {
  const credit = addCredit(RAHIM, money(50_000), AT, '2026-10-01')
  const undone = reverseEntry(credit, later(60), 'r1' as EntityId)

  assert.equal(undone.id, 'r1')
  assert.equal(undone.customerId, RAHIM)
  assert.equal(undone.amountDelta, -50_000)
  assert.equal(undone.type, 'entry_reversal')
  assert.equal(undone.reference, credit.id)
  assert.equal(undone.dueDate, null, 'the undo carries no due date of its own')
  assert.equal(undone.time.occurredAt, later(60).toISOString())
})

test('reversing a payment writes a positive entry', () => {
  assert.equal(reverseEntry(receivePayment(RAHIM, money(20_000), AT), AT).amountDelta, 20_000)
})

test('reversing leaves the original untouched', () => {
  const credit = addCredit(RAHIM, money(50_000), AT)
  const snapshot = structuredClone(credit)
  reverseEntry(credit, AT)
  assert.deepEqual(credit, snapshot, 'history is never edited')
})

test('an entry and its reversal cancel exactly', () => {
  for (const original of [
    addCredit(RAHIM, money(12_345), AT),
    receivePayment(RAHIM, money(6_789), AT),
  ]) {
    assert.equal(calculateBalance([original, reverseEntry(original, AT)], RAHIM), ZERO_MONEY)
  }
})

test("a credit sale's baki cannot be reversed on its own, it goes with its sale and stock", () => {
  assert.throws(() => reverseEntry(oneLineSale(RAHIM, 5_000).bakiEntry!, AT), /reverseSale/)
})

test('a reversal cannot be reversed, whether it came from a sale or from an entry', () => {
  const saleReversal = reverseSale(oneLineSale(RAHIM, 5_000), AT).bakiEntry!
  const entryReversal = reverseEntry(addCredit(RAHIM, money(100), AT), AT)

  assert.throws(() => reverseEntry(saleReversal, AT), /cannot be reversed/)
  assert.throws(() => reverseEntry(entryReversal, AT), /cannot be reversed/)
})

// --- calculateBalance ---

test('a customer with no entries owes nothing', () => {
  assert.equal(calculateBalance([], RAHIM), ZERO_MONEY)
})

test('calculateBalance counts only the customer asked about', () => {
  const entries = [
    addCredit(RAHIM, money(500), AT),
    addCredit(KARIM, money(70), AT),
    receivePayment(KARIM, money(20), AT),
  ]
  assert.equal(calculateBalance(entries, RAHIM), 500)
  assert.equal(calculateBalance(entries, KARIM), 50)
})

test('the balance does not depend on the order entries are listed in', () => {
  const entries = [
    addCredit(RAHIM, money(500), AT),
    receivePayment(RAHIM, money(200), AT),
    addCredit(RAHIM, money(100), AT),
  ]
  assert.equal(calculateBalance(entries, RAHIM), calculateBalance([...entries].reverse(), RAHIM))
})

test('the balance of a sale and its reversal is zero', () => {
  const sale = oneLineSale(RAHIM, 7_800)
  assert.equal(calculateBalance([sale.bakiEntry!, reverseSale(sale, AT).bakiEntry!], RAHIM), 0)
})

// --- overdue ---

test('nothing is overdue for a customer with no entries', () => {
  assert.equal(isOverdue([], RAHIM, TODAY), false)
  assert.equal(overdueAmount([], RAHIM, TODAY), ZERO_MONEY)
})

test('overdue is decided on the day given, not on the clock', () => {
  const entries = [addCredit(RAHIM, money(500), AT, '2026-09-20')]
  assert.equal(isOverdue(entries, RAHIM, '2026-09-20'), false)
  assert.equal(isOverdue(entries, RAHIM, '2026-09-21'), true)
})

test('overdue refuses a today that is not a yyyy-MM-dd date', () => {
  assert.throws(() => overdueAmount([], RAHIM, '15/09/2026'), RangeError)
})

test('only the customer asked about can be overdue', () => {
  const entries = [addCredit(RAHIM, money(500), AT, '2026-09-01'), addCredit(KARIM, money(500), AT)]
  assert.equal(isOverdue(entries, RAHIM, TODAY), true)
  assert.equal(isOverdue(entries, KARIM, TODAY), false)
})

test('entries recorded at the same moment are settled in the same order on every phone', () => {
  const early = addCredit(RAHIM, money(300), AT, '2026-09-01', 'a' as EntityId)
  const late = addCredit(RAHIM, money(300), AT, '2026-09-30', 'b' as EntityId)
  const payment = receivePayment(RAHIM, money(300), later(1))

  // Same instant, so the id decides: "a" is settled first, and it is the overdue one.
  assert.equal(overdueAmount([early, late, payment], RAHIM, TODAY), 0)
  assert.equal(overdueAmount([payment, late, early], RAHIM, TODAY), 0)
})

test('working out what is overdue does not reorder the list it was given', () => {
  const entries = [
    addCredit(RAHIM, money(300), later(120), '2026-09-01', 'z' as EntityId),
    addCredit(RAHIM, money(300), later(0), '2026-09-01', 'y' as EntityId),
  ]
  const before = entries.map((e) => e.id)
  overdueAmount(entries, RAHIM, TODAY)
  assert.deepEqual(
    entries.map((e) => e.id),
    before,
  )
})

test('overdue never asks for more than is owed', () => {
  const ledger = [
    addCredit(RAHIM, money(50_000), AT, '2026-09-01'),
    addCredit(RAHIM, money(30_000), later(60), '2026-09-05'),
    receivePayment(RAHIM, money(10_000), later(120)),
  ]
  const overdue = overdueAmount(ledger, RAHIM, TODAY)

  assert.equal(overdue, 70_000)
  assert.ok(overdue <= calculateBalance(ledger, RAHIM))
})

test('everything unpaid always equals the balance, whatever the mix of entries', () => {
  // With every credit past due, what is overdue is exactly what is unpaid,
  // which must be the balance (or nothing, when the customer is ahead). This
  // ties the two rules together: they cannot disagree about the money.
  const sale = oneLineSale(RAHIM, 40_000, '2026-01-01')
  const credit = addCredit(RAHIM, money(25_000), later(60), '2026-02-01')
  const mistake = addCredit(RAHIM, money(9_000), later(120), '2026-03-01')
  const payment = receivePayment(RAHIM, money(15_000), later(180))
  const wrongPayment = receivePayment(RAHIM, money(4_000), later(240))

  const ledgers = [
    [sale.bakiEntry!, credit],
    [sale.bakiEntry!, credit, payment],
    [sale.bakiEntry!, credit, mistake, reverseEntry(mistake, later(300)), payment],
    [sale.bakiEntry!, credit, payment, wrongPayment, reverseEntry(wrongPayment, later(300))],
    [sale.bakiEntry!, reverseSale(sale, later(300)).bakiEntry!, credit],
    [credit, receivePayment(RAHIM, money(99_000), later(400))],
  ]
  ledgers.forEach((ledger, i) => {
    const balance = calculateBalance(ledger, RAHIM)
    assert.equal(overdueAmount(ledger, RAHIM, TODAY), Math.max(balance, 0), `ledger ${i}`)
  })
})
