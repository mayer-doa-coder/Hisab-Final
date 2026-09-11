import Fastify from 'fastify'
import { registerAuthRoutes } from './modules/auth/routes.js'

export function buildApp() {
  const app = Fastify({ logger: true })

  app.get('/', async () => ({ status: 'ok' }))
  registerAuthRoutes(app)

  return app
}
