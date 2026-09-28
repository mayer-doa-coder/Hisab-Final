import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { buildApp } from '../../app.js'
import { closePool } from '../../db/pool.js'
import { runMigrations } from '../../db/migrate.js'
import { type EntityId } from '../../domain/id.js'
import { quantity } from '../../domain/quantity.js'
import { restock } from '../../domain/stock.js'
import { auth, loginToken, makeProduct, stockOf, type App } from '../../testSupport.js'

// Steps 67-70 on the server's side: what happens when a push is cut off part
// way, arrives twice at once, or arrives very large. Every check here is
// about the same promise — nothing is lost, and nothing is applied twice
// (D004) — so each one is written against a running total, where a second
// application would show up as a doubled number rather than something subtle.
//
// Needs a Postgres to talk to — see README.md, "Run It Yourself".
before(async () => {
  await runMigrations()
})

after(async () => {
  await closePool()
})

const AT = new Date('2026-09-27T10:00:00.000Z')

function restockEvent(productId: string, amount: number) {
  const movement = restock(productId as EntityId, quantity(amount), AT)
  return {
    eventId: randomUUID(),
    entityType: 'StockMovement',
    entityId: movement.id,
    operation: 'create',
    payload: JSON.parse(JSON.stringify(movement)),
    baseRevision: null,
    clientTimestamp: AT.toISOString(),
  }
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

const statusOf = (
  results: Array<{ eventId: string; status: string }>,
  event: { eventId: string },
) => results.find((result) => result.eventId === event.eventId)?.status

// Step 67. A batch is applied one event at a time, each claimed and committed
// on its own, so a server that dies half way through has really written the
// first one and really not written the second. The phone never saw a reply,
// so it sends the whole batch again — which is the moment the first event
// could be applied twice, if the event log were not doing its job.
test('a batch cut off after its first event does not apply that event twice when it is sent again', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const productId = await makeProduct(app, token)

  const first = restockEvent(productId, 10_000)
  const second = restockEvent(productId, 7_000)

  // The part that got through before the crash.
  const beforeCrash = await push(app, token, [first])
  assert.equal(statusOf(beforeCrash, first), 'applied')
  assert.equal(await stockOf(app, token, productId), 10_000)

  // The phone, having had no answer, sends the whole batch again.
  const retry = await push(app, token, [first, second])

  assert.equal(statusOf(retry, first), 'already-applied', 'the first must not be applied twice')
  assert.equal(statusOf(retry, second), 'applied', 'the second must still get through')
  assert.equal(
    await stockOf(app, token, productId),
    17_000,
    'ten plus seven — not twenty-seven, and not ten',
  )
})

// Step 69, "duplicate request": the same batch arriving twice at the same
// moment, which is what a phone retrying over a flaky connection actually
// does. Both requests race on the same claim; exactly one can win it.
test('the same batch arriving twice at once is still applied once', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const productId = await makeProduct(app, token)
  const event = restockEvent(productId, 5_000)

  const [left, right] = await Promise.all([push(app, token, [event]), push(app, token, [event])])

  const statuses = [statusOf(left, event), statusOf(right, event)].sort()
  assert.deepEqual(
    statuses,
    ['already-applied', 'applied'],
    'one applies it, one is told it exists',
  )
  assert.equal(await stockOf(app, token, productId), 5_000, 'the stock moved once, not twice')
})

// Step 68, "timeout after server processing": the work was done, the answer
// never arrived. From here that is the same event id arriving again, and the
// phone is told the truth — it is already applied — so it can stop sending it.
test('an event whose answer never reached the phone is reported as already applied, not refused', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const productId = await makeProduct(app, token)
  const event = restockEvent(productId, 3_000)

  await push(app, token, [event])
  const retry = await push(app, token, [event])

  assert.equal(statusOf(retry, event), 'already-applied')
  assert.equal(await stockOf(app, token, productId), 3_000)
})

// Step 70. A phone that has been offline for a long week comes back with a
// lot to say. The point is not speed, it is that a big push is still exactly
// once — and that sending the same big push again changes nothing.
test('a thousand events in one push all apply, once, and a repeat of the same push changes nothing', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const productId = await makeProduct(app, token)

  const events = Array.from({ length: 1_000 }, () => restockEvent(productId, 1_000))

  const first = await push(app, token, events)
  assert.equal(first.length, 1_000)
  assert.equal(
    first.filter((result) => result.status === 'applied').length,
    1_000,
    'every one of them applied',
  )
  assert.equal(await stockOf(app, token, productId), 1_000_000, 'a thousand lots of one')

  const repeat = await push(app, token, events)
  assert.equal(
    repeat.filter((result) => result.status === 'already-applied').length,
    1_000,
    'the whole batch is recognised the second time',
  )
  assert.equal(await stockOf(app, token, productId), 1_000_000, 'and the total did not move')
})

// Step 70, the smaller size the plan names first, kept separate so a failure
// says which size broke.
test('a hundred events in one push all apply', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const productId = await makeProduct(app, token)

  const events = Array.from({ length: 100 }, () => restockEvent(productId, 500))
  const results = await push(app, token, events)

  assert.equal(results.filter((result) => result.status === 'applied').length, 100)
  assert.equal(await stockOf(app, token, productId), 50_000)
})
