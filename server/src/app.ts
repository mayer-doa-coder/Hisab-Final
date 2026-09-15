import Fastify from 'fastify'
import { registerAuthRoutes } from './modules/auth/routes.js'
import { requireAuth } from './modules/auth/requireAuth.js'
import { registerProductRoutes } from './modules/products/routes.js'
import { registerSyncRoutes } from './modules/sync/routes.js'

export function buildApp() {
  const app = Fastify({ logger: true })

  app.get('/', async () => ({ status: 'ok' }))

  // Step 21: the same "server is up" answer, but only for a logged-in caller —
  // proves the login guard (Steps 11–12) and the server work together.
  app.get('/health', { preHandler: requireAuth }, async () => ({ status: 'ok' }))

  registerAuthRoutes(app)
  registerProductRoutes(app)
  registerSyncRoutes(app)

  return app
}
