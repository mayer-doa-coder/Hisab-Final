import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { buildApp } from '../../app.js'
import { closePool } from '../../db/pool.js'
import { runMigrations } from '../../db/migrate.js'

// Needs a Postgres to talk to — see README.md, "Run It Yourself".
before(async () => {
  await runMigrations()
})

after(async () => {
  await closePool()
})

type App = ReturnType<typeof buildApp>

function productPayload(name: string, overrides: Record<string, unknown> = {}) {
  return {
    name,
    aliases: [],
    unit: 'piece',
    sellingPricePoisha: 2000,
    purchasePricePoisha: null,
    active: true,
    ...overrides,
  }
}

function event(entityId: string, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    eventId: randomUUID(),
    entityType: 'Product',
    entityId,
    operation: 'create',
    payload: productPayload(`Sync ${entityId.slice(0, 8)}`),
    baseRevision: null,
    clientTimestamp: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

async function loginToken(
  app: App,
  email = 'rahim@example.com',
  password = 'correct-horse-1',
): Promise<string> {
  const response = await app.inject({
    method: 'POST',
    url: '/auth/login',
    payload: { email, password },
  })
  return response.json().token as string
}

async function push(app: App, token: string, events: Record<string, unknown>[]) {
  return app.inject({
    method: 'POST',
    url: '/sync/push',
    headers: { authorization: `Bearer ${token}` },
    payload: { events },
  })
}

async function pull(app: App, token: string, cursor?: number) {
  const query = cursor === undefined ? '' : `?after=${cursor}`
  return app.inject({
    method: 'GET',
    url: `/sync/changes${query}`,
    headers: { authorization: `Bearer ${token}` },
  })
}

async function products(app: App, token: string, query: string) {
  const response = await app.inject({
    method: 'GET',
    url: `/products?q=${encodeURIComponent(query)}&includeInactive=true`,
    headers: { authorization: `Bearer ${token}` },
  })
  return response.json().products as { id: string; name: string; revision: number }[]
}

// --- Push (Steps 15, 32) ---

test('POST /sync/push requires a token', async () => {
  const app = buildApp()
  const response = await app.inject({ method: 'POST', url: '/sync/push', payload: { events: [] } })

  assert.equal(response.statusCode, 401)
})

// Step 32: a change made on the phone becomes a real product on the server.
test('a pushed create becomes a product the endpoints can see', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const id = randomUUID()
  const name = `Pushed ${id.slice(0, 8)}`

  const response = await push(app, token, [
    event(id, {
      payload: productPayload(name, { sellingPricePoisha: 8500, aliases: ['pushalias'] }),
    }),
  ])

  assert.equal(response.statusCode, 200)
  assert.equal(response.json().results[0].status, 'applied')

  const stored = await products(app, token, name)
  assert.equal(stored.length, 1)
  assert.equal(stored[0]!.id, id)
  assert.equal(stored[0]!.revision, 1)
})

// D004: the phone resends anything it is unsure about.
test('pushing the same event twice only applies it once', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const id = randomUUID()
  const name = `Repeat ${id.slice(0, 8)}`
  const pushed = event(id, { payload: productPayload(name) })

  const first = await push(app, token, [pushed])
  const second = await push(app, token, [pushed])

  assert.equal(first.json().results[0].status, 'applied')
  assert.equal(second.json().results[0].status, 'already-applied')
  assert.equal((await products(app, token, name)).length, 1)
})

test('a batch can mix an already-applied event with a new one', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const old = event(randomUUID())
  await push(app, token, [old])

  const response = await push(app, token, [old, event(randomUUID())])

  assert.deepEqual(
    response.json().results.map((result: { status: string }) => result.status),
    ['already-applied', 'applied'],
  )
})

test('an event without baseRevision is rejected by the schema', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const { baseRevision, ...withoutBaseRevision } = event(randomUUID())
  void baseRevision

  const response = await push(app, token, [withoutBaseRevision])

  assert.equal(response.statusCode, 400)
})

test('an unknown operation is rejected by the schema', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  const response = await push(app, token, [event(randomUUID(), { operation: 'destroy' })])

  assert.equal(response.statusCode, 400)
})

test('a payload that is not a usable product is refused, not half-written', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  const response = await push(app, token, [
    event(randomUUID(), { payload: { name: '', sellingPricePoisha: 'free' } }),
  ])

  assert.equal(response.json().results[0].status, 'rejected')
  assert.equal(response.json().results[0].code, 'INVALID_PAYLOAD')
})

test('an entity type the server does not know is refused', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  const response = await push(app, token, [event(randomUUID(), { entityType: 'Dragon' })])

  assert.equal(response.json().results[0].status, 'rejected')
  assert.equal(response.json().results[0].code, 'UNSUPPORTED_ENTITY')
})

// --- Conflicts (Steps 17, 34) ---

// Step 34: two devices edit the same product from the same revision, both
// offline, then both sync. The second one to arrive must be refused.
test('the second edit from the same revision is refused, and the first one stands', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const id = randomUUID()
  const name = `Conflict ${id.slice(0, 8)}`
  await push(app, token, [event(id, { payload: productPayload(name) })])

  const editFromRevisionOne = (suffix: string) =>
    push(app, token, [
      event(id, {
        operation: 'update',
        baseRevision: 1,
        payload: productPayload(`${name} ${suffix}`),
      }),
    ])

  const phoneA = await editFromRevisionOne('from phone A')
  const phoneB = await editFromRevisionOne('from phone B')

  assert.equal(phoneA.json().results[0].status, 'applied')
  assert.deepEqual(phoneB.json().results[0], {
    eventId: phoneB.json().results[0].eventId,
    status: 'conflict',
    code: 'REVISION_CONFLICT',
  })

  const stored = await products(app, token, name)
  assert.equal(stored.length, 1)
  assert.equal(stored[0]!.name, `${name} from phone A`)
  assert.equal(stored[0]!.revision, 2)
})

test('a delete from a stale revision is refused the same way', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const id = randomUUID()
  const name = `Stale delete ${id.slice(0, 8)}`
  await push(app, token, [event(id, { payload: productPayload(name) })])
  await push(app, token, [
    event(id, { operation: 'update', baseRevision: 1, payload: productPayload(name) }),
  ])

  const response = await push(app, token, [event(id, { operation: 'delete', baseRevision: 1 })])

  assert.equal(response.json().results[0].status, 'conflict')
  assert.equal((await products(app, token, name)).length, 1)
})

// A refused event must stay unclaimed, or the phone could never retry it.
test('a refused event is not remembered as applied, so sending it again is checked again', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const id = randomUUID()
  await push(app, token, [event(id, { payload: productPayload(`Retry ${id.slice(0, 8)}`) })])
  await push(app, token, [
    event(id, { operation: 'update', baseRevision: 1, payload: productPayload('moved on') }),
  ])

  const stale = event(id, {
    operation: 'update',
    baseRevision: 1,
    payload: productPayload('stale'),
  })
  const first = await push(app, token, [stale])
  const again = await push(app, token, [stale])

  assert.equal(first.json().results[0].status, 'conflict')
  assert.equal(again.json().results[0].status, 'conflict')
})

test('a pushed delete leaves a tombstone the endpoints stop listing', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const id = randomUUID()
  const name = `Deleted ${id.slice(0, 8)}`
  await push(app, token, [event(id, { payload: productPayload(name) })])

  const response = await push(app, token, [event(id, { operation: 'delete', baseRevision: 1 })])

  assert.equal(response.json().results[0].status, 'applied')
  assert.deepEqual(await products(app, token, name), [])
})

// --- Pull (Steps 16, 33) ---

test('GET /sync/changes requires a token', async () => {
  const app = buildApp()
  const response = await app.inject({ method: 'GET', url: '/sync/changes' })

  assert.equal(response.statusCode, 401)
})

test('a pushed change comes back in a pull', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const id = randomUUID()
  await push(app, token, [event(id, { payload: productPayload(`Pulled ${id.slice(0, 8)}`) })])

  const body = (await pull(app, token)).json()
  const mine = body.events.filter((e: { entityId: string }) => e.entityId === id)

  assert.equal(mine.length, 1)
  assert.equal(mine[0].operation, 'create')
  assert.equal(mine[0].entityType, 'Product')
  assert.equal(mine[0].payload.name, `Pulled ${id.slice(0, 8)}`)
  assert.ok(body.cursor > 0)
})

// Step 33's exact check: made through the REST endpoint, not pushed as an
// event, and a phone still learns about it.
test('a product created through the product endpoint also comes back in a pull', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const before = (await pull(app, token)).json().cursor
  const id = randomUUID()
  const name = `Rest made ${id.slice(0, 8)}`

  await app.inject({
    method: 'POST',
    url: '/products',
    headers: { authorization: `Bearer ${token}` },
    payload: { id, ...productPayload(name) },
  })

  const body = (await pull(app, token, before)).json()
  const mine = body.events.filter((e: { entityId: string }) => e.entityId === id)

  assert.equal(mine.length, 1)
  assert.equal(mine[0].operation, 'create')
  assert.equal(mine[0].payload.name, name)
})

test('asking again with the returned cursor does not repeat what was already seen', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const id = randomUUID()
  await push(app, token, [event(id, { payload: productPayload(`Cursor ${id.slice(0, 8)}`) })])

  const first = (await pull(app, token)).json()
  assert.ok(first.events.some((e: { entityId: string }) => e.entityId === id))

  const second = (await pull(app, token, first.cursor)).json()
  assert.ok(!second.events.some((e: { entityId: string }) => e.entityId === id))

  const editId = randomUUID()
  await push(app, token, [
    event(editId, {
      entityId: id,
      operation: 'update',
      baseRevision: 1,
      payload: productPayload(`Cursor ${id.slice(0, 8)} edited`),
    }),
  ])

  const third = (await pull(app, token, first.cursor)).json()
  const mine = third.events.filter((e: { entityId: string }) => e.entityId === id)
  assert.equal(mine.length, 1)
  assert.equal(mine[0].operation, 'update')
  assert.equal(mine[0].baseRevision, 1)
})

test('a deletion is something a pull tells other devices about', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const id = randomUUID()
  await push(app, token, [event(id, { payload: productPayload(`Tombstone ${id.slice(0, 8)}`) })])
  const before = (await pull(app, token)).json().cursor
  await push(app, token, [event(id, { operation: 'delete', baseRevision: 1 })])

  const body = (await pull(app, token, before)).json()
  const mine = body.events.filter((e: { entityId: string }) => e.entityId === id)

  assert.equal(mine.length, 1)
  assert.equal(mine[0].operation, 'delete')
  assert.notEqual(mine[0].payload.deletedAt, null)
})

test('GET /sync/changes rejects a cursor that is not a number', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  const response = await app.inject({
    method: 'GET',
    url: '/sync/changes?after=not-a-number',
    headers: { authorization: `Bearer ${token}` },
  })

  assert.equal(response.statusCode, 400)
})

test('a pull never returns another shop changes', async () => {
  const app = buildApp()
  const shopOne = await loginToken(app)
  const shopTwo = await loginToken(app, 'karim@example.com', 'correct-horse-2')
  const id = randomUUID()
  await push(app, shopOne, [event(id, { payload: productPayload(`Private ${id.slice(0, 8)}`) })])

  const seenByOther = (await pull(app, shopTwo)).json().events

  assert.ok(!seenByOther.some((e: { entityId: string }) => e.entityId === id))
})
