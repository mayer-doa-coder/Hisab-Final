import { test } from 'node:test'
import assert from 'node:assert/strict'
import { generateId } from './id.js'

test('generateId produces a well-formed UUID', () => {
  const id = generateId()
  assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i)
})

test('generateId produces unique values', () => {
  const ids = new Set(Array.from({ length: 1000 }, () => generateId()))
  assert.equal(ids.size, 1000)
})
