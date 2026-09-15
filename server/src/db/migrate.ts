import { readdir, readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { getPool } from './pool.js'

/** `server/migrations`, reached from the compiled file in `server/dist/db`. */
const MIGRATIONS_DIR = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'migrations',
)

/**
 * A lock held in the database itself while migrations run. Two servers
 * starting at once — or two test files, which is how this was found — would
 * otherwise both try to create the same table, and one would fail. With the
 * lock, the second waits and then finds there is nothing left to do.
 *
 * The number is arbitrary; it only has to be the same everywhere.
 */
const MIGRATION_LOCK_KEY = 827431

/**
 * Applies every migration that has not run yet, in filename order, and
 * records it. Running it again does nothing — which is what makes it safe to
 * run on every deploy and at the start of a test run.
 *
 * Each migration runs inside its own transaction, so a failing one leaves the
 * database exactly as it was rather than half-changed.
 *
 * Deliberately hand-written rather than a migration library: plain .sql files
 * are readable by anyone who knows SQL, and this is the whole mechanism
 * (D006, D030).
 */
export async function runMigrations(): Promise<string[]> {
  const client = await getPool().connect()
  const applied: string[] = []

  try {
    await client.query('SELECT pg_advisory_lock($1)', [MIGRATION_LOCK_KEY])

    await client.query(
      `CREATE TABLE IF NOT EXISTS schema_migrations (
         name TEXT PRIMARY KEY,
         applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
       )`,
    )

    const files = (await readdir(MIGRATIONS_DIR)).filter((file) => file.endsWith('.sql')).sort()
    const { rows } = await client.query<{ name: string }>('SELECT name FROM schema_migrations')
    const alreadyApplied = new Set(rows.map((row) => row.name))

    for (const file of files) {
      if (alreadyApplied.has(file)) continue

      const sql = await readFile(path.join(MIGRATIONS_DIR, file), 'utf8')
      try {
        await client.query('BEGIN')
        await client.query(sql)
        await client.query('INSERT INTO schema_migrations (name) VALUES ($1)', [file])
        await client.query('COMMIT')
        applied.push(file)
      } catch (error) {
        await client.query('ROLLBACK')
        throw error
      }
    }
  } finally {
    await client.query('SELECT pg_advisory_unlock($1)', [MIGRATION_LOCK_KEY])
    client.release()
  }

  return applied
}
