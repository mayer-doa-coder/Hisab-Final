import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { buildApp } from '../../app.js'
import { closePool } from '../../db/pool.js'
import { runMigrations } from '../../db/migrate.js'
import {
  auth,
  loginToken,
  makeCustomer,
  makeProduct,
  restock,
  SHOP_2,
  type App,
} from '../../testSupport.js'

// Step 58: adding baki by hand, receiving a payment, undoing either, and a
// customer's ledger. Same shop-scoping rule as every other endpoint (D015).
//
// Needs a Postgres to talk to — see README.md, "Run It Yourself".
before(async () => {
  await runMigrations()
})

after(async () => {
  await closePool()
})

async function addEntry(app: App, token: string, payload: Record<string, unknown>) {
  return app.inject({ method: 'POST', url: '/baki/entries', headers: auth(token), payload })
}

async function reverseEntryReq(
  app: App,
  token: string,
  entryId: string,
  payload: Record<string, unknown> = {},
) {
  return app.inject({
    method: 'POST',
    url: `/baki/entries/${entryId}/reversal`,
    headers: auth(token),
    payload,
  })
}

async function ledgerOf(app: App, token: string, customerId: string) {
  return app.inject({ method: 'GET', url: `/customers/${customerId}/baki`, headers: auth(token) })
}

test('every baki endpoint requires a token', async () => {
  const app = buildApp()
  assert.equal(
    (await app.inject({ method: 'POST', url: '/baki/entries', payload: {} })).statusCode,
    401,
  )
  assert.equal(
    (await app.inject({ method: 'POST', url: '/baki/entries/x/reversal', payload: {} })).statusCode,
    401,
  )
  assert.equal((await app.inject({ method: 'GET', url: '/customers/x/baki' })).statusCode, 401)
})

// The plan's own numbers (Step 56, now over the API too): 500 in baki, 200
// received, 100 more added — the balance is exactly 400.
test('credit 500, payment 200, credit 100 gives exactly 400', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const customerId = await makeCustomer(app, token)

  await addEntry(app, token, { customerId, type: 'credit', amountPoisha: 50_000 })
  await addEntry(app, token, { customerId, type: 'payment', amountPoisha: 20_000 })
  const last = await addEntry(app, token, { customerId, type: 'credit', amountPoisha: 10_000 })

  assert.equal(last.statusCode, 201)
  assert.equal(last.json().balance, 40_000)

  const ledger = await ledgerOf(app, token, customerId)
  assert.equal(ledger.json().balance, 40_000)
  assert.equal(ledger.json().entries.length, 3)
})

test('a due date travels with a credit', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const customerId = await makeCustomer(app, token)

  const response = await addEntry(app, token, {
    customerId,
    type: 'credit',
    amountPoisha: 50_000,
    dueDate: '2026-10-15',
  })

  assert.equal(response.json().entry.dueDate, '2026-10-15')
})

test('adding baki for a customer this shop does not have is a 404, and nothing is written', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  const response = await addEntry(app, token, {
    customerId: 'nobody',
    type: 'credit',
    amountPoisha: 50_000,
  })

  assert.equal(response.statusCode, 404)
  assert.equal(response.json().code, 'CUSTOMER_NOT_FOUND')
})

test('sending the same entry id twice stores it once (D004)', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const customerId = await makeCustomer(app, token)
  const payload = { id: randomUUID(), customerId, type: 'credit', amountPoisha: 50_000 }

  const first = await addEntry(app, token, payload)
  const second = await addEntry(app, token, payload)

  assert.equal(first.statusCode, 201)
  assert.equal(second.statusCode, 200)
  const ledger = await ledgerOf(app, token, customerId)
  assert.equal(ledger.json().balance, 50_000, 'stored once')
})

test('undoing a credit writes the opposite entry, and the balance is right again', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const customerId = await makeCustomer(app, token)
  const credit = (
    await addEntry(app, token, { customerId, type: 'credit', amountPoisha: 50_000 })
  ).json().entry

  const response = await reverseEntryReq(app, token, credit.id)

  assert.equal(response.statusCode, 201)
  assert.equal(response.json().entry.type, 'entry_reversal')
  assert.equal(response.json().entry.reference, credit.id)
  assert.equal(response.json().balance, 0)

  const ledger = await ledgerOf(app, token, customerId)
  assert.equal(ledger.json().entries.length, 2, 'both the entry and its undo are history')
})

test('undoing a payment restores what was owed', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const customerId = await makeCustomer(app, token)
  await addEntry(app, token, { customerId, type: 'credit', amountPoisha: 50_000 })
  const payment = (
    await addEntry(app, token, { customerId, type: 'payment', amountPoisha: 20_000 })
  ).json().entry

  const response = await reverseEntryReq(app, token, payment.id)

  assert.equal(response.json().entry.amountDelta, 20_000)
  assert.equal(response.json().balance, 50_000)
})

test('an entry can only be undone once, even from two racing requests', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const customerId = await makeCustomer(app, token)
  const credit = (
    await addEntry(app, token, { customerId, type: 'credit', amountPoisha: 50_000 })
  ).json().entry

  const [first, second] = await Promise.all([
    reverseEntryReq(app, token, credit.id, { id: randomUUID() }),
    reverseEntryReq(app, token, credit.id, { id: randomUUID() }),
  ])
  const statuses = [first.statusCode, second.statusCode].sort()

  assert.deepEqual(statuses, [201, 409])
  const conflict = first.statusCode === 409 ? first : second
  assert.equal(conflict.json().code, 'ALREADY_REVERSED')
  const ledger = await ledgerOf(app, token, customerId)
  assert.equal(ledger.json().entries.length, 2, 'exactly one undo was written')
})

test('undoing an entry that does not exist is a 404', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const response = await reverseEntryReq(app, token, 'nope')
  assert.equal(response.statusCode, 404)
  assert.equal(response.json().code, 'BAKI_ENTRY_NOT_FOUND')
})

test('an undo cannot itself be undone', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const customerId = await makeCustomer(app, token)
  const credit = (
    await addEntry(app, token, { customerId, type: 'credit', amountPoisha: 50_000 })
  ).json().entry
  const undo = (await reverseEntryReq(app, token, credit.id)).json().entry

  const response = await reverseEntryReq(app, token, undo.id)

  assert.equal(response.statusCode, 409)
  assert.equal(response.json().code, 'NOT_REVERSIBLE')
})

test("a credit sale's baki entry cannot be undone through this endpoint — it goes with its sale (D021)", async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const productId = await makeProduct(app, token, { sellingPricePoisha: 5000 })
  await restock(app, token, productId, 10_000)
  const sale = (
    await app.inject({
      method: 'POST',
      url: '/sales',
      headers: auth(token),
      payload: { payment: 'credit', customerName: 'Rahim', lines: [{ productId, quantity: 1000 }] },
    })
  ).json().sale
  assert.ok(sale.bakiEntry, 'the sale has a baki entry to try reversing')

  const response = await reverseEntryReq(app, token, sale.bakiEntry.id)

  assert.equal(response.statusCode, 409)
  assert.equal(response.json().code, 'NOT_REVERSIBLE')
})

test("one shop cannot add baki to, read the ledger of, or undo an entry for another shop's customer", async () => {
  const app = buildApp()
  const shop1 = await loginToken(app)
  const customerId = await makeCustomer(app, shop1)
  const credit = (
    await addEntry(app, shop1, { customerId, type: 'credit', amountPoisha: 50_000 })
  ).json().entry

  const shop2 = await loginToken(app, SHOP_2)

  const added = await addEntry(app, shop2, { customerId, type: 'credit', amountPoisha: 1 })
  assert.equal(added.statusCode, 404)

  const read = await ledgerOf(app, shop2, customerId)
  assert.equal(read.statusCode, 404)

  const reversed = await reverseEntryReq(app, shop2, credit.id)
  assert.equal(reversed.statusCode, 404)

  assert.equal((await ledgerOf(app, shop1, customerId)).json().balance, 50_000, 'untouched')
})
