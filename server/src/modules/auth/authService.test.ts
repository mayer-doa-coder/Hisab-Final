import { test } from 'node:test'
import assert from 'node:assert/strict'
import { login, verifyToken, findShop } from './authService.js'

test('login with valid credentials returns a token', () => {
  const token = login('rahim@example.com', 'correct-horse-1')
  assert.ok(token)
  assert.equal(typeof token, 'string')
})

test('login with a wrong password returns null, not a token', () => {
  assert.equal(login('rahim@example.com', 'wrong-password'), null)
})

test('login with an unknown email returns null', () => {
  assert.equal(login('nobody@example.com', 'anything'), null)
})

test('verifyToken resolves a valid token to the right user and shop', () => {
  const token = login('rahim@example.com', 'correct-horse-1')
  const session = verifyToken(token!)

  assert.equal(session?.userId, 'user-1')
  assert.equal(session?.shopId, 'shop-1')
  assert.equal(session?.role, 'owner')
})

test('verifyToken rejects an unknown or made-up token', () => {
  assert.equal(verifyToken('not-a-real-token'), null)
})

test('two different users get two different tokens, each resolving to their own shop', () => {
  const tokenA = login('rahim@example.com', 'correct-horse-1')
  const tokenB = login('karim@example.com', 'correct-horse-2')

  assert.notEqual(tokenA, tokenB)
  assert.equal(verifyToken(tokenA!)?.shopId, 'shop-1')
  assert.equal(verifyToken(tokenB!)?.shopId, 'shop-2')
})

test('findShop returns the shop for a known id, null for an unknown one', () => {
  assert.equal(findShop('shop-1')?.name, 'Rahim Store')
  assert.equal(findShop('no-such-shop'), null)
})
