import type { PoolClient } from 'pg'
import { getPool } from './pool.js'

/**
 * Runs `work` inside one database transaction: every write in it happens, or
 * none does. A thrown error rolls back; a normal return commits.
 *
 * This is what makes a sale on the server as atomic as it is on the phone
 * (D021): the sale, its lines, its stock movements and its baki entry are
 * written by one call, so a failure half way cannot leave stock moved with
 * nothing owed.
 */
export async function withTransaction<T>(work: (client: PoolClient) => Promise<T>): Promise<T> {
  const client = await getPool().connect()
  try {
    await client.query('BEGIN')
    const result = await work(client)
    await client.query('COMMIT')
    return result
  } catch (error) {
    await client.query('ROLLBACK')
    throw error
  } finally {
    client.release()
  }
}

/** Postgres error codes this code reacts to by name. */
export const UNIQUE_VIOLATION = '23505'
export const FOREIGN_KEY_VIOLATION = '23503'

export function pgErrorCode(error: unknown): string | undefined {
  return (error as { code?: string }).code
}
