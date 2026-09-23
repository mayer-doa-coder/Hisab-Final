import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { buildApp } from '../../app.js'
import { closePool, getPool } from '../../db/pool.js'
import { runMigrations } from '../../db/migrate.js'
import { generateId, type EntityId } from '../../domain/id.js'
import { money } from '../../domain/money.js'
import { quantity } from '../../domain/quantity.js'
import {
  completeCashSale,
  completeCreditSale,
  reverseSale,
  type SaleTransaction,
} from '../../domain/sale.js'
import { restock, sell as sellMovement } from '../../domain/stock.js'
import { auth, loginToken, makeProduct, SHOP_2, stockOf, type App } from '../../testSupport.js'
import { changesSince, isProcessed } from './syncService.js'

// Steps 43–47 on the server's side of sync: a sale, its reversal, a restock
// and a customer made on a phone — offline, in the shape `domain/` builds —
// pushed here, and pulled back by another device.
//
// Needs a Postgres to talk to — see README.md, "Run It Yourself".
before(async () => {
  await runMigrations()
})

after(async () => {
  await closePool()
})

const AT = new Date('2026-09-17T10:00:00.000Z')

/** The same envelope a phone's outbox sends (D026). */
function envelope(entityType: string, entityId: string, payload: unknown, operation = 'create') {
  return {
    eventId: randomUUID(),
    entityType,
    entityId,
    operation,
    payload: JSON.parse(JSON.stringify(payload)),
    baseRevision: null,
    clientTimestamp: AT.toISOString(),
  }
}

const customerEvent = (id: string, name: string) => envelope('Customer', id, { name, phone: null })
const saleEvent = (transaction: SaleTransaction) =>
  envelope('Sale', transaction.sale.id, transaction)
const restockEvent = (productId: string, amount: number) => {
  const movement = restock(productId as EntityId, quantity(amount), AT)
  return envelope('StockMovement', movement.id, movement)
}

async function push(app: App, token: string, events: unknown[]) {
  const response = await app.inject({
    method: 'POST',
    url: '/sync/push',
    headers: auth(token),
    payload: { events },
  })
  assert.equal(response.statusCode, 200, response.body)
  return response.json().results as Array<{ eventId: string; status: string; code?: string }>
}

/** Every change after a cursor, following pages to the end. */
async function pullAll(app: App, token: string, after?: number) {
  const events: Array<ReturnType<typeof JSON.parse>> = []
  let cursor = after
  for (;;) {
    const query = cursor === undefined ? '' : `?after=${cursor}`
    const page = (
      await app.inject({ method: 'GET', url: `/sync/changes${query}`, headers: auth(token) })
    ).json()
    events.push(...page.events)
    if (page.events.length === 0 || page.cursor === cursor) return { events, cursor: page.cursor }
    cursor = page.cursor
  }
}

async function balanceOf(customerId: string): Promise<number> {
  const { rows } = await getPool().query<{ owed: string }>(
    'SELECT COALESCE(SUM(amount_delta_poisha), 0) AS owed FROM baki_entry WHERE customer_id = $1',
    [customerId],
  )
  return Number(rows[0]!.owed)
}

/** A shop with one product in stock, ready to sell from. */
async function shopWithStock(app: App, account = undefined as typeof SHOP_2 | undefined) {
  const token = await loginToken(app, account)
  const rice = await makeProduct(app, token, { sellingPricePoisha: 5000 })
  const results = await push(app, token, [restockEvent(rice, 10_000)])
  assert.equal(results[0]!.status, 'applied')
  return { token, rice: rice as EntityId }
}

const riceLine = (rice: EntityId, amount = 3000) => [
  { productId: rice, quantity: quantity(amount), unitPrice: money(5000) },
]

// --- Push (Steps 39–41 made offline, then synced) ---

test('a restock pushed from a phone moves the server stock', async () => {
  const app = buildApp()
  const { token, rice } = await shopWithStock(app)
  assert.equal(await stockOf(app, token, rice), 10_000)
})

test('a cash sale pushed from a phone is stored whole and takes its stock', async () => {
  const app = buildApp()
  const { token, rice } = await shopWithStock(app)
  const sale = completeCashSale('shop-1', riceLine(rice), AT)

  const [result] = await push(app, token, [saleEvent(sale)])

  assert.equal(result!.status, 'applied')
  const stored = (
    await app.inject({ method: 'GET', url: `/sales/${sale.sale.id}`, headers: auth(token) })
  ).json().sale
  assert.equal(stored.sale.total, 15_000)
  assert.equal(
    stored.sale.time.occurredAt,
    AT.toISOString(),
    'when it happened is what the phone said (D019)',
  )
  assert.ok(stored.sale.time.serverReceivedAt, 'when the server got it is the server’s own clock')
  assert.equal(stored.items.length, 1)
  assert.equal(stored.stockMovements.length, 1)
  assert.equal(await stockOf(app, token, rice), 7000)
})

test('a customer and their credit sale pushed in one batch owe exactly the total', async () => {
  const app = buildApp()
  const { token, rice } = await shopWithStock(app)
  const customer = generateId()
  const sale = completeCreditSale('shop-1', customer, riceLine(rice, 2000), AT, '2026-10-15')

  const results = await push(app, token, [customerEvent(customer, 'রহিম'), saleEvent(sale)])

  assert.deepEqual(
    results.map((r) => r.status),
    ['applied', 'applied'],
  )
  assert.equal(await balanceOf(customer), 10_000)
  assert.equal(await stockOf(app, token, rice), 8000)
})

test('the same sale event pushed twice is applied once (D004)', async () => {
  const app = buildApp()
  const { token, rice } = await shopWithStock(app)
  const event = saleEvent(completeCashSale('shop-1', riceLine(rice), AT))

  const [first] = await push(app, token, [event])
  const [second] = await push(app, token, [event])

  assert.equal(first!.status, 'applied')
  assert.equal(second!.status, 'already-applied')
  assert.equal(await stockOf(app, token, rice), 7000, 'stock left once')
})

test('the same sale under a new event id is recognised as the same sale, not a second one', async () => {
  const app = buildApp()
  const { token, rice } = await shopWithStock(app)
  const sale = completeCashSale('shop-1', riceLine(rice), AT)

  await push(app, token, [saleEvent(sale)])
  const [again] = await push(app, token, [saleEvent(sale)])

  assert.equal(again!.status, 'applied')
  assert.equal(await stockOf(app, token, rice), 7000)
})

test('a sale whose total was tampered with is refused, and not remembered as applied', async () => {
  const app = buildApp()
  const { token, rice } = await shopWithStock(app)
  const event = saleEvent(completeCashSale('shop-1', riceLine(rice), AT))
  event.payload.sale.total = 1

  const [result] = await push(app, token, [event])

  assert.equal(result!.status, 'rejected')
  assert.equal(result!.code, 'INVALID_PAYLOAD')
  assert.equal(
    await isProcessed(event.eventId),
    false,
    'the claim was rolled back with the refusal',
  )
  assert.equal(await stockOf(app, token, rice), 10_000, 'nothing was written')
})

test('a stock movement that only a sale may make is refused on its own', async () => {
  const app = buildApp()
  const { token, rice } = await shopWithStock(app)
  const movement = sellMovement(rice, quantity(1000), generateId(), AT)

  const [result] = await push(app, token, [envelope('StockMovement', movement.id, movement)])

  assert.equal(result!.status, 'rejected')
  assert.equal(result!.code, 'INVALID_PAYLOAD')
})

test('a sale cannot be edited or deleted through sync — history is never rewritten', async () => {
  const app = buildApp()
  const { token, rice } = await shopWithStock(app)
  const sale = completeCashSale('shop-1', riceLine(rice), AT)
  await push(app, token, [saleEvent(sale)])

  const results = await push(app, token, [
    envelope('Sale', sale.sale.id, sale, 'update'),
    envelope('Sale', sale.sale.id, sale, 'delete'),
  ])

  assert.deepEqual(
    results.map((r) => r.code),
    ['UNSUPPORTED_OPERATION', 'UNSUPPORTED_OPERATION'],
  )
})

// --- Reversal (Steps 43–44) ---

test('a pushed credit-sale reversal restores stock and clears the baki together (D021)', async () => {
  const app = buildApp()
  const { token, rice } = await shopWithStock(app)
  const customer = generateId()
  const sale = completeCreditSale('shop-1', customer, riceLine(rice, 2000), AT)
  await push(app, token, [customerEvent(customer, 'করিম'), saleEvent(sale)])
  assert.equal(await balanceOf(customer), 10_000)

  const [result] = await push(app, token, [saleEvent(reverseSale(sale, AT))])

  assert.equal(result!.status, 'applied')
  assert.equal(await stockOf(app, token, rice), 10_000)
  assert.equal(await balanceOf(customer), 0)
})

test('a second device reversing an already-reversed sale is refused', async () => {
  const app = buildApp()
  const { token, rice } = await shopWithStock(app)
  const sale = completeCashSale('shop-1', riceLine(rice), AT)
  await push(app, token, [saleEvent(sale)])

  const [first] = await push(app, token, [saleEvent(reverseSale(sale, AT))])
  const [second] = await push(app, token, [saleEvent(reverseSale(sale, AT))])

  assert.equal(first!.status, 'applied')
  assert.equal(second!.status, 'rejected')
  assert.equal(second!.code, 'ALREADY_REVERSED')
  assert.equal(await stockOf(app, token, rice), 10_000, 'stock came back once')
})

test('a reversal of a sale the server never received is refused', async () => {
  const app = buildApp()
  const { token, rice } = await shopWithStock(app)
  const sale = completeCashSale('shop-1', riceLine(rice), AT)

  const [result] = await push(app, token, [saleEvent(reverseSale(sale, AT))])

  assert.equal(result!.status, 'rejected')
  assert.equal(result!.code, 'UNKNOWN_ENTITY')
})

test('a reversal that gives back more stock than was sold is refused', async () => {
  const app = buildApp()
  const { token, rice } = await shopWithStock(app)
  const sale = completeCashSale('shop-1', riceLine(rice), AT)
  await push(app, token, [saleEvent(sale)])
  const event = saleEvent(reverseSale(sale, AT))
  event.payload.stockMovements[0].quantityDelta = 9000

  const [result] = await push(app, token, [event])

  assert.equal(result!.code, 'INVALID_PAYLOAD')
  assert.equal(await stockOf(app, token, rice), 7000)
})

// --- Shops stay apart (D015) ---

test("one shop cannot push baki onto another shop's customer", async () => {
  const app = buildApp()
  const shop1 = await loginToken(app)
  const customer = generateId()
  await push(app, shop1, [customerEvent(customer, 'Shop one customer')])

  const { token: shop2, rice } = await shopWithStock(app, SHOP_2)
  const sale = completeCreditSale('shop-2', customer, riceLine(rice), AT)
  const [result] = await push(app, shop2, [saleEvent(sale)])

  assert.equal(result!.status, 'rejected')
  assert.equal(await balanceOf(customer), 0, "shop 1's customer owes nothing")
  assert.equal(await stockOf(app, shop2, rice), 10_000)
})

test("one shop cannot reverse another shop's sale", async () => {
  const app = buildApp()
  const { token: shop1, rice } = await shopWithStock(app)
  const sale = completeCashSale('shop-1', riceLine(rice), AT)
  await push(app, shop1, [saleEvent(sale)])

  const shop2 = await loginToken(app, SHOP_2)
  const [result] = await push(app, shop2, [saleEvent(reverseSale(sale, AT))])

  assert.equal(result!.code, 'UNKNOWN_ENTITY')
  assert.equal(await stockOf(app, shop1, rice), 7000)
})

// --- Pull (Step 47) ---

test('a pull returns the customer, the restock and the whole sale, in the order they happened', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const before = (await pullAll(app, token)).cursor
  const rice = (await makeProduct(app, token)) as EntityId
  const restockOne = restockEvent(rice, 10_000)
  const customer = generateId()
  const sale = completeCreditSale('shop-1', customer, riceLine(rice), AT)
  await push(app, token, [restockOne, customerEvent(customer, 'রহিম'), saleEvent(sale)])

  const { events } = await pullAll(app, token, before)
  const mine = events.filter((e) =>
    [rice, restockOne.entityId, customer, sale.sale.id].includes(e.entityId),
  )

  assert.deepEqual(
    mine.map((e) => e.entityType),
    ['Product', 'StockMovement', 'Customer', 'Sale'],
  )
  const pulledSale = mine[3]!.payload
  assert.equal(pulledSale.items.length, 1)
  assert.equal(pulledSale.stockMovements.length, 1)
  assert.equal(pulledSale.bakiEntry.amountDelta, 15_000)
  assert.ok(pulledSale.sale.time.serverReceivedAt, 'a device learns the sale reached the server')
})

test("a sale's own stock movements never arrive on their own — a sale is never split", async () => {
  const app = buildApp()
  const { token, rice } = await shopWithStock(app)
  const before = (await pullAll(app, token)).cursor
  const sale = completeCashSale('shop-1', riceLine(rice), AT)
  await push(app, token, [saleEvent(sale)])

  const { events } = await pullAll(app, token, before)
  const movementIds = sale.stockMovements.map((m) => m.id as string)

  assert.equal(events.filter((e) => e.entityId === sale.sale.id).length, 1)
  assert.ok(!events.some((e) => movementIds.includes(e.entityId)))
})

test('paging through changes in small pages returns everything once, and nothing twice', async () => {
  const app = buildApp()
  const { token, rice } = await shopWithStock(app)
  const before = (await pullAll(app, token)).cursor
  const pushed = [
    restockEvent(rice, 1000),
    saleEvent(completeCashSale('shop-1', riceLine(rice, 1000), AT)),
    restockEvent(rice, 2000),
    saleEvent(completeCashSale('shop-1', riceLine(rice, 2000), AT)),
    restockEvent(rice, 3000),
  ]
  await push(app, token, pushed)

  const seen: string[] = []
  let cursor = before
  for (;;) {
    const page = await changesSince('shop-1', cursor, 2)
    if (page.events.length === 0) break
    assert.ok(page.events.length <= 2)
    seen.push(...page.events.map((e) => e.entityId))
    assert.ok(page.cursor > cursor, 'the cursor always moves forward')
    cursor = page.cursor
  }

  for (const event of pushed) {
    assert.equal(seen.filter((id) => id === event.entityId).length, 1, event.entityType)
  }
})

test("a pull never returns another shop's sales, customers or stock", async () => {
  const app = buildApp()
  const { token: shop1, rice } = await shopWithStock(app)
  const customer = generateId()
  const sale = completeCreditSale('shop-1', customer, riceLine(rice), AT)
  await push(app, shop1, [customerEvent(customer, 'Private'), saleEvent(sale)])

  const shop2 = await loginToken(app, SHOP_2)
  const { events } = await pullAll(app, shop2)
  const ids = new Set(events.map((e) => e.entityId))

  assert.ok(!ids.has(sale.sale.id))
  assert.ok(!ids.has(customer))
  assert.ok(!ids.has(rice))
})
