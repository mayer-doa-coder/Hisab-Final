import type { FastifyInstance } from 'fastify'
import { requireAuth } from '../auth/requireAuth.js'
import { applyEvent, changesSince, INITIAL_CURSOR } from './eventLog.js'
import type { SyncEventEnvelope } from '../../domain/syncEvent.js'

const eventSchema = {
  type: 'object',
  required: ['eventId', 'entityType', 'entityId', 'operation', 'baseRevision', 'clientTimestamp'],
  properties: {
    eventId: { type: 'string' },
    entityType: { type: 'string' },
    entityId: { type: 'string' },
    operation: { type: 'string', enum: ['create', 'update', 'delete'] },
    payload: {},
    baseRevision: { type: ['integer', 'null'] },
    clientTimestamp: { type: 'string' },
  },
} as const

export function registerSyncRoutes(app: FastifyInstance): void {
  // Step 15 + 17: accepts a batch of events. Never applies the same eventId
  // twice, even across separate requests (D004). An update/delete whose
  // base_revision no longer matches the entity's current revision is
  // rejected with REVISION_CONFLICT, not silently applied (D017). Behind
  // requireAuth like every endpoint (D015) — shop_id always comes from the
  // token, never the request body.
  app.post<{ Body: { events: SyncEventEnvelope[] } }>(
    '/sync/push',
    {
      preHandler: requireAuth,
      schema: {
        body: {
          type: 'object',
          required: ['events'],
          properties: {
            events: { type: 'array', items: eventSchema },
          },
        },
      },
    },
    async (request, reply) => {
      const results = request.body.events.map((event) => applyEvent(request.auth.shopId, event))
      reply.send({ results })
    },
  )

  // Step 16: returns every change after the given cursor, plus the cursor to
  // pull from next time. No `after` means "from the beginning" — everything
  // this shop has. Scoped to the requester's own shop, same as every other
  // endpoint (D015) — never another shop's events.
  app.get<{ Querystring: { after?: string } }>(
    '/sync/changes',
    {
      preHandler: requireAuth,
      schema: {
        querystring: {
          type: 'object',
          properties: {
            after: { type: 'string' },
          },
        },
      },
    },
    async (request, reply) => {
      let after = INITIAL_CURSOR
      if (request.query.after !== undefined) {
        after = Number(request.query.after)
        if (!Number.isInteger(after) || after < 0) {
          reply.code(400).send({ code: 'INVALID_CURSOR' })
          return
        }
      }

      const { events, cursor } = changesSince(request.auth.shopId, after)
      reply.send({ events, cursor })
    },
  )
}
