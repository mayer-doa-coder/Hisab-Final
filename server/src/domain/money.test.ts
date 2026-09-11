import { test } from 'node:test'
import assert from 'node:assert/strict'
import { money, addMoney, subtractMoney, sumMoney, ZERO_MONEY } from './money.js'

test('money rejects non-integer values (never a float)', () => {
  assert.throws(() => money(10.5), TypeError)
  assert.throws(() => money(0.1), TypeError)
})

test('money accepts integers, including negative (for reversals)', () => {
  assert.equal(money(100), 100)
  assert.equal(money(-50), -50)
  assert.equal(money(0), 0)
})

test('addMoney and subtractMoney stay integer', () => {
  assert.equal(addMoney(money(500), money(200)), 700)
  assert.equal(subtractMoney(money(700), money(200)), 500)
})

test('sumMoney sums a list, empty list sums to zero', () => {
  assert.equal(sumMoney([money(100), money(200), money(300)]), 600)
  assert.equal(sumMoney([]), ZERO_MONEY)
})
