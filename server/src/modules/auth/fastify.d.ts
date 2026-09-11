import type { AuthSession } from './authService.js'

declare module 'fastify' {
  interface FastifyRequest {
    /** Set by requireAuth (routes.ts). Absent until that preHandler runs. */
    auth: AuthSession
  }
}
