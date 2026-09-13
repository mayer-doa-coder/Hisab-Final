import { test } from 'node:test'
import assert from 'node:assert/strict'
import { applyEvent, isProcessed } from './processedEvents.js'

test('a new event is applied', () => {
  const result = applyEvent('evt-unique-1')
  assert.deepEqual(result, { eventId: 'evt-unique-1', applied: true })
})

// The exact scenario Step 15 (docs/PHASE_GUIDE.md) describes: pushing the
// same event twice has the same effect as pushing it once.
test('the same event pushed twice is applied only once', () => {
  const first = applyEvent('evt-unique-2')
  const second = applyEvent('evt-unique-2')

  assert.equal(first.applied, true)
  assert.equal(second.applied, false)
})

test('pushing the same event three times still only applies it once', () => {
  applyEvent('evt-unique-repeat')
  applyEvent('evt-unique-repeat')
  const third = applyEvent('evt-unique-repeat')

  assert.equal(third.applied, false)
})

test('isProcessed reflects whether an event has been applied', () => {
  assert.equal(isProcessed('evt-unique-3'), false)
  applyEvent('evt-unique-3')
  assert.equal(isProcessed('evt-unique-3'), true)
})

test('two different event ids are tracked independently', () => {
  const a = applyEvent('evt-unique-4a')
  const b = applyEvent('evt-unique-4b')

  assert.equal(a.applied, true)
  assert.equal(b.applied, true)
})
