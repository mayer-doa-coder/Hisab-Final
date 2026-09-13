import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildApp } from '../../app.js'

function sampleEvent(eventId: string, entityId: string, overrides: Record<string, unknown> = {}) {
  return {
    eventId,
    entityType: 'Product',
    entityId,
    operation: 'create' as const,
    payload: { name: 'Coke 500ml' },
    baseRevision: null,
    clientTimestamp: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

async function loginToken(
  app: ReturnType<typeof buildApp>,
  email = 'rahim@example.com',
  password = 'correct-horse-1',
): Promise<string> {
  const res = await app.inject({
    method: 'POST',
    url: '/auth/login',
    payload: { email, password },
  })
  return res.json().token as string
}

async function push(
  app: ReturnType<typeof buildApp>,
  token: string,
  events: ReturnType<typeof sampleEvent>[],
) {
  return app.inject({
    method: 'POST',
    url: '/sync/push',
    headers: { authorization: `Bearer ${token}` },
    payload: { events },
  })
}

// --- Push (Step 15) ---

test('POST /sync/push requires a token', async () => {
  const app = buildApp()
  const response = await app.inject({ method: 'POST', url: '/sync/push', payload: { events: [] } })

  assert.equal(response.statusCode, 401)
})

test('POST /sync/push applies a new event', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  const response = await push(app, token, [sampleEvent('evt-push-1', 'prod-1')])

  assert.equal(response.statusCode, 200)
  assert.deepEqual(response.json().results, [{ eventId: 'evt-push-1', status: 'applied' }])
})

// The exact scenario Step 15 describes: pushing the same event twice has the
// same effect as pushing it once.
test('pushing the same event twice, in two separate requests, only applies it once', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const event = sampleEvent('evt-push-repeat', 'prod-2')

  const first = await push(app, token, [event])
  const second = await push(app, token, [event])

  assert.deepEqual(first.json().results, [{ eventId: 'evt-push-repeat', status: 'applied' }])
  assert.deepEqual(second.json().results, [
    { eventId: 'evt-push-repeat', status: 'already-applied' },
  ])
})

test('a batch can mix a previously-applied event with a brand-new one', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  await push(app, token, [sampleEvent('evt-batch-old', 'prod-3')])

  const response = await push(app, token, [
    sampleEvent('evt-batch-old', 'prod-3'),
    sampleEvent('evt-batch-new', 'prod-4'),
  ])

  assert.deepEqual(response.json().results, [
    { eventId: 'evt-batch-old', status: 'already-applied' },
    { eventId: 'evt-batch-new', status: 'applied' },
  ])
})

test('an update event must carry a baseRevision (schema requires the field, even if null)', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  const response = await app.inject({
    method: 'POST',
    url: '/sync/push',
    headers: { authorization: `Bearer ${token}` },
    payload: {
      events: [
        {
          eventId: 'evt-bad',
          entityType: 'Product',
          entityId: 'prod-5',
          operation: 'update',
          payload: {},
          clientTimestamp: '2026-01-01T00:00:00Z',
          // baseRevision deliberately omitted
        },
      ],
    },
  })

  assert.equal(response.statusCode, 400)
})

test('an unknown operation value is rejected', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  const response = await push(app, token, [
    sampleEvent('evt-bad-2', 'prod-6', { operation: 'destroy' }),
  ])

  assert.equal(response.statusCode, 400)
})

// --- Conflict checking (Step 17) ---

// Step 17's exact check, run through the real HTTP endpoint: edit the same
// (test) record twice with the same base revision — the second one is
// rejected.
test('editing the same record twice with the same base_revision rejects the second edit over HTTP', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  await push(app, token, [sampleEvent('evt-conflict-create', 'prod-conflict')])

  const firstEdit = await push(app, token, [
    sampleEvent('evt-conflict-edit-1', 'prod-conflict', { operation: 'update', baseRevision: 1 }),
  ])
  assert.deepEqual(firstEdit.json().results, [
    { eventId: 'evt-conflict-edit-1', status: 'applied' },
  ])

  const secondEdit = await push(app, token, [
    sampleEvent('evt-conflict-edit-2', 'prod-conflict', { operation: 'update', baseRevision: 1 }),
  ])
  assert.deepEqual(secondEdit.json().results, [
    { eventId: 'evt-conflict-edit-2', status: 'conflict', code: 'REVISION_CONFLICT' },
  ])
})

test('a delete against a stale base_revision is rejected over HTTP, same as an update', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  await push(app, token, [sampleEvent('evt-conflict-create-2', 'prod-conflict-2')])
  await push(app, token, [
    sampleEvent('evt-conflict-bump', 'prod-conflict-2', { operation: 'update', baseRevision: 1 }),
  ])

  const staleDelete = await push(app, token, [
    sampleEvent('evt-conflict-delete', 'prod-conflict-2', { operation: 'delete', baseRevision: 1 }),
  ])

  assert.deepEqual(staleDelete.json().results, [
    { eventId: 'evt-conflict-delete', status: 'conflict', code: 'REVISION_CONFLICT' },
  ])
})

// --- Pull (Step 16) ---

test('GET /sync/changes requires a token', async () => {
  const app = buildApp()
  const response = await app.inject({ method: 'GET', url: '/sync/changes' })

  assert.equal(response.statusCode, 401)
})

test('GET /sync/changes with no cursor returns everything pushed so far', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  await push(app, token, [
    sampleEvent('evt-pull-1', 'prod-pull-1'),
    sampleEvent('evt-pull-2', 'prod-pull-2'),
  ])

  const response = await app.inject({
    method: 'GET',
    url: '/sync/changes',
    headers: { authorization: `Bearer ${token}` },
  })

  assert.equal(response.statusCode, 200)
  const body = response.json()
  // "Everything" for this shop also includes events from earlier tests in
  // this file (eventLog is one shared store for the whole process, same as
  // real usage) — so check these two are present, not that they are the
  // only ones.
  const ids = body.events.map((event: { eventId: string }) => event.eventId)
  assert.ok(ids.includes('evt-pull-1'))
  assert.ok(ids.includes('evt-pull-2'))
  assert.ok(ids.indexOf('evt-pull-1') < ids.indexOf('evt-pull-2'))
  assert.ok(body.cursor > 0)
})

// Step 16's exact check: calling it again with the returned cursor returns
// nothing new (until something changes).
test('GET /sync/changes with the returned cursor returns nothing new, until something changes', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  await push(app, token, [sampleEvent('evt-pull-3', 'prod-pull-3')])

  const first = await app.inject({
    method: 'GET',
    url: '/sync/changes',
    headers: { authorization: `Bearer ${token}` },
  })
  const cursor = first.json().cursor

  const second = await app.inject({
    method: 'GET',
    url: `/sync/changes?after=${cursor}`,
    headers: { authorization: `Bearer ${token}` },
  })
  assert.deepEqual(second.json().events, [])
  assert.equal(second.json().cursor, cursor)

  await push(app, token, [sampleEvent('evt-pull-4', 'prod-pull-4')])

  const third = await app.inject({
    method: 'GET',
    url: `/sync/changes?after=${cursor}`,
    headers: { authorization: `Bearer ${token}` },
  })
  assert.deepEqual(
    third.json().events.map((event: { eventId: string }) => event.eventId),
    ['evt-pull-4'],
  )
})

test('GET /sync/changes rejects a non-numeric cursor', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  const response = await app.inject({
    method: 'GET',
    url: '/sync/changes?after=not-a-number',
    headers: { authorization: `Bearer ${token}` },
  })

  assert.equal(response.statusCode, 400)
})

// The same tenant-isolation rule as everywhere else (D015): a shop only
// ever sees its own events, never another shop's.
test('GET /sync/changes never returns another shop', async () => {
  const app = buildApp()
  const tokenShop1 = await loginToken(app, 'rahim@example.com', 'correct-horse-1')
  const tokenShop2 = await loginToken(app, 'karim@example.com', 'correct-horse-2')

  await push(app, tokenShop1, [sampleEvent('evt-shop1-only', 'prod-shop1')])
  await push(app, tokenShop2, [sampleEvent('evt-shop2-only', 'prod-shop2')])

  const responseShop1 = await app.inject({
    method: 'GET',
    url: '/sync/changes',
    headers: { authorization: `Bearer ${tokenShop1}` },
  })

  const eventIds = responseShop1.json().events.map((event: { eventId: string }) => event.eventId)
  assert.ok(eventIds.includes('evt-shop1-only'))
  assert.ok(!eventIds.includes('evt-shop2-only'))
})
