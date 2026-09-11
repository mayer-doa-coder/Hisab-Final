import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildApp } from '../../app.js'

test('POST /auth/login with valid credentials returns 200 and a token', async () => {
  const app = buildApp()
  const response = await app.inject({
    method: 'POST',
    url: '/auth/login',
    payload: { email: 'rahim@example.com', password: 'correct-horse-1' },
  })

  assert.equal(response.statusCode, 200)
  assert.ok(response.json().token)
})

test('POST /auth/login with a wrong password returns 401', async () => {
  const app = buildApp()
  const response = await app.inject({
    method: 'POST',
    url: '/auth/login',
    payload: { email: 'rahim@example.com', password: 'wrong' },
  })

  assert.equal(response.statusCode, 401)
  assert.equal(response.json().code, 'AUTH_INVALID')
})

test('GET /auth/me with no token is rejected', async () => {
  const app = buildApp()
  const response = await app.inject({ method: 'GET', url: '/auth/me' })

  assert.equal(response.statusCode, 401)
  assert.equal(response.json().code, 'AUTH_INVALID')
})

test('GET /auth/me with a made-up token is rejected', async () => {
  const app = buildApp()
  const response = await app.inject({
    method: 'GET',
    url: '/auth/me',
    headers: { authorization: 'Bearer not-a-real-token' },
  })

  assert.equal(response.statusCode, 401)
})

test("GET /auth/me with a valid token returns that user's own shop_id", async () => {
  const app = buildApp()
  const login = await app.inject({
    method: 'POST',
    url: '/auth/login',
    payload: { email: 'rahim@example.com', password: 'correct-horse-1' },
  })
  const { token } = login.json()

  const me = await app.inject({
    method: 'GET',
    url: '/auth/me',
    headers: { authorization: `Bearer ${token}` },
  })

  assert.equal(me.statusCode, 200)
  assert.equal(me.json().shopId, 'shop-1')
})

// The exact scenario Step 12 (docs/PHASE_GUIDE.md) describes: a token for
// Shop A must not be tricked into acting on Shop B's data by changing a
// parameter. shop_id must come only from the token, never the request.
test('a token for shop-1 still returns shop-1, even when a shop_id for shop-2 is passed as a query param', async () => {
  const app = buildApp()
  const login = await app.inject({
    method: 'POST',
    url: '/auth/login',
    payload: { email: 'rahim@example.com', password: 'correct-horse-1' },
  })
  const { token } = login.json()

  const me = await app.inject({
    method: 'GET',
    url: '/auth/me?shop_id=shop-2',
    headers: { authorization: `Bearer ${token}` },
  })

  assert.equal(me.statusCode, 200)
  assert.equal(me.json().shopId, 'shop-1')
  assert.notEqual(me.json().shopId, 'shop-2')
})
