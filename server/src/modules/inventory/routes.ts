import { randomUUID } from 'node:crypto'
import type { FastifyInstance } from 'fastify'
import { withTransaction } from '../../db/transaction.js'
import { ID_TAKEN, type EntityId } from '../../domain/id.js'
import { PRODUCT_NOT_FOUND } from '../../domain/product.js'
import { quantity } from '../../domain/quantity.js'
import {
  correctStock,
  damage,
  restock,
  returnStock,
  type StockMovement,
} from '../../domain/stock.js'
import { requireAuth } from '../auth/requireAuth.js'
import { findProduct } from '../products/productRepository.js'
import { currentStock, movementsOf, saveStandaloneMovement, stockOf } from './stockRepository.js'

/** Language-neutral code (D011). */
export const QUANTITY_REQUIRED = 'QUANTITY_REQUIRED'

interface MovementBody {
  id?: string
  productId: string
  type: 'restock' | 'damage' | 'return' | 'correction'
  /** Restock, damage or return: how much, positive, scaled by 1000. */
  quantity?: number
  /** Correction: what the shelf count found, scaled by 1000. */
  countedQuantity?: number
  note?: string
  occurredAt?: string
}

/**
 * The stock endpoints (Step 46). Behind the login guard; the shop always comes
 * from the session token, never the request (D015).
 *
 * Stock is never stored: every number these return is a SUM over the
 * movements (D002, D020). A sale's movements are written with the sale
 * (`POST /sales`); here are only the movements that stand on their own.
 */
export function registerInventoryRoutes(app: FastifyInstance): void {
  app.get('/stock', { onRequest: requireAuth }, async (request, reply) => {
    reply.send({ stock: await currentStock(request.auth.shopId) })
  })

  app.get<{ Params: { productId: string }; Querystring: { limit?: number } }>(
    '/stock/:productId',
    {
      onRequest: requireAuth,
      schema: {
        querystring: {
          type: 'object',
          properties: { limit: { type: 'integer', minimum: 1, maximum: 500, default: 100 } },
        },
      },
    },
    async (request, reply) => {
      const shopId = request.auth.shopId
      const product = await findProduct(shopId, request.params.productId)
      if (product === null) {
        reply.code(404).send({ code: PRODUCT_NOT_FOUND })
        return
      }
      reply.send({
        productId: product.id,
        quantity: await stockOf(shopId, product.id),
        movements: await movementsOf(shopId, product.id, request.query.limit ?? 100),
      })
    },
  )

  app.post<{ Body: MovementBody }>(
    '/stock/movements',
    {
      onRequest: requireAuth,
      schema: {
        body: {
          type: 'object',
          required: ['productId', 'type'],
          additionalProperties: false,
          properties: {
            id: { type: 'string', minLength: 1 },
            productId: { type: 'string', minLength: 1 },
            // No 'sale': stock only leaves in a sale through POST /sales.
            type: { type: 'string', enum: ['restock', 'damage', 'return', 'correction'] },
            quantity: { type: 'integer', minimum: 1 },
            countedQuantity: { type: 'integer', minimum: 0 },
            note: { type: 'string' },
            occurredAt: { type: 'string', format: 'date-time' },
          },
        },
      },
    },
    async (request, reply) => {
      const shopId = request.auth.shopId
      const body = request.body

      const product = await findProduct(shopId, body.productId)
      if (product === null || product.deletedAt !== null) {
        reply.code(404).send({ code: PRODUCT_NOT_FOUND })
        return
      }

      const amount = body.type === 'correction' ? body.countedQuantity : body.quantity
      if (amount === undefined) {
        reply.code(400).send({ code: QUANTITY_REQUIRED })
        return
      }

      const id = (body.id ?? randomUUID()) as EntityId
      const productId = product.id as EntityId
      const occurredAt = body.occurredAt === undefined ? new Date() : new Date(body.occurredAt)
      const note = body.note?.trim() || null

      const result = await withTransaction(async (db) => {
        // One count or delivery for a product at a time, so a shelf count is
        // measured against a ledger nobody else is writing to mid-way.
        await db.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`${shopId}:${productId}`])

        let movement: StockMovement
        switch (body.type) {
          case 'restock':
            movement = restock(productId, quantity(amount), occurredAt, note, id)
            break
          case 'damage':
            movement = damage(productId, quantity(amount), occurredAt, note, id)
            break
          case 'return':
            movement = returnStock(productId, quantity(amount), occurredAt, note, id)
            break
          case 'correction':
            movement = correctStock(
              productId,
              quantity(amount),
              await stockOf(shopId, productId, db),
              occurredAt,
              note,
              id,
            )
            break
        }

        const saved = await saveStandaloneMovement(db, shopId, movement)
        return { saved, stock: await stockOf(shopId, productId, db) }
      })

      if (result.saved.status === 'refused') {
        reply.code(409).send({ code: ID_TAKEN })
        return
      }
      reply
        .code(result.saved.status === 'created' ? 201 : 200)
        .send({ movement: result.saved.movement, quantity: result.stock })
    },
  )
}
