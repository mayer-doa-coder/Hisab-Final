import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { buildApp } from '../../app.js'
import { closePool } from '../../db/pool.js'
import { runMigrations } from '../../db/migrate.js'

// Needs a Postgres to talk to — see README.md, "Run It Yourself".
before(async () => {
  await runMigrations()
})

after(async () => {
  await closePool()
})

/** Marks every row this file creates, so tests never trip over each other. */
const tag = () => randomUUID().slice(0, 8)

async function loginToken(
  app: ReturnType<typeof buildApp>,
  email = 'rahim@example.com',
  password = 'correct-horse-1',
): Promise<string> {
  const response = await app.inject({
    method: 'POST',
    url: '/auth/login',
    payload: { email, password },
  })
  return response.json().token as string
}

function productPayload(name: string, overrides: Record<string, unknown> = {}) {
  return {
    id: randomUUID(),
    name,
    aliases: [],
    unit: 'piece',
    sellingPricePoisha: 2000,
    ...overrides,
  }
}

async function create(
  app: ReturnType<typeof buildApp>,
  token: string,
  payload: Record<string, unknown>,
) {
  return app.inject({
    method: 'POST',
    url: '/products',
    headers: { authorization: `Bearer ${token}` },
    payload,
  })
}

/** A whole product, as a replace must send it — every field, nothing implied. */
function replacement(name: string, overrides: Record<string, unknown> = {}) {
  return {
    baseRevision: 1,
    name,
    aliases: [],
    unit: 'piece',
    sellingPricePoisha: 2000,
    purchasePricePoisha: null,
    active: true,
    ...overrides,
  }
}

async function replace(
  app: ReturnType<typeof buildApp>,
  token: string,
  id: string,
  payload: Record<string, unknown>,
) {
  return app.inject({
    method: 'PUT',
    url: `/products/${id}`,
    headers: { authorization: `Bearer ${token}` },
    payload,
  })
}

async function list(app: ReturnType<typeof buildApp>, token: string, query = '') {
  return app.inject({
    method: 'GET',
    url: `/products${query}`,
    headers: { authorization: `Bearer ${token}` },
  })
}

// --- Login is required everywhere (D015) ---

test('the product endpoints all require a token', async () => {
  const app = buildApp()

  const post = await app.inject({ method: 'POST', url: '/products', payload: productPayload('x') })
  const get = await app.inject({ method: 'GET', url: '/products' })
  const put = await app.inject({
    method: 'PUT',
    url: '/products/whatever',
    payload: replacement('x'),
  })

  assert.equal(post.statusCode, 401)
  assert.equal(get.statusCode, 401)
  assert.equal(put.statusCode, 401)
})

// --- Create ---

test('creating a product stores it at revision 1 and keeps the id the phone made', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const payload = productPayload(`চিনি ${tag()}`, {
    aliases: ['chini', 'sugar'],
    unit: 'kg',
    sellingPricePoisha: 8500,
    purchasePricePoisha: 8000,
  })

  const response = await create(app, token, payload)

  assert.equal(response.statusCode, 201)
  const { product } = response.json()
  assert.equal(product.id, payload.id)
  assert.equal(product.name, payload.name)
  assert.deepEqual(product.aliases, ['chini', 'sugar'])
  assert.equal(product.unit, 'kg')
  assert.equal(product.sellingPricePoisha, 8500)
  assert.equal(product.purchasePricePoisha, 8000)
  assert.equal(product.revision, 1)
  assert.equal(product.active, true)
  assert.equal(product.deletedAt, null)
  assert.equal(product.shopId, 'shop-1')
})

test('a product without a purchase price keeps it unset, not zero', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  const response = await create(app, token, productPayload(`Coke ${tag()}`))

  assert.equal(response.json().product.purchasePricePoisha, null)
})

// The phone may resend a create it is not sure arrived (D004).
test('creating the same id twice stores one product, not two', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const payload = productPayload(`Repeat ${tag()}`)

  const first = await create(app, token, payload)
  const second = await create(app, token, payload)

  assert.equal(first.statusCode, 201)
  assert.equal(second.statusCode, 200)
  assert.equal(second.json().product.id, payload.id)
  assert.equal(second.json().product.revision, 1)

  const names = list(app, token, `?q=${encodeURIComponent(payload.name)}`)
  assert.equal((await names).json().products.length, 1)
})

test('a product needs a name and a selling price, and the unit must be a known one', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  const noName = await create(app, token, { sellingPricePoisha: 100 })
  const emptyName = await create(app, token, productPayload(''))
  const noPrice = await create(app, token, { name: 'x' })
  const negativePrice = await create(app, token, productPayload('x', { sellingPricePoisha: -1 }))
  const oddUnit = await create(app, token, productPayload('x', { unit: 'barrel' }))

  assert.equal(noName.statusCode, 400)
  assert.equal(emptyName.statusCode, 400)
  assert.equal(noPrice.statusCode, 400)
  assert.equal(negativePrice.statusCode, 400)
  assert.equal(oddUnit.statusCode, 400)
})

// --- List and search ---

test('the list shows this shop products, ordered by name', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const marker = tag()
  await create(app, token, productPayload(`Alpha ${marker}`))
  await create(app, token, productPayload(`Beta ${marker}`))

  const names = (await list(app, token, `?q=${marker}`))
    .json()
    .products.map((p: { name: string }) => p.name)

  assert.deepEqual(names, [`Alpha ${marker}`, `Beta ${marker}`])
})

test('search matches part of the name, ignoring case', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const marker = tag()
  await create(app, token, productPayload(`Biscuit ${marker}`))

  const found = (await list(app, token, `?q=BISCUIT+${marker}`)).json().products
  assert.equal(found.length, 1)
  assert.equal(found[0].name, `Biscuit ${marker}`)
})

test('search matches an alias, which is not the name shown', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const marker = tag()
  await create(
    app,
    token,
    productPayload(`চিনি ${marker}`, { aliases: [`chini${marker}`, 'sugar'] }),
  )

  const found = (await list(app, token, `?q=chini${marker}`)).json().products

  assert.equal(found.length, 1)
  assert.equal(found[0].name, `চিনি ${marker}`)
})

test('a search that matches nothing returns nothing', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  assert.deepEqual((await list(app, token, `?q=nothing-${tag()}`)).json().products, [])
})

test('inactive products are hidden unless asked for', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const marker = tag()
  await create(app, token, productPayload(`Old stock ${marker}`, { active: false }))

  const byDefault = (await list(app, token, `?q=${marker}`)).json().products
  const including = (await list(app, token, `?q=${marker}&includeInactive=true`)).json().products

  assert.deepEqual(byDefault, [])
  assert.equal(including.length, 1)
  assert.equal(including[0].active, false)
})

// --- Edit ---

test('editing a product saves the change and moves the revision forward', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const payload = productPayload(`Coke ${tag()}`, { sellingPricePoisha: 2000 })
  await create(app, token, payload)

  const response = await replace(
    app,
    token,
    payload.id,
    replacement(`${payload.name} 500ml`, {
      aliases: ['cook'],
      unit: 'bottle',
      sellingPricePoisha: 2500,
    }),
  )

  assert.equal(response.statusCode, 200)
  const { product } = response.json()
  assert.equal(product.name, `${payload.name} 500ml`)
  assert.equal(product.sellingPricePoisha, 2500)
  assert.deepEqual(product.aliases, ['cook'])
  assert.equal(product.revision, 2)
})

// D017: the second of two edits made from the same revision is refused.
test('an edit from a stale revision is rejected and changes nothing', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const payload = productPayload(`Stale ${tag()}`)
  await create(app, token, payload)

  const edit = (name: string) => replace(app, token, payload.id, replacement(name))

  const first = await edit(`${payload.name} edited`)
  const second = await edit(`${payload.name} overwritten`)

  assert.equal(first.statusCode, 200)
  assert.equal(second.statusCode, 409)
  assert.deepEqual(second.json(), { code: 'REVISION_CONFLICT' })

  const stored = (await list(app, token, `?q=${encodeURIComponent(payload.name)}`)).json()
    .products[0]
  assert.equal(stored.name, `${payload.name} edited`)
  assert.equal(stored.revision, 2)
})

test('editing a product that does not exist says so', async () => {
  const app = buildApp()
  const token = await loginToken(app)

  const response = await replace(app, token, randomUUID(), replacement('x'))

  assert.equal(response.statusCode, 404)
  assert.deepEqual(response.json(), { code: 'PRODUCT_NOT_FOUND' })
})

// A replace is all-or-nothing on purpose: a caller that forgets the purchase
// price must be told, not have it quietly cleared.
test('a replace that leaves out a field is refused, and nothing changes', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const payload = productPayload(`Partial ${tag()}`, { purchasePricePoisha: 8000 })
  await create(app, token, payload)

  const withoutPurchasePrice = {
    baseRevision: 1,
    name: payload.name,
    aliases: [],
    unit: 'piece',
    sellingPricePoisha: 9000,
    active: true,
  }
  const response = await replace(app, token, payload.id, withoutPurchasePrice)

  assert.equal(response.statusCode, 400)

  const stored = (await list(app, token, `?q=${encodeURIComponent(payload.name)}`)).json()
    .products[0]
  assert.equal(stored.purchasePricePoisha, 8000)
  assert.equal(stored.revision, 1)
})

// --- One shop never reaches another shop's products: Step 30's check ---

test('another shop token can neither see nor edit this shop products', async () => {
  const app = buildApp()
  const shopOneToken = await loginToken(app)
  const shopTwoToken = await loginToken(app, 'karim@example.com', 'correct-horse-2')
  const payload = productPayload(`Private ${tag()}`)
  await create(app, shopOneToken, payload)

  const seenByOther = (
    await list(app, shopTwoToken, `?q=${encodeURIComponent(payload.name)}`)
  ).json().products
  const editByOther = await replace(app, shopTwoToken, payload.id, replacement('stolen'))

  assert.deepEqual(seenByOther, [])
  assert.equal(editByOther.statusCode, 404)

  const stillMine = (await list(app, shopOneToken, `?q=${encodeURIComponent(payload.name)}`)).json()
    .products[0]
  assert.equal(stillMine.name, payload.name)
  assert.equal(stillMine.revision, 1)
})

// A shop_id sent by the client is ignored, exactly as in GET /auth/me (D015).
test('a shop_id in the request body or query string is ignored', async () => {
  const app = buildApp()
  const token = await loginToken(app)
  const payload = productPayload(`Spoof ${tag()}`, { shopId: 'shop-2', shop_id: 'shop-2' })

  const created = await create(app, token, payload)

  assert.equal(created.json().product.shopId, 'shop-1')

  const listed = (
    await list(app, token, `?q=${encodeURIComponent(payload.name)}&shop_id=shop-2`)
  ).json().products
  assert.equal(listed.length, 1)
  assert.equal(listed[0].shopId, 'shop-1')
})
