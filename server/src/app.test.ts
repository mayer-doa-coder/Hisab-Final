import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildApp } from './app.js'

test('GET / responds with status ok', async () => {
  const app = buildApp()
  const response = await app.inject({ method: 'GET', url: '/' })

  assert.equal(response.statusCode, 200)
  assert.deepEqual(response.json(), { status: 'ok' })
})

// --- Health check (Step 21) ---

async function loginToken(app: ReturnType<typeof buildApp>): Promise<string> {
  const response = await app.inject({
    method: 'POST',
    url: '/auth/login',
    payload: { email: 'rahim@example.com', password: 'correct-horse-1' },
  })
  return response.json().token as string
}

// Step 21's exact check, first half: calling it without a token fails.
test('GET /health without a token is rejected', async () => {
  const app = buildApp()
  const response = await app.inject({ method: 'GET', url: '/health' })

  assert.equal(response.statusCode, 401)
  assert.deepEqual(response.json(), { code: 'AUTH_INVALID' })
})

test('GET /health with a made-up token is rejected', async () => {
  const app = buildApp()
  const response = await app.inject({
    method: 'GET',
    url: '/health',
    headers: { authorization: 'Bearer not-a-real-token' },
  })

  assert.equal(response.statusCode, 401)
  assert.deepEqual(response.json(), { code: 'AUTH_INVALID' })
})

test('GET /health with a real token but the wrong header format is rejected', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const response = await app.inject({
    method: 'GET',
    url: '/health',
    headers: { authorization: token },
  })

  assert.equal(response.statusCode, 401)
})

// Step 21's exact check, second half: calling it with a token succeeds.
test('GET /health with a valid token responds with status ok', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const response = await app.inject({
    method: 'GET',
    url: '/health',
    headers: { authorization: `Bearer ${token}` },
  })

  assert.equal(response.statusCode, 200)
  assert.deepEqual(response.json(), { status: 'ok' })
})
