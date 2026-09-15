import { runMigrations } from './migrate.js'
import { closePool } from './pool.js'

/** `npm run migrate` — brings the database up to date and says what it did. */
try {
  const applied = await runMigrations()
  console.log(applied.length > 0 ? `applied: ${applied.join(', ')}` : 'database already up to date')
} catch (error) {
  console.error('migration failed:', error)
  process.exitCode = 1
} finally {
  await closePool()
}
