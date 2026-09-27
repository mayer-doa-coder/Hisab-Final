import { randomUUID } from 'node:crypto'
import type { FastifyInstance } from 'fastify'
import { CUSTOMER_NOT_FOUND } from '../../domain/customer.js'
import type { EntityId } from '../../domain/id.js'
import { requireAuth } from '../auth/requireAuth.js'
import { balanceOf } from '../baki/bakiRepository.js'
import {
  createCustomer,
  findCustomer,
  findCustomerByName,
  listCustomers,
} from './customerRepository.js'

/** Language-neutral code (D011). */
export const CUSTOMER_NAME_TAKEN = 'CUSTOMER_NAME_TAKEN'

interface CustomerBody {
  id?: string
  name: string
  phone?: string | null
}

/**
 * The customer endpoints (Step 58). Every one is behind the login guard, and
 * the shop always comes from the session token, never the request (D015).
 *
 * A name that is already taken in this shop is answered, not merged and not
 * repeated: the caller is told who already has it, the same rule a credit
 * sale and the phone's own Add Customer screen already use (D035, D042) — two
 * rows for one person would split their baki, which is the failure that
 * actually loses a shopkeeper money.
 */
export function registerCustomerRoutes(app: FastifyInstance): void {
  app.post<{ Body: CustomerBody }>(
    '/customers',
    {
      onRequest: requireAuth,
      schema: {
        body: {
          type: 'object',
          required: ['name'],
          additionalProperties: false,
          properties: {
            id: { type: 'string', minLength: 1 },
            name: { type: 'string', minLength: 1 },
            phone: { type: ['string', 'null'] },
          },
        },
      },
    },
    async (request, reply) => {
      const shopId = request.auth.shopId
      const name = request.body.name.trim()

      const existing = await findCustomerByName(shopId, name)
      if (existing !== null) {
        reply.code(409).send({ code: CUSTOMER_NAME_TAKEN, customer: existing })
        return
      }

      const id = (request.body.id ?? randomUUID()) as EntityId
      const phone = request.body.phone?.trim() || null
      const created = await createCustomer(shopId, { id, name, phone })
      if (!created) {
        reply.code(409).send({ code: 'ID_TAKEN' })
        return
      }
      reply.code(201).send({ customer: (await findCustomer(shopId, id))! })
    },
  )

  app.get<{ Querystring: { q?: string } }>(
    '/customers',
    { onRequest: requireAuth },
    async (request, reply) => {
      reply.send({ customers: await listCustomers(request.auth.shopId, request.query.q ?? '') })
    },
  )

  app.get<{ Params: { id: string } }>(
    '/customers/:id',
    { onRequest: requireAuth },
    async (request, reply) => {
      const shopId = request.auth.shopId
      const customer = await findCustomer(shopId, request.params.id)
      if (customer === null) {
        reply.code(404).send({ code: CUSTOMER_NOT_FOUND })
        return
      }
      reply.send({ customer, balance: await balanceOf(shopId, customer.id) })
    },
  )
}
