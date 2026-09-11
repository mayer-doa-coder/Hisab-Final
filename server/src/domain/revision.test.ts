import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  checkRevision,
  createRevisioned,
  applyRevisionedUpdate,
  applyRevisionedDelete,
  REVISION_CONFLICT,
} from './revision.js'

test('checkRevision accepts a write against the current revision', () => {
  assert.deepEqual(checkRevision(1, 1), { ok: true })
})

test('checkRevision rejects a write against a stale revision', () => {
  assert.deepEqual(checkRevision(2, 1), { ok: false, code: REVISION_CONFLICT })
})

test('createRevisioned starts at revision 1 with no tombstone', () => {
  const created = createRevisioned({ name: 'Coke 500ml' }, new Date('2026-01-01T00:00:00Z'))
  assert.equal(created.revision, 1)
  assert.equal(created.deletedAt, null)
})

test('applyRevisionedUpdate succeeds and bumps the revision when base_revision matches', () => {
  const created = createRevisioned({ name: 'Coke 500ml' }, new Date('2026-01-01T00:00:00Z'))
  const { result, next } = applyRevisionedUpdate(
    created,
    /* baseRevision */ 1,
    { name: 'Coca-Cola 500ml' },
    new Date('2026-01-02T00:00:00Z'),
  )

  assert.deepEqual(result, { ok: true })
  assert.equal(next.revision, 2)
  assert.equal(next.data.name, 'Coca-Cola 500ml')
})

// This is the exact scenario docs/PHASE_GUIDE.md Step 34 (M1) describes:
// two devices edit the same product offline, then both sync. The second one
// to land must be rejected, not silently overwrite the first.
test('two offline edits to the same entity: the second to arrive is rejected, not silently applied', () => {
  const created = createRevisioned({ name: 'Coke 500ml' }, new Date('2026-01-01T00:00:00Z'))

  // Phone A edits first, from revision 1. Succeeds, server is now at revision 2.
  const phoneA = applyRevisionedUpdate(
    created,
    1,
    { name: 'Coca-Cola 500ml' },
    new Date('2026-01-02T00:00:00Z'),
  )
  assert.equal(phoneA.result.ok, true)
  const serverState = phoneA.next

  // Phone B was also offline since revision 1, and now tries to sync its own
  // edit — still against base_revision 1, which is now stale.
  const phoneB = applyRevisionedUpdate(
    serverState,
    1,
    { name: 'Coca Cola (500 ML)' },
    new Date('2026-01-02T01:00:00Z'),
  )

  assert.deepEqual(phoneB.result, { ok: false, code: REVISION_CONFLICT })
  // Rejected means untouched: still Phone A's name and revision, not Phone B's.
  assert.equal(phoneB.next.data.name, 'Coca-Cola 500ml')
  assert.equal(phoneB.next.revision, 2)
})

test('applyRevisionedDelete sets a tombstone instead of being silently dropped', () => {
  const created = createRevisioned({ name: 'Coke 500ml' }, new Date('2026-01-01T00:00:00Z'))
  const { result, next } = applyRevisionedDelete(created, 1, new Date('2026-01-03T00:00:00Z'))

  assert.deepEqual(result, { ok: true })
  assert.equal(next.deletedAt, '2026-01-03T00:00:00.000Z')
  assert.equal(next.revision, 2)
  // The underlying data is preserved, not erased — it's a tombstone, not a delete.
  assert.equal(next.data.name, 'Coke 500ml')
})

test('a delete against a stale revision is rejected, same as an update', () => {
  const created = createRevisioned({ name: 'Coke 500ml' }, new Date('2026-01-01T00:00:00Z'))
  const staleDelete = applyRevisionedDelete(created, 0, new Date('2026-01-03T00:00:00Z'))

  assert.deepEqual(staleDelete.result, { ok: false, code: REVISION_CONFLICT })
  assert.equal(staleDelete.next.deletedAt, null)
})
