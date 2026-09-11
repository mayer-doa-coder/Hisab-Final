import { test } from 'node:test'
import assert from 'node:assert/strict'
import { DEFAULT_CURRENCY } from './currency.js'

test('the default currency is BDT', () => {
  assert.equal(DEFAULT_CURRENCY, 'BDT')
})
