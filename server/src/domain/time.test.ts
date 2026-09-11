import { test } from 'node:test'
import assert from 'node:assert/strict'
import { transactionTimeAtCreation, markServerReceived, isSynced } from './time.js'

test('a transaction created offline has no server_received_at yet', () => {
  const time = transactionTimeAtCreation(new Date('2026-01-01T08:00:00Z'))
  assert.equal(time.occurredAt, '2026-01-01T08:00:00.000Z')
  assert.equal(time.serverReceivedAt, null)
  assert.equal(isSynced(time), false)
})

// The scenario D019/D016 explicitly call out: an offline sale that syncs
// hours later must keep reporting when it actually happened, not when the
// server happened to receive it.
test('occurred_at and server_received_at can differ by hours', () => {
  const occurredAt = new Date('2026-01-01T08:00:00Z') // sale made in the shop, offline
  const receivedAt = new Date('2026-01-01T14:00:00Z') // phone reconnects hours later

  const created = transactionTimeAtCreation(occurredAt)
  const synced = markServerReceived(created, receivedAt)

  assert.equal(synced.occurredAt, '2026-01-01T08:00:00.000Z')
  assert.equal(synced.serverReceivedAt, '2026-01-01T14:00:00.000Z')
  assert.notEqual(synced.occurredAt, synced.serverReceivedAt)
  assert.equal(isSynced(synced), true)
})
