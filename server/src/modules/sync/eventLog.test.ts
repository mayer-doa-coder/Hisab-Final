import { test } from 'node:test'
import assert from 'node:assert/strict'
import type { SyncEventEnvelope } from '../../domain/syncEvent.js'
import {
  applyEvent,
  isProcessed,
  getCurrentRevision,
  changesSince,
  INITIAL_CURSOR,
} from './eventLog.js'

let uniqueCounter = 0
function nextId(prefix: string): string {
  uniqueCounter += 1
  return `${prefix}-${uniqueCounter}`
}

function makeEvent(overrides: Partial<SyncEventEnvelope> = {}): SyncEventEnvelope {
  return {
    eventId: nextId('evt'),
    entityType: 'Product',
    entityId: nextId('prod'),
    operation: 'create',
    payload: { name: 'Coke 500ml' },
    baseRevision: null,
    clientTimestamp: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

// --- Idempotency (Step 15) ---

test('a new event is applied', () => {
  const event = makeEvent()
  assert.deepEqual(applyEvent('shop-1', event), { eventId: event.eventId, status: 'applied' })
})

// The exact scenario Step 15 describes: pushing the same event twice has
// the same effect as pushing it once.
test('the same event applied twice reports already-applied the second time', () => {
  const event = makeEvent()
  const first = applyEvent('shop-1', event)
  const second = applyEvent('shop-1', event)

  assert.equal(first.status, 'applied')
  assert.deepEqual(second, { eventId: event.eventId, status: 'already-applied' })
})

test('applying the same event three times still only applies it once', () => {
  const event = makeEvent()
  applyEvent('shop-1', event)
  applyEvent('shop-1', event)
  const third = applyEvent('shop-1', event)

  assert.equal(third.status, 'already-applied')
})

test('isProcessed reflects whether an event has been applied', () => {
  const event = makeEvent()
  assert.equal(isProcessed(event.eventId), false)
  applyEvent('shop-1', event)
  assert.equal(isProcessed(event.eventId), true)
})

test('two different event ids are tracked independently', () => {
  const a = applyEvent('shop-1', makeEvent())
  const b = applyEvent('shop-1', makeEvent())

  assert.equal(a.status, 'applied')
  assert.equal(b.status, 'applied')
})

// --- Conflict checking (Step 17) ---

test('create always succeeds and starts the entity at revision 1', () => {
  const entityId = nextId('prod')
  applyEvent('shop-1', makeEvent({ entityId, operation: 'create', baseRevision: null }))

  assert.equal(getCurrentRevision('Product', entityId), 1)
})

test('an update against the current revision is applied and bumps the revision', () => {
  const entityId = nextId('prod')
  applyEvent('shop-1', makeEvent({ entityId, operation: 'create', baseRevision: null }))

  const update = makeEvent({ entityId, operation: 'update', baseRevision: 1 })
  const result = applyEvent('shop-1', update)

  assert.deepEqual(result, { eventId: update.eventId, status: 'applied' })
  assert.equal(getCurrentRevision('Product', entityId), 2)
})

// This is Step 17's exact check: edit the same (test) record twice with the
// same base revision — the second one is rejected.
test('editing the same record twice with the same base_revision rejects the second edit', () => {
  const entityId = nextId('prod')
  applyEvent('shop-1', makeEvent({ entityId, operation: 'create', baseRevision: null }))

  const firstEdit = makeEvent({ entityId, operation: 'update', baseRevision: 1 })
  const firstResult = applyEvent('shop-1', firstEdit)
  assert.equal(firstResult.status, 'applied')
  assert.equal(getCurrentRevision('Product', entityId), 2)

  // A second device, also editing from revision 1, now stale, since the
  // first edit already moved the entity to revision 2.
  const secondEdit = makeEvent({ entityId, operation: 'update', baseRevision: 1 })
  const secondResult = applyEvent('shop-1', secondEdit)

  assert.deepEqual(secondResult, {
    eventId: secondEdit.eventId,
    status: 'conflict',
    code: 'REVISION_CONFLICT',
  })
  // Rejected means untouched: the revision did not move a second time.
  assert.equal(getCurrentRevision('Product', entityId), 2)
})

test('a delete is revision-checked the same way as an update', () => {
  const entityId = nextId('prod')
  applyEvent('shop-1', makeEvent({ entityId, operation: 'create', baseRevision: null }))

  const staleDelete = makeEvent({ entityId, operation: 'delete', baseRevision: 0 })
  const result = applyEvent('shop-1', staleDelete)

  assert.deepEqual(result, {
    eventId: staleDelete.eventId,
    status: 'conflict',
    code: 'REVISION_CONFLICT',
  })
})

test('a rejected conflicting event is not marked processed, so retrying it is re-checked, not treated as a duplicate', () => {
  const entityId = nextId('prod')
  applyEvent('shop-1', makeEvent({ entityId, operation: 'create', baseRevision: null }))
  applyEvent('shop-1', makeEvent({ entityId, operation: 'update', baseRevision: 1 })) // moves to revision 2

  const conflicting = makeEvent({ entityId, operation: 'update', baseRevision: 1 })
  const firstAttempt = applyEvent('shop-1', conflicting)
  assert.equal(firstAttempt.status, 'conflict')
  assert.equal(isProcessed(conflicting.eventId), false)

  // Retrying the exact same (still-stale) event gives the same conflict
  // again — it was never "used up" by the first rejection.
  const secondAttempt = applyEvent('shop-1', conflicting)
  assert.equal(secondAttempt.status, 'conflict')
})

test('a rejected conflicting event does not appear in pull results', () => {
  const entityId = nextId('prod')
  applyEvent('shop-2', makeEvent({ entityId, operation: 'create', baseRevision: null }))
  applyEvent('shop-2', makeEvent({ entityId, operation: 'update', baseRevision: 1 }))

  const conflicting = makeEvent({ entityId, operation: 'update', baseRevision: 1 })
  applyEvent('shop-2', conflicting)

  const { events } = changesSince('shop-2', INITIAL_CURSOR)
  assert.ok(!events.some((event) => event.eventId === conflicting.eventId))
})

// --- Pull / cursor (Step 16) ---

test('changesSince returns nothing for a shop that has never pushed anything', () => {
  const result = changesSince('shop-never-used', INITIAL_CURSOR)
  assert.deepEqual(result, { events: [], cursor: INITIAL_CURSOR })
})

// Step 16's exact check: calling it with no cursor returns everything.
test('changesSince with the initial cursor returns everything pushed so far', () => {
  const shopId = nextId('shop')
  const first = makeEvent()
  const second = makeEvent()
  applyEvent(shopId, first)
  applyEvent(shopId, second)

  const result = changesSince(shopId, INITIAL_CURSOR)
  assert.deepEqual(
    result.events.map((event) => event.eventId),
    [first.eventId, second.eventId],
  )
  assert.ok(result.cursor > INITIAL_CURSOR)
})

// Step 16's exact check: calling it again with the returned cursor returns
// nothing new (until something changes).
test('calling changesSince again with the returned cursor returns nothing new', () => {
  const shopId = nextId('shop')
  applyEvent(shopId, makeEvent())
  const first = changesSince(shopId, INITIAL_CURSOR)

  const second = changesSince(shopId, first.cursor)
  assert.deepEqual(second.events, [])
  assert.equal(second.cursor, first.cursor)
})

test('after pushing something new, changesSince with the old cursor returns only the new event', () => {
  const shopId = nextId('shop')
  const older = makeEvent()
  applyEvent(shopId, older)
  const afterFirst = changesSince(shopId, INITIAL_CURSOR)

  const newer = makeEvent()
  applyEvent(shopId, newer)
  const afterSecond = changesSince(shopId, afterFirst.cursor)

  assert.deepEqual(
    afterSecond.events.map((event) => event.eventId),
    [newer.eventId],
  )
  assert.ok(afterSecond.cursor > afterFirst.cursor)
})

test("changesSince never returns another shop's events", () => {
  const shopA = nextId('shop')
  const shopB = nextId('shop')
  const eventForA = makeEvent()
  const eventForB = makeEvent()
  applyEvent(shopA, eventForA)
  applyEvent(shopB, eventForB)

  const resultForA = changesSince(shopA, INITIAL_CURSOR)
  assert.deepEqual(
    resultForA.events.map((event) => event.eventId),
    [eventForA.eventId],
  )
  assert.ok(!resultForA.events.some((event) => event.eventId === eventForB.eventId))
})
