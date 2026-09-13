import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildApp } from '../../app.js'

function sampleEvent(eventId: string, entityId: string) {
  return {
    eventId,
    entityType: 'Product',
    entityId,
    operation: 'create' as const,
    payload: { name: 'Coke 500ml' },
    baseRevision: null,
    clientTimestamp: '2026-01-01T00:00:00Z',
  }
}

async function loginToken(app: ReturnType<typeof buildApp>): Promise<string> {
  const res = await app.inject({
    method: 'POST',
    url: '/auth/login',
    payload: { email: 'rahim@example.com', password: 'correct-horse-1' },
  })
  return res.json().token as string
}

test('POST /sync/push requires a token', async () => {
  const app = buildApp()
  const response = await app.inject({ method: 'POST', url: '/sync/push', payload: { events: [] } })

  assert.equal(response.statusCode, 401)
})

test('POST /sync/push applies a new event', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  const response = await app.inject({
    method: 'POST',
    url: '/sync/push',
    headers: { authorization: `Bearer ${token}` },
    payload: { events: [sampleEvent('evt-push-1', 'prod-1')] },
  })

  assert.equal(response.statusCode, 200)
  assert.deepEqual(response.json().results, [{ eventId: 'evt-push-1', applied: true }])
})

// The exact scenario Step 15 describes: pushing the same event twice has the
// same effect as pushing it once.
test('pushing the same event twice, in two separate requests, only applies it once', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const event = sampleEvent('evt-push-repeat', 'prod-2')

  const first = await app.inject({
    method: 'POST',
    url: '/sync/push',
    headers: { authorization: `Bearer ${token}` },
    payload: { events: [event] },
  })
  const second = await app.inject({
    method: 'POST',
    url: '/sync/push',
    headers: { authorization: `Bearer ${token}` },
    payload: { events: [event] },
  })

  assert.deepEqual(first.json().results, [{ eventId: 'evt-push-repeat', applied: true }])
  assert.deepEqual(second.json().results, [{ eventId: 'evt-push-repeat', applied: false }])
})

test('a batch can mix a previously-applied event with a brand-new one', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  await app.inject({
    method: 'POST',
    url: '/sync/push',
    headers: { authorization: `Bearer ${token}` },
    payload: { events: [sampleEvent('evt-batch-old', 'prod-3')] },
  })

  const response = await app.inject({
    method: 'POST',
    url: '/sync/push',
    headers: { authorization: `Bearer ${token}` },
    payload: {
      events: [sampleEvent('evt-batch-old', 'prod-3'), sampleEvent('evt-batch-new', 'prod-4')],
    },
  })

  assert.deepEqual(response.json().results, [
    { eventId: 'evt-batch-old', applied: false },
    { eventId: 'evt-batch-new', applied: true },
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

  const response = await app.inject({
    method: 'POST',
    url: '/sync/push',
    headers: { authorization: `Bearer ${token}` },
    payload: {
      events: [
        {
          eventId: 'evt-bad-2',
          entityType: 'Product',
          entityId: 'prod-6',
          operation: 'destroy',
          payload: {},
          baseRevision: null,
          clientTimestamp: '2026-01-01T00:00:00Z',
        },
      ],
    },
  })

  assert.equal(response.statusCode, 400)
})
