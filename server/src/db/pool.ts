import { Pool } from 'pg'

/**
 * Where the database lives. The default is the local Postgres described in
 * README.md ("Run It Yourself"), which listens on 5433 and trusts local
 * connections — so there is no password anywhere in this repository
 * (CLAUDE.md: never write passwords in code).
 *
 * A real deployment passes DATABASE_URL instead; that is the only change
 * needed to point this at a hosted Postgres such as Supabase (D030).
 */
function defaultUrl(): string {
  // `npm test` gets its own database, so a test run can never touch the data
  // being used for development. npm sets this variable to the script name.
  const database = process.env.npm_lifecycle_event === 'test' ? 'hisab_test' : 'hisab_dev'
  return `postgres://postgres@127.0.0.1:5433/${database}`
}

let pool: Pool | null = null

export function getPool(): Pool {
  pool ??= new Pool({ connectionString: process.env.DATABASE_URL ?? defaultUrl() })
  return pool
}

export async function closePool(): Promise<void> {
  if (pool) {
    const closing = pool
    pool = null
    await closing.end()
  }
}
