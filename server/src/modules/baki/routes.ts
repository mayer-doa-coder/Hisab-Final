import { randomUUID } from 'node:crypto'
import type { FastifyInstance } from 'fastify'
import { withTransaction } from '../../db/transaction.js'
import { addCredit, receivePayment, reverseEntry } from '../../domain/baki.js'
import type { BakiEntry } from '../../domain/bakiEntry.js'
import { CUSTOMER_NOT_FOUND } from '../../domain/customer.js'
import type { EntityId } from '../../domain/id.js'
import { money } from '../../domain/money.js'
import { requireAuth } from '../auth/requireAuth.js'
import { findCustomer } from '../customers/customerRepository.js'
import { ALREADY_REVERSED, BAKI_ENTRY_NOT_FOUND, NOT_REVERSIBLE } from './bakiValidation.js'
import {
  balanceOf,
  entriesOf,
  findBakiEntry,
  findReversalOfEntry,
  saveStandaloneEntry,
} from './bakiRepository.js'

interface EntryBody {
  id?: string
  customerId: string
  type: 'credit' | 'payment'
  /** How much, as a positive amount — the endpoint decides the sign from `type` (D041). */
  amountPoisha: number
  dueDate?: string
  occurredAt?: string
}

interface ReversalBody {
  id?: string
  occurredAt?: string
}

/**
 * Baki endpoints (Step 58): a customer's ledger, adding baki, receiving a
 * payment, and undoing either. Every one is behind the login guard, and the
 * shop always comes from the session token (D015).
 *
 * A credit sale's own baki entry is never made or undone here — it travels
 * with the sale (`POST /sales`, `POST /sales/:id/reversal`), together with the
 * sale and its stock (D021, D038).
 */
export function registerBakiRoutes(app: FastifyInstance): void {
  app.get<{ Params: { customerId: string } }>(
    '/customers/:customerId/baki',
    { onRequest: requireAuth },
    async (request, reply) => {
      const shopId = request.auth.shopId
      const customer = await findCustomer(shopId, request.params.customerId)
      if (customer === null) {
        reply.code(404).send({ code: CUSTOMER_NOT_FOUND })
        return
      }
      reply.send({
        balance: await balanceOf(shopId, customer.id),
        entries: await entriesOf(shopId, customer.id, 200),
      })
    },
  )

  app.post<{ Body: EntryBody }>(
    '/baki/entries',
    {
      onRequest: requireAuth,
      schema: {
        body: {
          type: 'object',
          required: ['customerId', 'type', 'amountPoisha'],
          additionalProperties: false,
          properties: {
            id: { type: 'string', minLength: 1 },
            customerId: { type: 'string', minLength: 1 },
            type: { type: 'string', enum: ['credit', 'payment'] },
            amountPoisha: { type: 'integer', minimum: 1 },
            dueDate: { type: 'string', pattern: '^\\d{4}-\\d{2}-\\d{2}$' },
            occurredAt: { type: 'string', format: 'date-time' },
          },
        },
      },
    },
    async (request, reply) => {
      const shopId = request.auth.shopId
      const body = request.body

      const customer = await findCustomer(shopId, body.customerId)
      if (customer === null) {
        reply.code(404).send({ code: CUSTOMER_NOT_FOUND })
        return
      }

      const id = (body.id ?? randomUUID()) as EntityId
      const customerId = customer.id as EntityId
      const occurredAt = body.occurredAt === undefined ? new Date() : new Date(body.occurredAt)
      const amount = money(body.amountPoisha)

      const entry: BakiEntry =
        body.type === 'credit'
          ? addCredit(customerId, amount, occurredAt, body.dueDate ?? null, id)
          : receivePayment(customerId, amount, occurredAt, id)

      const saved = await withTransaction((db) => saveStandaloneEntry(db, shopId, entry))
      if (saved.status === 'refused') {
        reply.code(409).send({ code: 'ID_TAKEN' })
        return
      }
      if (saved.status === 'already-reversed') {
        // saveStandaloneEntry only returns this for an `entry_reversal`; this
        // route only ever passes a `credit` or `payment` entry, so this branch
        // cannot actually run. It exists to satisfy the shared return type.
        reply.code(500).send()
        return
      }
      reply.code(saved.status === 'created' ? 201 : 200).send({
        entry: saved.entry,
        balance: await balanceOf(shopId, customerId),
      })
    },
  )

  app.post<{ Params: { id: string }; Body: ReversalBody }>(
    '/baki/entries/:id/reversal',
    {
      onRequest: requireAuth,
      schema: {
        body: {
          type: 'object',
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
      const original = await findBakiEntry(shopId, request.params.id)
      if (original === null) {
        reply.code(404).send({ code: BAKI_ENTRY_NOT_FOUND })
        return
      }
      if (original.type !== 'credit' && original.type !== 'payment') {
        reply.code(409).send({ code: NOT_REVERSIBLE })
        return
      }
      // A fast-path check outside the transaction; the unique index inside
      // saveStandaloneEntry is the guarantee two devices racing cannot both
      // win (D043).
      if ((await findReversalOfEntry(shopId, original.id)) !== null) {
        reply.code(409).send({ code: ALREADY_REVERSED })
        return
      }

      const id = (request.body.id ?? randomUUID()) as EntityId
      const occurredAt =
        request.body.occurredAt === undefined ? new Date() : new Date(request.body.occurredAt)
      const reversal = reverseEntry(original, occurredAt, id)

      const saved = await withTransaction((db) => saveStandaloneEntry(db, shopId, reversal))
      if (saved.status === 'already-reversed') {
        reply.code(409).send({ code: ALREADY_REVERSED })
        return
      }
      if (saved.status === 'refused') {
        reply.code(409).send({ code: 'ID_TAKEN' })
        return
      }
      reply.code(saved.status === 'created' ? 201 : 200).send({
        entry: saved.entry,
        balance: await balanceOf(shopId, original.customerId),
      })
    },
  )
}
