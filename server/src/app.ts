import Fastify from 'fastify'
import { registerAuthRoutes } from './modules/auth/routes.js'
import { registerSyncRoutes } from './modules/sync/routes.js'

export function buildApp() {
  const app = Fastify({ logger: true })

  app.get('/', async () => ({ status: 'ok' }))
  registerAuthRoutes(app)
  registerSyncRoutes(app)

  return app
}
