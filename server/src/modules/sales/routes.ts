import { randomUUID } from 'node:crypto'
import type { FastifyInstance } from 'fastify'
import { withTransaction } from '../../db/transaction.js'
import { ID_TAKEN, type EntityId } from '../../domain/id.js'
import { money } from '../../domain/money.js'
import { PRODUCT_NOT_FOUND } from '../../domain/product.js'
import { quantity } from '../../domain/quantity.js'
import {
  completeCashSale,
  completeCreditSale,
  reverseSale,
  type SaleLine,
  type SaleTransaction,
} from '../../domain/sale.js'
import { requireAuth } from '../auth/requireAuth.js'
import { findOrCreateCustomerByName } from '../customers/customerRepository.js'
import { findProduct } from '../products/productRepository.js'
import {
  findReversalOf,
  findSaleTransaction,
  listSaleTransactions,
  saveSaleTransaction,
} from './saleRepository.js'
import { ALREADY_REVERSED, CANNOT_REVERSE_A_REVERSAL, SALE_NOT_FOUND } from './saleValidation.js'

/** Language-neutral codes (D011). */
export const INVALID_SALE = 'INVALID_SALE'
export const CUSTOMER_REQUIRED = 'CUSTOMER_REQUIRED'

interface SaleBody {
  id?: string
  payment: 'cash' | 'credit'
  customerName?: string
  dueDate?: string
  occurredAt?: string
  lines: Array<{ productId: string; quantity: number; unitPricePoisha?: number }>
}

interface ReversalBody {
  id?: string
  occurredAt?: string
}

/** Thrown inside a transaction to roll it back and answer with this status and code. */
class Refusal extends Error {
  constructor(
    readonly statusCode: number,
    readonly code: string,
    readonly detail: Record<string, unknown> = {},
  ) {
    super(code)
  }
}

/**
 * The sale endpoints (Step 46). Every one is behind the login guard, and the
 * shop always comes from the session token — never from the body or query
 * string (D015). A sale id, a product id or a customer that belongs to another
 * shop simply is not found.
 *
 * The guard runs at `onRequest`, before the body is validated, so a caller
 * without a token is told 401 and nothing about what a valid body looks like.
 * (Fastify validates before `preHandler`; the older product and sync routes
 * still guard there — a hardening item for M7's security pass, Step 96.)
 *
 * Unknown fields in a body are stripped by Fastify, never used: a `total` or
 * a `shop_id` sent by a caller is thrown away before the handler runs.
 *
 * The server builds every sale with the same domain functions the phone uses
 * (`domain/sale.ts`), so the total, the stock movements and the baki entry
 * are computed here, never taken from the caller.
 */
export function registerSaleRoutes(app: FastifyInstance): void {
  app.post<{ Body: SaleBody }>(
    '/sales',
    {
      onRequest: requireAuth,
      schema: {
        body: {
          type: 'object',
          required: ['payment', 'lines'],
          additionalProperties: false,
          properties: {
            id: { type: 'string', minLength: 1 },
            payment: { type: 'string', enum: ['cash', 'credit'] },
            customerName: { type: 'string', minLength: 1 },
            dueDate: { type: 'string', pattern: '^\\d{4}-\\d{2}-\\d{2}$' },
            occurredAt: { type: 'string', format: 'date-time' },
            lines: {
              type: 'array',
              minItems: 1,
              items: {
                type: 'object',
                required: ['productId', 'quantity'],
                additionalProperties: false,
                properties: {
                  productId: { type: 'string', minLength: 1 },
                  quantity: { type: 'integer', minimum: 1 },
                  unitPricePoisha: { type: 'integer', minimum: 0 },
                },
              },
            },
          },
        },
      },
    },
    async (request, reply) => {
      const shopId = request.auth.shopId
      const body = request.body
      const saleId = (body.id ?? randomUUID()) as EntityId

      try {
        const outcome = await withTransaction(async (db) => {
          // A retry of a sale already stored is answered with that sale (D004).
          const existing = await findSaleTransaction(shopId, saleId, db)
          if (existing !== null) return { status: 200, transaction: existing }

          const lines: SaleLine[] = []
          for (const line of body.lines) {
            const product = await findProduct(shopId, line.productId)
            if (product === null || product.deletedAt !== null) {
              throw new Refusal(404, PRODUCT_NOT_FOUND, { productId: line.productId })
            }
            lines.push({
              productId: product.id as EntityId,
              quantity: quantity(line.quantity),
              // The product's own price unless the caller sells it for less.
              unitPrice: money(line.unitPricePoisha ?? product.sellingPricePoisha),
            })
          }

          const occurredAt = body.occurredAt === undefined ? new Date() : new Date(body.occurredAt)
          let transaction: SaleTransaction
          try {
            if (body.payment === 'cash') {
              transaction = completeCashSale(shopId, lines, occurredAt, saleId)
            } else {
              if (body.customerName === undefined || body.customerName.trim() === '') {
                throw new Refusal(400, CUSTOMER_REQUIRED)
              }
              const customer = await findOrCreateCustomerByName(
                shopId,
                body.customerName,
                randomUUID(),
                db,
              )
              transaction = completeCreditSale(
                shopId,
                customer.id as EntityId,
                lines,
                occurredAt,
                body.dueDate ?? null,
                saleId,
              )
            }
          } catch (error) {
            if (error instanceof RangeError) {
              throw new Refusal(400, INVALID_SALE, { message: error.message })
            }
            throw error
          }

          const saved = await saveSaleTransaction(db, shopId, transaction)
          if (saved.status === 'refused' || saved.status === 'already-reversed') {
            throw new Refusal(409, ID_TAKEN)
          }
          return { status: saved.status === 'created' ? 201 : 200, transaction: saved.transaction }
        })
        reply.code(outcome.status).send({ sale: outcome.transaction })
      } catch (error) {
        if (error instanceof Refusal) {
          reply.code(error.statusCode).send({ code: error.code, ...error.detail })
          return
        }
        throw error
      }
    },
  )

  // Step 43 on the server: a sale is undone by recording its opposite, never
  // by editing or deleting it. A credit sale's baki is cleared in the same
  // transaction as its stock comes back (D021).
  app.post<{ Params: { id: string }; Body: ReversalBody | undefined }>(
    '/sales/:id/reversal',
    {
      onRequest: requireAuth,
      schema: {
        body: {
          type: ['object', 'null'],
          additionalProperties: false,
          properties: {
            id: { type: 'string', minLength: 1 },
            occurredAt: { type: 'string', format: 'date-time' },
          },
        },
      },
    },
    async (request, reply) => {
      const shopId = request.auth.shopId
      const body = request.body ?? {}
      const reversalId = (body.id ?? randomUUID()) as EntityId

      try {
        const outcome = await withTransaction(async (db) => {
          const original = await findSaleTransaction(shopId, request.params.id, db)
          if (original === null) throw new Refusal(404, SALE_NOT_FOUND)

          // A retry of a reversal already stored is answered with it (D004).
          const retried = await findSaleTransaction(shopId, reversalId, db)
          if (retried !== null && retried.sale.reversesSaleId === original.sale.id) {
            return { status: 200, transaction: retried }
          }

          if (original.sale.reversesSaleId !== null)
            throw new Refusal(409, CANNOT_REVERSE_A_REVERSAL)
          const existing = await findReversalOf(shopId, original.sale.id, db)
          if (existing !== null) throw new Refusal(409, ALREADY_REVERSED, { reversalId: existing })

          const occurredAt = body.occurredAt === undefined ? new Date() : new Date(body.occurredAt)
          const reversal = reverseSale(original, occurredAt, reversalId)
          const saved = await saveSaleTransaction(db, shopId, reversal)
          if (saved.status === 'already-reversed') throw new Refusal(409, ALREADY_REVERSED)
          if (saved.status === 'refused') throw new Refusal(409, ID_TAKEN)
          return { status: saved.status === 'created' ? 201 : 200, transaction: saved.transaction }
        })
        reply.code(outcome.status).send({ sale: outcome.transaction })
      } catch (error) {
        if (error instanceof Refusal) {
          reply.code(error.statusCode).send({ code: error.code, ...error.detail })
          return
        }
        throw error
      }
    },
  )

  app.get<{ Querystring: { limit?: number } }>(
    '/sales',
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
      const sales = await listSaleTransactions(request.auth.shopId, request.query.limit ?? 100)
      reply.send({ sales })
    },
  )

  app.get<{ Params: { id: string } }>(
    '/sales/:id',
    { onRequest: requireAuth },
    async (request, reply) => {
      const transaction = await findSaleTransaction(request.auth.shopId, request.params.id)
      if (transaction === null) {
        reply.code(404).send({ code: SALE_NOT_FOUND })
        return
      }
      const reversedBy = await findReversalOf(request.auth.shopId, transaction.sale.id)
      reply.send({ sale: transaction, reversedBy })
    },
  )
}
