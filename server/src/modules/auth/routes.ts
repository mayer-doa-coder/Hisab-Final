import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify'
import { login, verifyToken } from './authService.js'

/** Language-neutral status code — see DECISIONS.md D011. Never localize this string itself. */
const AUTH_INVALID = 'AUTH_INVALID'

/**
 * Rejects the request unless it carries a valid `Authorization: Bearer <token>`
 * header, and attaches the session it resolves to. Every protected route uses
 * this — none of them ever read shop_id from the body or query string (D015).
 */
async function requireAuth(request: FastifyRequest, reply: FastifyReply): Promise<void> {
  const header = request.headers.authorization ?? ''
  const token = header.startsWith('Bearer ') ? header.slice('Bearer '.length) : null
  const session = token ? verifyToken(token) : null

  if (!session) {
    reply.code(401).send({ code: AUTH_INVALID })
    return
  }

  request.auth = session
}

export function registerAuthRoutes(app: FastifyInstance): void {
  app.post<{ Body: { email: string; password: string } }>(
    '/auth/login',
    {
      schema: {
        body: {
          type: 'object',
          required: ['email', 'password'],
          properties: {
            email: { type: 'string' },
            password: { type: 'string' },
          },
        },
      },
    },
    async (request, reply) => {
      const { email, password } = request.body
      const token = login(email, password)

      if (!token) {
        reply.code(401).send({ code: AUTH_INVALID })
        return
      }

      reply.send({ token })
    },
  )

  // Deliberately minimal: this is what Step 12 tests against, not a real
  // feature. It answers "who does this token belong to" and nothing else.
  // shop_id always comes from request.auth (the token) — a shop_id sent as a
  // query string, here or on any future route, is never read.
  app.get('/auth/me', { preHandler: requireAuth }, async (request, reply) => {
    reply.send(request.auth)
  })
}
