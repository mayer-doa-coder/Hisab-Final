import type { FastifyInstance } from 'fastify'
import type { SyncEventEnvelope } from '../../domain/syncEvent.js'
import { requireAuth } from '../auth/requireAuth.js'
import { applyEvent, changesSince, INITIAL_CURSOR } from './syncService.js'

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
  // Steps 15, 17 and 32: accepts a batch of changes a phone made, possibly
  // offline, and applies them to the real data. The same eventId is never
  // applied twice (D004), and an update or delete whose base_revision has
  // moved on is refused with REVISION_CONFLICT rather than overwriting
  // someone else's change (D017). Behind requireAuth like everything else —
  // the shop comes from the token, never the request (D015).
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
      // One at a time, in the order the phone sent them: a create and the
      // edit that follows it must not race each other.
      const results = []
      for (const event of request.body.events) {
        results.push(await applyEvent(request.auth.shopId, event))
      }
      reply.send({ results })
    },
  )

  // Steps 16 and 33: everything this shop has to catch up on, plus the cursor
  // to ask from next time. No cursor means "from the beginning". Scoped to
  // the requester's own shop (D015).
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

      const { events, cursor } = await changesSince(request.auth.shopId, after)
      reply.send({ events, cursor })
    },
  )
}
