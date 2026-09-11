import Fastify from 'fastify'

export function buildApp() {
  const app = Fastify({ logger: true })

  app.get('/', async () => ({ status: 'ok' }))

  return app
}
