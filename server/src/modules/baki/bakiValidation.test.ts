/** Step 58: what the server accepts as a hand-written baki entry, and what it refuses. */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { addCredit, receivePayment, reverseEntry } from '../../domain/baki.js'
import type { BakiEntry } from '../../domain/bakiEntry.js'
import { generateId, type EntityId } from '../../domain/id.js'
import { money } from '../../domain/money.js'
import { completeCreditSale, reverseSale } from '../../domain/sale.js'
import { quantity } from '../../domain/quantity.js'
import { checkPushedBakiEntry, BAKI_ENTRY_NOT_FOUND, INVALID_PAYLOAD, NOT_REVERSIBLE } from './bakiValidation.js'

const AT = new Date('2026-09-27T10:00:00.000Z')
const RAHIM = 'rahim' as EntityId
const KARIM = 'karim' as EntityId

test('a sound credit or payment is accepted', () => {
  assert.equal(checkPushedBakiEntry(addCredit(RAHIM, money(50_000), AT), null), null)
  assert.equal(checkPushedBakiEntry(receivePayment(RAHIM, money(20_000), AT), null), null)
})

test('a credit must be positive, and a payment must be negative', () => {
  const credit = addCredit(RAHIM, money(50_000), AT)
  const payment = receivePayment(RAHIM, money(20_000), AT)

  assert.equal(checkPushedBakiEntry({ ...credit, amountDelta: money(-50_000) }, null), INVALID_PAYLOAD)
  assert.equal(checkPushedBakiEntry({ ...credit, amountDelta: money(0) }, null), INVALID_PAYLOAD)
  assert.equal(checkPushedBakiEntry({ ...payment, amountDelta: money(20_000) }, null), INVALID_PAYLOAD)
})

test('a credit or payment names no reference — that field is never free text (D041)', () => {
  const credit = addCredit(RAHIM, money(50_000), AT)
  assert.equal(checkPushedBakiEntry({ ...credit, reference: generateId() }, null), INVALID_PAYLOAD)
})

test('a sound undo of a credit or a payment is accepted', () => {
  const credit = addCredit(RAHIM, money(50_000), AT)
  const payment = receivePayment(RAHIM, money(20_000), AT)

  assert.equal(checkPushedBakiEntry(reverseEntry(credit, AT), credit), null)
  assert.equal(checkPushedBakiEntry(reverseEntry(payment, AT), payment), null)
})

test('an undo of an entry the server does not have is refused', () => {
  const undo = reverseEntry(addCredit(RAHIM, money(50_000), AT), AT)
  assert.equal(checkPushedBakiEntry(undo, null), BAKI_ENTRY_NOT_FOUND)
})

test("a credit sale's baki, and a sale reversal's baki, cannot be undone here — they go with their sale (D021)", () => {
  const sale = completeCreditSale('shop', RAHIM, [{ productId: generateId(), quantity: quantity(1000), unitPrice: money(9_000) }], AT)
  const saleReversal = reverseSale(sale, AT)

  // An attempted undo naming either one as its target: sound in every other
  // way (right customer, right amount, right shape), refused only because the
  // thing it names is not a kind this function will undo.
  const undoOf = (target: BakiEntry): BakiEntry => ({
    id: generateId(),
    customerId: target.customerId,
    amountDelta: money(-target.amountDelta),
    type: 'entry_reversal',
    reference: target.id,
    dueDate: null,
    time: target.time,
  })

  assert.equal(checkPushedBakiEntry(undoOf(sale.bakiEntry!), sale.bakiEntry), NOT_REVERSIBLE)
  assert.equal(checkPushedBakiEntry(undoOf(saleReversal.bakiEntry!), saleReversal.bakiEntry), NOT_REVERSIBLE)
})

test('an undo cannot itself be undone', () => {
  const credit = addCredit(RAHIM, money(50_000), AT)
  const undo = reverseEntry(credit, AT)
  const undoUndo: BakiEntry = { ...undo, id: generateId(), reference: undo.id, amountDelta: money(-undo.amountDelta) }

  assert.equal(checkPushedBakiEntry(undoUndo, undo), NOT_REVERSIBLE)
})

test('an undo must exactly cancel the entry it references, for the same customer', () => {
  const credit = addCredit(RAHIM, money(50_000), AT)
  const wrongAmount = { ...reverseEntry(credit, AT), amountDelta: money(-40_000) }
  const wrongCustomer = { ...reverseEntry(credit, AT), customerId: KARIM }

  assert.equal(checkPushedBakiEntry(wrongAmount, credit), INVALID_PAYLOAD)
  assert.equal(checkPushedBakiEntry(wrongCustomer, credit), INVALID_PAYLOAD)
})

test('an undo carries no due date of its own', () => {
  const credit = addCredit(RAHIM, money(50_000), AT)
  const withDueDate = { ...reverseEntry(credit, AT), dueDate: '2026-10-01' }

  assert.equal(checkPushedBakiEntry(withDueDate, credit), INVALID_PAYLOAD)
})

test('an undo must actually name something', () => {
  const credit = addCredit(RAHIM, money(50_000), AT)
  const noReference = { ...reverseEntry(credit, AT), reference: null }

  assert.equal(checkPushedBakiEntry(noReference, credit), INVALID_PAYLOAD)
})

test("credit_sale and reversal cannot arrive standalone — only embedded in a Sale event (D021, D038)", () => {
  const sale = completeCreditSale('shop', RAHIM, [{ productId: generateId(), quantity: quantity(1000), unitPrice: money(9_000) }], AT)
  assert.equal(checkPushedBakiEntry(sale.bakiEntry!, null), INVALID_PAYLOAD)
  assert.equal(checkPushedBakiEntry(reverseSale(sale, AT).bakiEntry!, null), INVALID_PAYLOAD)
})
