import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  quantity,
  quantityFromDecimal,
  quantityToDecimal,
  addQuantity,
  sumQuantity,
} from './quantity.js'

test('quantity rejects non-integer scaled values', () => {
  assert.throws(() => quantity(1.5), TypeError)
})

// The exact examples from PRD.md section 24 and DECISIONS.md D019.
test('quantityFromDecimal matches the documented examples', () => {
  assert.equal(quantityFromDecimal(0.5), 500) // 0.5 kg
  assert.equal(quantityFromDecimal(1.25), 1250) // 1.25 kg
  assert.equal(quantityFromDecimal(2.5), 2500) // 2.5 litre
  assert.equal(quantityFromDecimal(3), 3000) // 3 pieces
})

test('quantityToDecimal is the inverse of quantityFromDecimal', () => {
  assert.equal(quantityToDecimal(quantityFromDecimal(1.25)), 1.25)
})

test('addQuantity and sumQuantity work in scaled integer space', () => {
  assert.equal(addQuantity(quantity(500), quantity(1250)), 1750)
  assert.equal(sumQuantity([quantity(500), quantity(500), quantity(1000)]), 2000)
})

test('a negative quantity_delta (e.g. a sale) is allowed', () => {
  assert.equal(quantity(-500), -500)
})
