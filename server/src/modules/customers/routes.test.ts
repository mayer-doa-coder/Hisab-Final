import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { buildApp } from '../../app.js'
import { closePool } from '../../db/pool.js'
import { runMigrations } from '../../db/migrate.js'
import { auth, loginToken, makeCustomer, SHOP_2, type App } from '../../testSupport.js'

// Step 58: the customer endpoints. Same shop-scoping rule as every other
// endpoint (D015) — the shop always comes from the token.
//
// Needs a Postgres to talk to — see README.md, "Run It Yourself".
//
// The test database is kept between runs, so every name and id here is made
// unique per run. A fixed one passes once and then collides with itself.
before(async () => {
  await runMigrations()
})

after(async () => {
  await closePool()
})

/** A name no other run, and no other test in this file, will have used. */
const uniqueName = (label: string) => `${label} ${randomUUID().slice(0, 8)}`

async function create(app: App, token: string, payload: Record<string, unknown>) {
  return app.inject({ method: 'POST', url: '/customers', headers: auth(token), payload })
}

test('every customer endpoint requires a token', async () => {
  const app = buildApp()
  const noAuth = { method: 'GET' as const, url: '/customers' }
  assert.equal((await app.inject(noAuth)).statusCode, 401)
  assert.equal(
    (await app.inject({ method: 'POST', url: '/customers', payload: { name: 'x' } })).statusCode,
    401,
  )
  assert.equal((await app.inject({ method: 'GET', url: '/customers/x' })).statusCode, 401)
})

test('a customer is created under the id sent, and can be read back', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const id = randomUUID()
  const name = uniqueName('Rahim Mia')

  const response = await create(app, token, { id, name, phone: '01711000000' })

  assert.equal(response.statusCode, 201)
  const created = response.json().customer
  assert.equal(created.id, id)
  assert.equal(created.name, name)
  assert.equal(created.phone, '01711000000')
  assert.equal(created.revision, 1)

  const read = await app.inject({ method: 'GET', url: `/customers/${id}`, headers: auth(token) })
  assert.equal(read.statusCode, 200)
  assert.equal(read.json().customer.name, name)
  assert.equal(read.json().balance, 0, "a new customer's balance is a SUM over no rows (D001)")
})

test('a phone number is optional', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const response = await create(app, token, { name: uniqueName('No Phone') })
  assert.equal(response.json().customer.phone, null)
})

test('a name already taken in this shop is answered, not merged and not repeated (D035, D042)', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const name = uniqueName('Karim Uddin')
  await create(app, token, { name })

  const response = await create(app, token, { name: `  ${name.toLowerCase()}  ` })

  assert.equal(response.statusCode, 409)
  assert.equal(response.json().code, 'CUSTOMER_NAME_TAKEN')
  assert.equal(response.json().customer.name, name, 'the existing customer is offered back')
})

test('the same name in two different shops is not a conflict', async () => {
  const app = buildApp()
  const shop1 = await loginToken(app)
  const shop2 = await loginToken(app, SHOP_2)
  const name = uniqueName('Common Name')
  await create(app, shop1, { name })

  const response = await create(app, shop2, { name })
  assert.equal(response.statusCode, 201)
})

test('search finds a customer by part of their name or phone, ignoring case', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const tag = randomUUID().slice(0, 8)
  const wanted = `Findable ${tag}`
  // A phone whose middle really does contain the digits searched for below.
  await create(app, token, { name: wanted, phone: '01712345678' })
  const other = uniqueName('Someone Else')
  await create(app, token, { name: other, phone: '01798765432' })

  const byName = await app.inject({
    method: 'GET',
    url: `/customers?q=findable+${tag}`,
    headers: auth(token),
  })
  const namesFound = byName.json().customers.map((c: { name: string }) => c.name)
  assert.deepEqual(namesFound, [wanted], 'matched on the name, ignoring case')

  const byPhone = await app.inject({
    method: 'GET',
    url: '/customers?q=1234567',
    headers: auth(token),
  })
  const phoneMatches = byPhone.json().customers.map((c: { name: string }) => c.name)
  assert.ok(phoneMatches.includes(wanted), 'matched on part of the phone number')
  assert.ok(!phoneMatches.includes(other), 'and not on a phone that does not contain it')
})

test('reading a customer that does not exist is a 404', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const response = await app.inject({
    method: 'GET',
    url: `/customers/${randomUUID()}`,
    headers: auth(token),
  })
  assert.equal(response.statusCode, 404)
  assert.equal(response.json().code, 'CUSTOMER_NOT_FOUND')
})

test("one shop cannot see, or claim the name of, another shop's customer", async () => {
  const app = buildApp()
  const shop1 = await loginToken(app)
  const id = await makeCustomer(app, shop1, { name: uniqueName('Shop Ones Customer') })

  const shop2 = await loginToken(app, SHOP_2)
  const read = await app.inject({ method: 'GET', url: `/customers/${id}`, headers: auth(shop2) })
  assert.equal(read.statusCode, 404)

  const list = await app.inject({ method: 'GET', url: '/customers', headers: auth(shop2) })
  assert.ok(!list.json().customers.some((c: { id: string }) => c.id === id))
})

test("a shopId in the body is never used — the customer belongs to the token's shop (D015)", async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const name = uniqueName('Ignore Shop Id')

  // The schema declares additionalProperties: false, which Fastify's validator
  // applies by stripping the unknown field rather than refusing the request.
  // Either way the shop can only come from the token, which is what D015 is
  // about — so what this checks is the outcome, not the mechanism.
  const response = await create(app, token, { name, shopId: 'someone-elses-shop' })

  assert.equal(response.statusCode, 201)
  assert.equal(response.json().customer.shopId, 'shop-1', "the token's shop, not the body's")

  const otherShop = await loginToken(app, SHOP_2)
  const read = await app.inject({
    method: 'GET',
    url: `/customers/${response.json().customer.id}`,
    headers: auth(otherShop),
  })
  assert.equal(read.statusCode, 404, 'the shop named in the body cannot see it')
})
