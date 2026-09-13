import type { FastifyInstance } from 'fastify'
import { login } from './authService.js'
import { requireAuth, AUTH_INVALID } from './requireAuth.js'

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
