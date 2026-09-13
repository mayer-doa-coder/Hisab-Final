import type { FastifyReply, FastifyRequest } from 'fastify'
import { verifyToken } from './authService.js'

/** Language-neutral status code — see DECISIONS.md D011. Never localize this string itself. */
export const AUTH_INVALID = 'AUTH_INVALID'

/**
 * Rejects the request unless it carries a valid `Authorization: Bearer <token>`
 * header, and attaches the session it resolves to. Every protected route uses
 * this — none of them ever read shop_id from the body or query string (D015).
 *
 * Pulled out of auth/routes.ts once a second module (sync) needed the same
 * guard — D026: add structure when a real second use case shows up.
 */
export async function requireAuth(request: FastifyRequest, reply: FastifyReply): Promise<void> {
  const header = request.headers.authorization ?? ''
  const token = header.startsWith('Bearer ') ? header.slice('Bearer '.length) : null
  const session = token ? verifyToken(token) : null

  if (!session) {
    reply.code(401).send({ code: AUTH_INVALID })
    return
  }

  request.auth = session
}
