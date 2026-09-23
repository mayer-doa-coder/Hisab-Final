import { randomUUID } from 'node:crypto'
import type { buildApp } from './app.js'

/**
 * Helpers shared by the route tests that need a logged-in shop and a real
 * product to sell. Not a test file itself (it does not end in .test.ts), so
 * the test runner never runs it on its own.
 *
 * The two demo accounts are the in-memory ones from authService (D027):
 * rahim@example.com is shop-1, karim@example.com is shop-2 — which is what
 * lets every suite check that one shop cannot reach the other (D015).
 */
export type App = ReturnType<typeof buildApp>

export const SHOP_1 = { email: 'rahim@example.com', password: 'correct-horse-1' }
export const SHOP_2 = { email: 'karim@example.com', password: 'correct-horse-2' }

export async function loginToken(app: App, account = SHOP_1): Promise<string> {
  const response = await app.inject({
    method: 'POST',
    url: '/auth/login',
    payload: account,
  })
  const token = response.json().token as string | undefined
  if (token === undefined) throw new Error(`login failed for ${account.email}`)
  return token
}

export function auth(token: string): Record<string, string> {
  return { authorization: `Bearer ${token}` }
}

/** Creates a product through the real endpoint and returns its id. */
export async function makeProduct(
  app: App,
  token: string,
  overrides: Record<string, unknown> = {},
): Promise<string> {
  const id = randomUUID()
  const response = await app.inject({
    method: 'POST',
    url: '/products',
    headers: auth(token),
    payload: {
      id,
      name: `Test ${id.slice(0, 8)}`,
      unit: 'piece',
      sellingPricePoisha: 5000,
      ...overrides,
    },
  })
  if (response.statusCode !== 201) {
    throw new Error(`could not create a product: ${response.statusCode} ${response.body}`)
  }
  return id
}

/** Current stock of one product, as the stock endpoint reports it. */
export async function stockOf(app: App, token: string, productId: string): Promise<number> {
  const response = await app.inject({
    method: 'GET',
    url: `/stock/${productId}`,
    headers: auth(token),
  })
  return response.json().quantity as number
}

export async function restock(
  app: App,
  token: string,
  productId: string,
  quantity: number,
): Promise<void> {
  const response = await app.inject({
    method: 'POST',
    url: '/stock/movements',
    headers: auth(token),
    payload: { productId, type: 'restock', quantity },
  })
  if (response.statusCode !== 201) {
    throw new Error(`could not restock: ${response.statusCode} ${response.body}`)
  }
}
