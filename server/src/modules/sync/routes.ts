import type { FastifyInstance } from 'fastify'
import { requireAuth } from '../auth/requireAuth.js'
import { applyEvent } from './processedEvents.js'
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
  // Step 15: accepts a batch of events, and never applies the same eventId
  // twice, even across separate requests (D004). Behind requireAuth like
  // every endpoint (D015) — sync events belong to whichever shop the token
  // resolves to, once real entities exist to check that against (M1).
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
      const results = request.body.events.map((event) => applyEvent(event.eventId))
      reply.send({ results })
    },
  )
}
