import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { buildApp } from '../../app.js'
import { closePool } from '../../db/pool.js'
import { runMigrations } from '../../db/migrate.js'
import {
  auth,
  loginToken,
  makeProduct,
  restock,
  SHOP_2,
  stockOf,
  type App,
} from '../../testSupport.js'

// Needs a Postgres to talk to — see README.md, "Run It Yourself".
before(async () => {
  await runMigrations()
})

after(async () => {
  await closePool()
})

async function sell(app: App, token: string, payload: Record<string, unknown>) {
  return app.inject({ method: 'POST', url: '/sales', headers: auth(token), payload })
}

async function reverse(
  app: App,
  token: string,
  saleId: string,
  payload: Record<string, unknown> = {},
) {
  return app.inject({
    method: 'POST',
    url: `/sales/${saleId}/reversal`,
    headers: auth(token),
    payload,
  })
}

async function balanceOf(customerId: string): Promise<number> {
  const { getPool } = await import('../../db/pool.js')
  const { rows } = await getPool().query<{ owed: string }>(
    'SELECT COALESCE(SUM(amount_delta_poisha), 0) AS owed FROM baki_entry WHERE customer_id = $1',
    [customerId],
  )
  return Number(rows[0]!.owed)
}

test('every sale endpoint requires a token', async () => {
  const app = buildApp()
  for (const [method, url] of [
    ['POST', '/sales'],
    ['GET', '/sales'],
    ['GET', `/sales/${randomUUID()}`],
    ['POST', `/sales/${randomUUID()}/reversal`],
  ] as const) {
    const response = await app.inject({ method, url, payload: method === 'POST' ? {} : undefined })
    assert.equal(response.statusCode, 401, `${method} ${url}`)
  }
})

// Step 39 on the server.
test('a cash sale is stored whole, and its stock leaves', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token, { sellingPricePoisha: 5000 })
  const oil = await makeProduct(app, token, { sellingPricePoisha: 2500 })
  await restock(app, token, rice, 10_000)
  await restock(app, token, oil, 10_000)

  const response = await sell(app, token, {
    payment: 'cash',
    lines: [
      { productId: rice, quantity: 3000 },
      { productId: oil, quantity: 2000 },
    ],
  })

  assert.equal(response.statusCode, 201)
  const { sale } = response.json()
  assert.equal(sale.sale.total, 20_000, 'the Step 37 fixture: 3 at 50 plus 2 at 25')
  assert.equal(sale.sale.payment, 'cash')
  assert.equal(sale.items.length, 2)
  assert.equal(sale.stockMovements.length, 2)
  assert.equal(sale.bakiEntry, null)
  assert.ok(sale.sale.time.serverReceivedAt, 'the server records when it received the sale')

  assert.equal(await stockOf(app, token, rice), 7000)
  assert.equal(await stockOf(app, token, oil), 8000)
})

test('the server works out the total — a total sent by the caller is thrown away', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token, { sellingPricePoisha: 5000 })

  const response = await sell(app, token, {
    payment: 'cash',
    total: 1,
    lines: [{ productId: rice, quantity: 1000 }],
  })

  assert.equal(response.statusCode, 201)
  assert.equal(
    response.json().sale.sale.total,
    5000,
    'one piece at 50 taka, whatever the caller claimed',
  )
})

test('a line can be sold below the product price, and the total follows', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token, { sellingPricePoisha: 5000 })

  const response = await sell(app, token, {
    payment: 'cash',
    lines: [{ productId: rice, quantity: 1250, unitPricePoisha: 9050 }],
  })
  // The fixture's rounding case: 1.25 kg at 90.50 taka lands on half a poisha.
  assert.equal(response.json().sale.sale.total, 11_313)
})

// Step 40 on the server.
test('a credit sale creates the customer once and owes exactly its total', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token, { sellingPricePoisha: 5000 })
  const name = `Rahim ${randomUUID().slice(0, 8)}`

  const first = (
    await sell(app, token, {
      payment: 'credit',
      customerName: name,
      dueDate: '2026-10-15',
      lines: [{ productId: rice, quantity: 2000 }],
    })
  ).json().sale
  const second = (
    await sell(app, token, {
      payment: 'credit',
      customerName: `  ${name.toUpperCase()} `,
      lines: [{ productId: rice, quantity: 1000 }],
    })
  ).json().sale

  assert.equal(first.bakiEntry.amountDelta, 10_000)
  assert.equal(first.bakiEntry.dueDate, '2026-10-15')
  assert.equal(first.bakiEntry.type, 'credit_sale')
  assert.equal(second.sale.customerId, first.sale.customerId, 'the same name is the same customer')
  assert.equal(await balanceOf(first.sale.customerId), 15_000)
})

test('a credit sale without a customer is refused', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token)

  const response = await sell(app, token, {
    payment: 'credit',
    lines: [{ productId: rice, quantity: 1000 }],
  })
  assert.equal(response.statusCode, 400)
  assert.equal(response.json().code, 'CUSTOMER_REQUIRED')
})

test('a sale of a product this shop does not have is refused, and nothing is written', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token)
  const id = randomUUID()

  const response = await sell(app, token, {
    id,
    payment: 'cash',
    lines: [
      { productId: rice, quantity: 1000 },
      { productId: randomUUID(), quantity: 1000 },
    ],
  })
  assert.equal(response.statusCode, 404)
  assert.equal(response.json().code, 'PRODUCT_NOT_FOUND')
  assert.equal(
    (await app.inject({ method: 'GET', url: `/sales/${id}`, headers: auth(token) })).statusCode,
    404,
  )
  assert.equal(await stockOf(app, token, rice), 0, 'no half-written sale moved stock')
})

test('the same product twice in one sale is refused', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token)

  const response = await sell(app, token, {
    payment: 'cash',
    lines: [
      { productId: rice, quantity: 1000 },
      { productId: rice, quantity: 2000 },
    ],
  })
  assert.equal(response.statusCode, 400)
  assert.equal(response.json().code, 'INVALID_SALE')
})

test('sending the same sale twice stores it once (D004)', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token)
  await restock(app, token, rice, 5000)
  const payload = {
    id: randomUUID(),
    payment: 'cash',
    lines: [{ productId: rice, quantity: 1000 }],
  }

  assert.equal((await sell(app, token, payload)).statusCode, 201)
  assert.equal((await sell(app, token, payload)).statusCode, 200)
  assert.equal(await stockOf(app, token, rice), 4000, 'stock left once, not twice')
})

// Step 43 on the server: reversing a cash sale restores stock.
test('reversing a cash sale restores stock and nets the money to zero', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token, { sellingPricePoisha: 5000 })
  await restock(app, token, rice, 10_000)
  const sale = (
    await sell(app, token, { payment: 'cash', lines: [{ productId: rice, quantity: 3000 }] })
  ).json().sale
  assert.equal(await stockOf(app, token, rice), 7000)

  const response = await reverse(app, token, sale.sale.id)

  assert.equal(response.statusCode, 201)
  const reversal = response.json().sale
  assert.equal(reversal.sale.reversesSaleId, sale.sale.id)
  assert.equal(reversal.sale.total, -15_000)
  assert.ok(reversal.stockMovements.every((m: { type: string }) => m.type === 'return'))
  assert.equal(await stockOf(app, token, rice), 10_000, 'stock is back where it started')

  const original = (
    await app.inject({ method: 'GET', url: `/sales/${sale.sale.id}`, headers: auth(token) })
  ).json()
  assert.equal(original.reversedBy, reversal.sale.id, 'the original stays, and says what undid it')
})

// Step 44 on the server: all three move together.
test('reversing a credit sale restores stock and clears the baki, together (D021)', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token, { sellingPricePoisha: 5000 })
  await restock(app, token, rice, 10_000)
  const sale = (
    await sell(app, token, {
      payment: 'credit',
      customerName: `Karim ${randomUUID().slice(0, 8)}`,
      lines: [{ productId: rice, quantity: 2000 }],
    })
  ).json().sale
  assert.equal(await balanceOf(sale.sale.customerId), 10_000)

  const reversal = (await reverse(app, token, sale.sale.id)).json().sale

  assert.equal(reversal.bakiEntry.type, 'reversal')
  assert.equal(reversal.bakiEntry.amountDelta, -10_000)
  assert.equal(await stockOf(app, token, rice), 10_000)
  assert.equal(await balanceOf(sale.sale.customerId), 0)
})

test('a sale can only be reversed once', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token)
  await restock(app, token, rice, 5000)
  const sale = (
    await sell(app, token, { payment: 'cash', lines: [{ productId: rice, quantity: 1000 }] })
  ).json().sale

  const first = await reverse(app, token, sale.sale.id)
  const second = await reverse(app, token, sale.sale.id)

  assert.equal(first.statusCode, 201)
  assert.equal(second.statusCode, 409)
  assert.equal(second.json().code, 'ALREADY_REVERSED')
  assert.equal(await stockOf(app, token, rice), 5000, 'stock came back once')
})

test('retrying the same reversal id is answered with the reversal, not refused', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token)
  const sale = (
    await sell(app, token, { payment: 'cash', lines: [{ productId: rice, quantity: 1000 }] })
  ).json().sale
  const id = randomUUID()

  assert.equal((await reverse(app, token, sale.sale.id, { id })).statusCode, 201)
  const retried = await reverse(app, token, sale.sale.id, { id })
  assert.equal(retried.statusCode, 200)
  assert.equal(retried.json().sale.sale.id, id)
})

test('a reversal cannot be reversed', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token)
  const sale = (
    await sell(app, token, { payment: 'cash', lines: [{ productId: rice, quantity: 1000 }] })
  ).json().sale
  const reversal = (await reverse(app, token, sale.sale.id)).json().sale

  const response = await reverse(app, token, reversal.sale.id)
  assert.equal(response.statusCode, 409)
  assert.equal(response.json().code, 'CANNOT_REVERSE_A_REVERSAL')
})

test('reversing a sale that does not exist is a 404', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const response = await reverse(app, token, randomUUID())
  assert.equal(response.statusCode, 404)
  assert.equal(response.json().code, 'SALE_NOT_FOUND')
})

test('the sale list is newest first and includes reversals', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const rice = await makeProduct(app, token)
  const older = (
    await sell(app, token, {
      payment: 'cash',
      occurredAt: '2026-01-01T08:00:00.000Z',
      lines: [{ productId: rice, quantity: 1000 }],
    })
  ).json().sale
  const newer = (
    await sell(app, token, {
      payment: 'cash',
      occurredAt: '2026-01-01T09:00:00.000Z',
      lines: [{ productId: rice, quantity: 1000 }],
    })
  ).json().sale

  const sales = (
    await app.inject({ method: 'GET', url: '/sales?limit=500', headers: auth(token) })
  ).json().sales
  const ids = sales.map((s: { sale: { id: string } }) => s.sale.id)
  assert.ok(ids.indexOf(newer.sale.id) < ids.indexOf(older.sale.id))
  assert.equal(
    newer.sale.time.occurredAt,
    '2026-01-01T09:00:00.000Z',
    'when it happened is what the caller said',
  )
})

// Step 46's check: the same shop-scoping rule as Product (Step 30).
test("one shop cannot see, reverse, or sell another shop's products or sales", async () => {
  const app = buildApp()
  const shop1 = await loginToken(app)
  const shop2 = await loginToken(app, SHOP_2)
  const rice = await makeProduct(app, shop1)
  await restock(app, shop1, rice, 5000)
  const sale = (
    await sell(app, shop1, { payment: 'cash', lines: [{ productId: rice, quantity: 1000 }] })
  ).json().sale

  const seen = await app.inject({
    method: 'GET',
    url: `/sales/${sale.sale.id}`,
    headers: auth(shop2),
  })
  assert.equal(seen.statusCode, 404, 'not found, rather than forbidden — no hint the sale exists')

  const listed = (
    await app.inject({ method: 'GET', url: '/sales?limit=500', headers: auth(shop2) })
  ).json().sales
  assert.ok(!listed.some((s: { sale: { id: string } }) => s.sale.id === sale.sale.id))

  assert.equal((await reverse(app, shop2, sale.sale.id)).statusCode, 404)

  const sold = await sell(app, shop2, {
    payment: 'cash',
    lines: [{ productId: rice, quantity: 1000 }],
  })
  assert.equal(sold.statusCode, 404, "shop 2 cannot sell shop 1's product")

  assert.equal(await stockOf(app, shop1, rice), 4000, "shop 1's stock was untouched by shop 2")
})

test("a shop_id in the body is never used — the sale belongs to the token's shop (D015)", async () => {
  const app = buildApp()
  const shop1 = await loginToken(app)
  const shop2 = await loginToken(app, SHOP_2)
  const product = await makeProduct(app, shop2)

  const response = await sell(app, shop2, {
    shop_id: 'shop-1',
    shopId: 'shop-1',
    payment: 'cash',
    lines: [{ productId: product, quantity: 1000 }],
  })
  assert.equal(response.statusCode, 201)
  const id = response.json().sale.sale.id

  assert.equal(response.json().sale.sale.shopId, 'shop-2')
  assert.equal(
    (await app.inject({ method: 'GET', url: `/sales/${id}`, headers: auth(shop2) })).statusCode,
    200,
  )
  assert.equal(
    (await app.inject({ method: 'GET', url: `/sales/${id}`, headers: auth(shop1) })).statusCode,
    404,
    'shop 1 never received it',
  )
})
