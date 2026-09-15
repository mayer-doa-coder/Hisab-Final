import { randomUUID } from 'node:crypto'
import type { FastifyInstance } from 'fastify'
import {
  DEFAULT_PRODUCT_UNIT,
  PRODUCT_NOT_FOUND,
  PRODUCT_UNITS,
  type ProductInput,
} from '../../domain/product.js'
import { requireAuth } from '../auth/requireAuth.js'
import { createProduct, listProducts, updateProduct } from './productRepository.js'

/** Creating: only a name and a selling price are required; the rest have sensible defaults. */
const createFields = {
  name: { type: 'string', minLength: 1 },
  aliases: { type: 'array', items: { type: 'string' }, default: [] },
  unit: { type: 'string', enum: [...PRODUCT_UNITS], default: DEFAULT_PRODUCT_UNIT },
  sellingPricePoisha: { type: 'integer', minimum: 0 },
  purchasePricePoisha: { type: ['integer', 'null'], minimum: 0, default: null },
  active: { type: 'boolean', default: true },
} as const

/**
 * Replacing: every field must be sent, and none of them has a default. A
 * replace that quietly filled in defaults would wipe a shopkeeper's purchase
 * price simply because the caller forgot to include it; this way the request
 * is refused instead.
 */
const replaceFields = {
  name: { type: 'string', minLength: 1 },
  aliases: { type: 'array', items: { type: 'string' } },
  unit: { type: 'string', enum: [...PRODUCT_UNITS] },
  sellingPricePoisha: { type: 'integer', minimum: 0 },
  purchasePricePoisha: { type: ['integer', 'null'], minimum: 0 },
  active: { type: 'boolean' },
} as const

interface ProductBody extends ProductInput {
  id?: string
}

interface EditBody extends ProductInput {
  baseRevision: number
}

/**
 * The product endpoints (Step 30). Every one of them is behind the login
 * guard, and the shop is always taken from the session token — a shop_id in
 * the body or query string is never read, so one shop's token can never
 * reach another shop's products (D015).
 */
export function registerProductRoutes(app: FastifyInstance): void {
  app.post<{ Body: ProductBody }>(
    '/products',
    {
      preHandler: requireAuth,
      schema: {
        body: {
          type: 'object',
          required: ['name', 'sellingPricePoisha'],
          properties: { id: { type: 'string', minLength: 1 }, ...createFields },
        },
      },
    },
    async (request, reply) => {
      const { id, ...input } = request.body
      // The phone makes the id when the product is created offline (D018);
      // a caller without one gets a server-made id instead.
      const outcome = await createProduct(request.auth.shopId, id ?? randomUUID(), input)
      reply.code(outcome.status === 'created' ? 201 : 200).send({ product: outcome.product })
    },
  )

  // PUT, not PATCH: this replaces the whole product, which is also the shape
  // a sync event carries (Step 32). The revision it was edited from must come
  // with it, or a stale write could overwrite a newer one (D017).
  app.put<{ Params: { id: string }; Body: EditBody }>(
    '/products/:id',
    {
      preHandler: requireAuth,
      schema: {
        body: {
          type: 'object',
          required: [
            'baseRevision',
            'name',
            'aliases',
            'unit',
            'sellingPricePoisha',
            'purchasePricePoisha',
            'active',
          ],
          properties: { baseRevision: { type: 'integer', minimum: 1 }, ...replaceFields },
        },
      },
    },
    async (request, reply) => {
      const { baseRevision, ...input } = request.body
      const outcome = await updateProduct(
        request.auth.shopId,
        request.params.id,
        baseRevision,
        input,
      )

      if (outcome.status === 'conflict') {
        reply.code(409).send({ code: outcome.code })
        return
      }
      if (outcome.status === 'not-found') {
        reply.code(404).send({ code: PRODUCT_NOT_FOUND })
        return
      }
      reply.send({ product: outcome.product })
    },
  )

  app.get<{ Querystring: { q?: string; includeInactive?: boolean } }>(
    '/products',
    {
      preHandler: requireAuth,
      schema: {
        querystring: {
          type: 'object',
          properties: {
            q: { type: 'string' },
            includeInactive: { type: 'boolean', default: false },
          },
        },
      },
    },
    async (request, reply) => {
      const products = await listProducts(request.auth.shopId, {
        query: request.query.q,
        includeInactive: request.query.includeInactive,
      })
      reply.send({ products })
    },
  )
}
