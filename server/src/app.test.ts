import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildApp } from './app.js'

test('GET / responds with status ok', async () => {
  const app = buildApp()
  const response = await app.inject({ method: 'GET', url: '/' })

  assert.equal(response.statusCode, 200)
  assert.deepEqual(response.json(), { status: 'ok' })
})
