import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { buildApp } from '../../app.js'
import { closePool } from '../../db/pool.js'
import { runMigrations } from '../../db/migrate.js'
import { auth, loginToken, makeProduct, SHOP_2, stockOf, type App } from '../../testSupport.js'

// Needs a Postgres to talk to — see README.md, "Run It Yourself".
before(async () => {
  await runMigrations()
})

after(async () => {
  await closePool()
})

async function move(app: App, token: string, payload: Record<string, unknown>) {
  return app.inject({ method: 'POST', url: '/stock/movements', headers: auth(token), payload })
}

test('every stock endpoint requires a token', async () => {
  const app = buildApp()
  for (const [method, url] of [
    ['GET', '/stock'],
    ['GET', `/stock/${randomUUID()}`],
    ['POST', '/stock/movements'],
  ] as const) {
    const response = await app.inject({ method, url, payload: method === 'POST' ? {} : undefined })
    assert.equal(response.statusCode, 401, `${method} ${url}`)
  }
})

test('a product that never moved has no stock', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token)
  assert.equal(await stockOf(app, token, rice), 0)
})

// Step 41 on the server.
test('restock, damage and return each move stock the right way', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token)

  const restocked = await move(app, token, {
    productId: rice,
    type: 'restock',
    quantity: 10_000,
    note: 'Karim Store',
  })
  assert.equal(restocked.statusCode, 201)
  assert.equal(restocked.json().quantity, 10_000)
  assert.equal(restocked.json().movement.sourceReference, 'Karim Store')

  assert.equal(
    (await move(app, token, { productId: rice, type: 'damage', quantity: 1000 })).json().quantity,
    9000,
  )
  assert.equal(
    (await move(app, token, { productId: rice, type: 'return', quantity: 500 })).json().quantity,
    9500,
  )
  assert.equal(await stockOf(app, token, rice), 9500)
})

test('a shelf count records the difference, worked out by the server', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token)
  await move(app, token, { productId: rice, type: 'restock', quantity: 10_000 })

  const counted = await move(app, token, {
    productId: rice,
    type: 'correction',
    countedQuantity: 8500,
  })

  assert.equal(counted.statusCode, 201)
  assert.equal(counted.json().movement.quantityDelta, -1500)
  assert.equal(counted.json().quantity, 8500)
})

test('a sale movement cannot be written on its own', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token)
  const response = await move(app, token, { productId: rice, type: 'sale', quantity: 1000 })
  assert.equal(response.statusCode, 400)
})

test('a restock with no quantity, or a zero one, is refused', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token)

  const missing = await move(app, token, { productId: rice, type: 'restock' })
  assert.equal(missing.statusCode, 400)
  assert.equal(missing.json().code, 'QUANTITY_REQUIRED')

  assert.equal(
    (await move(app, token, { productId: rice, type: 'restock', quantity: 0 })).statusCode,
    400,
  )
  assert.equal(await stockOf(app, token, rice), 0)
})

test('a quantity that is not a whole number of scaled units is refused — never a float (D019)', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token)
  const response = await move(app, token, { productId: rice, type: 'restock', quantity: 2.5 })
  assert.equal(response.statusCode, 400)
})

test('sending the same movement id twice moves stock once (D004)', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token)
  const payload = { id: randomUUID(), productId: rice, type: 'restock', quantity: 3000 }

  assert.equal((await move(app, token, payload)).statusCode, 201)
  assert.equal((await move(app, token, payload)).statusCode, 200)
  assert.equal(await stockOf(app, token, rice), 3000)
})

test('the stock list shows every product with its summed stock', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const name = `Listed ${randomUUID().slice(0, 8)}`
  const rice = await makeProduct(app, token, { name })
  await move(app, token, { productId: rice, type: 'restock', quantity: 4000 })

  const stock = (await app.inject({ method: 'GET', url: '/stock', headers: auth(token) })).json()
    .stock
  const row = stock.find((entry: { productId: string }) => entry.productId === rice)
  assert.equal(row.quantity, 4000)
  assert.equal(row.name, name)
})

// Step 46's check: the same shop-scoping rule as Product (Step 30).
test("one shop cannot see or move another shop's stock", async () => {
  const app = buildApp()
  const shop1 = await loginToken(app)
  const shop2 = await loginToken(app, SHOP_2)
  const rice = await makeProduct(app, shop1)
  await move(app, shop1, { productId: rice, type: 'restock', quantity: 5000 })

  assert.equal(
    (await app.inject({ method: 'GET', url: `/stock/${rice}`, headers: auth(shop2) })).statusCode,
    404,
  )
  assert.equal(
    (await move(app, shop2, { productId: rice, type: 'damage', quantity: 5000 })).statusCode,
    404,
  )

  const listed = (await app.inject({ method: 'GET', url: '/stock', headers: auth(shop2) })).json()
    .stock
  assert.ok(!listed.some((entry: { productId: string }) => entry.productId === rice))

  assert.equal(await stockOf(app, shop1, rice), 5000, "shop 1's stock was untouched")
})

test("a movement id already used by another shop is refused, and shop 1's row is not revealed", async () => {
  const app = buildApp()
  const shop1 = await loginToken(app)
  const shop2 = await loginToken(app, SHOP_2)
  const id = randomUUID()
  const product1 = await makeProduct(app, shop1)
  const product2 = await makeProduct(app, shop2)

  assert.equal(
    (await move(app, shop1, { id, productId: product1, type: 'restock', quantity: 1000 }))
      .statusCode,
    201,
  )
  const clash = await move(app, shop2, { id, productId: product2, type: 'restock', quantity: 1000 })

  assert.equal(clash.statusCode, 409)
  assert.equal(clash.json().code, 'ID_TAKEN')
  assert.equal(clash.json().movement, undefined)
  assert.equal(await stockOf(app, shop2, product2), 0)
})
