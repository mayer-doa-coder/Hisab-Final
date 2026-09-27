import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { buildApp } from '../../app.js'
import { closePool } from '../../db/pool.js'
import { runMigrations } from '../../db/migrate.js'
import { auth, loginToken, makeCustomer, SHOP_2, type App } from '../../testSupport.js'

// Step 58: the customer endpoints. Same shop-scoping rule as every other
// endpoint (D015) — the shop always comes from the token.
//
// Needs a Postgres to talk to — see README.md, "Run It Yourself".
before(async () => {
  await runMigrations()
})

after(async () => {
  await closePool()
})

async function create(app: App, token: string, payload: Record<string, unknown>) {
  return app.inject({ method: 'POST', url: '/customers', headers: auth(token), payload })
}

test('every customer endpoint requires a token', async () => {
  const app = buildApp()
  const noAuth = { method: 'GET' as const, url: '/customers' }
  assert.equal((await app.inject(noAuth)).statusCode, 401)
  assert.equal((await app.inject({ method: 'POST', url: '/customers', payload: { name: 'x' } })).statusCode, 401)
  assert.equal((await app.inject({ method: 'GET', url: '/customers/x' })).statusCode, 401)
})

test('a customer is created under the id sent, and can be read back', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  const response = await create(app, token, { id: 'c-1', name: 'Rahim Mia', phone: '01711000000' })

  assert.equal(response.statusCode, 201)
  const created = response.json().customer
  assert.equal(created.id, 'c-1')
  assert.equal(created.name, 'Rahim Mia')
  assert.equal(created.phone, '01711000000')
  assert.equal(created.revision, 1)

  const read = await app.inject({ method: 'GET', url: '/customers/c-1', headers: auth(token) })
  assert.equal(read.statusCode, 200)
  assert.equal(read.json().customer.name, 'Rahim Mia')
  assert.equal(read.json().balance, 0, "a new customer's balance is a SUM over no rows (D001)")
})

test('a phone number is optional', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const response = await create(app, token, { name: 'No Phone' })
  assert.equal(response.json().customer.phone, null)
})

test('a name already taken in this shop is answered, not merged and not repeated (D035, D042)', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  await create(app, token, { name: 'Karim Uddin' })

  const response = await create(app, token, { name: '  karim uddin  ' })

  assert.equal(response.statusCode, 409)
  assert.equal(response.json().code, 'CUSTOMER_NAME_TAKEN')
  assert.equal(response.json().customer.name, 'Karim Uddin', 'the existing customer is offered back')
})

test('the same name in two different shops is not a conflict', async () => {
  const app = buildApp()
  const shop1 = await loginToken(app)
  const shop2 = await loginToken(app, SHOP_2)
  await create(app, shop1, { name: 'Common Name' })

  const response = await create(app, shop2, { name: 'Common Name' })
  assert.equal(response.statusCode, 201)
})

test('search finds a customer by part of their name or phone, ignoring case', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  await create(app, token, { name: 'Findable Rahim', phone: '01799999999' })
  await create(app, token, { name: 'Someone Else', phone: '01711111111' })

  const byName = await app.inject({ method: 'GET', url: '/customers?q=findable', headers: auth(token) })
  assert.deepEqual(
    byName.json().customers.map((c: { name: string }) => c.name),
    ['Findable Rahim'],
  )

  const byPhone = await app.inject({ method: 'GET', url: '/customers?q=97999', headers: auth(token) })
  assert.deepEqual(
    byPhone.json().customers.map((c: { name: string }) => c.name),
    ['Findable Rahim'],
  )
})

test('reading a customer that does not exist is a 404', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const response = await app.inject({ method: 'GET', url: '/customers/nope', headers: auth(token) })
  assert.equal(response.statusCode, 404)
  assert.equal(response.json().code, 'CUSTOMER_NOT_FOUND')
})

test("one shop cannot see, or claim the name of, another shop's customer", async () => {
  const app = buildApp()
  const shop1 = await loginToken(app)
  const id = await makeCustomer(app, shop1, { name: 'Shop Ones Customer' })

  const shop2 = await loginToken(app, SHOP_2)
  const read = await app.inject({ method: 'GET', url: `/customers/${id}`, headers: auth(shop2) })
  assert.equal(read.statusCode, 404)

  const list = await app.inject({ method: 'GET', url: '/customers', headers: auth(shop2) })
  assert.ok(!list.json().customers.some((c: { id: string }) => c.id === id))
})

test("a shop_id in the body is never used — the customer belongs to the token's shop (D015)", async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const response = await create(app, token, { name: 'Ignore Shop Id', shopId: 'someone-elses-shop' })
  assert.equal(response.statusCode, 400, 'additionalProperties: false refuses an unknown field outright')
})
