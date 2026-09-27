import type { PoolClient } from 'pg'
import { getPool } from '../../db/pool.js'
import { FOREIGN_KEY_VIOLATION, pgErrorCode, UNIQUE_VIOLATION } from '../../db/transaction.js'
import type { BakiEntry } from '../../domain/bakiEntry.js'
import { bakiFromRow } from '../sales/saleRepository.js'

type Queryable = Pick<PoolClient, 'query'>

/**
 * Baki entries a shopkeeper adds or settles by hand — Add Baki, Receive
 * Payment, and undoing either (Steps 54, 55, 57, 58). The two kinds a credit
 * sale writes (`credit_sale`, `reversal`) are never written here: they arrive
 * embedded in a Sale event and are stored by `saveSaleTransaction` (D021,
 * D038); this module only ever writes `sale_id IS NULL` rows.
 *
 * What a customer owes is always a SUM over these rows plus a sale's, never
 * stored (D001, D020) — there is no balance column anywhere.
 *
 * `due_date` is read back through `to_char`, not the driver's default. Left
 * to itself, node-postgres turns a DATE column into a JS `Date`, not the
 * `yyyy-MM-dd` string `BakiEntry.dueDate` is (`saleRepository.ts`'s own
 * `assemble` does the same conversion for the same reason).
 */
const BAKI_COLUMNS = `id, shop_id, customer_id, amount_delta_poisha, entry_type, reference, sale_id,
       to_char(due_date, 'YYYY-MM-DD') AS due_date, occurred_at, server_received_at`

/** One customer's balance, always a SUM, never stored (D001). */
export async function balanceOf(
  shopId: string,
  customerId: string,
  db: Queryable = getPool(),
): Promise<number> {
  const { rows } = await db.query<{ balance: string }>(
    `SELECT COALESCE(SUM(amount_delta_poisha), 0) AS balance
     FROM baki_entry WHERE shop_id = $1 AND customer_id = $2`,
    [shopId, customerId],
  )
  return Number(rows[0]!.balance)
}

/** One customer's entries, newest first — their ledger (Step 58). */
export async function entriesOf(
  shopId: string,
  customerId: string,
  limit: number,
  db: Queryable = getPool(),
): Promise<BakiEntry[]> {
  const { rows } = await db.query(
    `SELECT ${BAKI_COLUMNS} FROM baki_entry
     WHERE shop_id = $1 AND customer_id = $2
     ORDER BY occurred_at DESC, id DESC
     LIMIT $3`,
    [shopId, customerId, limit],
  )
  return rows.map(bakiFromRow)
}

export async function findBakiEntry(
  shopId: string,
  id: string,
  db: Queryable = getPool(),
): Promise<BakiEntry | null> {
  const { rows } = await db.query(
    `SELECT ${BAKI_COLUMNS} FROM baki_entry WHERE shop_id = $1 AND id = $2`,
    [shopId, id],
  )
  return rows[0] === undefined ? null : bakiFromRow(rows[0])
}

/** The entry_reversal that undoes this entry, if one was recorded — how "already undone?" is answered. */
export async function findReversalOfEntry(
  shopId: string,
  entryId: string,
  db: Queryable = getPool(),
): Promise<string | null> {
  const { rows } = await db.query<{ id: string }>(
    `SELECT id FROM baki_entry WHERE shop_id = $1 AND entry_type = 'entry_reversal' AND reference = $2`,
    [shopId, entryId],
  )
  return rows[0]?.id ?? null
}

export type SaveEntryOutcome =
  | { readonly status: 'created'; readonly entry: BakiEntry }
  /** The same entry id was already stored for this shop — a retry, not a second entry (D004). */
  | { readonly status: 'exists'; readonly entry: BakiEntry }
  /** Someone else undid the same entry first (D043) — caught here as the guarantee between devices. */
  | { readonly status: 'already-reversed' }
  /** The customer is not this shop's, or the id belongs to another shop. */
  | { readonly status: 'refused' }

/**
 * Stores a hand-written entry — a credit, a payment, or an undo of either.
 * Called either inside the transaction that also claims a sync event, or on
 * its own for a direct API call (Step 58).
 */
export async function saveStandaloneEntry(
  db: PoolClient,
  shopId: string,
  entry: BakiEntry,
): Promise<SaveEntryOutcome> {
  // The foreign key only proves the customer exists somewhere. It has to be
  // this shop's customer, or one shop could put baki on another shop's
  // customer by sending their id (D015) — the same check a sale makes.
  const { rowCount } = await db.query('SELECT 1 FROM customer WHERE shop_id = $1 AND id = $2', [
    shopId,
    entry.customerId,
  ])
  if (rowCount === 0) return { status: 'refused' }

  try {
    const inserted = await db.query(
      `INSERT INTO baki_entry
         (id, shop_id, customer_id, amount_delta_poisha, entry_type, reference, due_date, occurred_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
       ON CONFLICT (id) DO NOTHING
       RETURNING id`,
      [
        entry.id,
        shopId,
        entry.customerId,
        entry.amountDelta,
        entry.type,
        entry.reference,
        entry.dueDate,
        entry.time.occurredAt,
      ],
    )
    if (inserted.rowCount === 0) {
      const existing = await findBakiEntry(shopId, entry.id, db)
      return existing === null ? { status: 'refused' } : { status: 'exists', entry: existing }
    }
    return { status: 'created', entry: (await findBakiEntry(shopId, entry.id, db))! }
  } catch (error) {
    const code = pgErrorCode(error)
    if (
      code === UNIQUE_VIOLATION &&
      String((error as { constraint?: string }).constraint).includes('reversed_once')
    ) {
      return { status: 'already-reversed' }
    }
    if (code === UNIQUE_VIOLATION || code === FOREIGN_KEY_VIOLATION) return { status: 'refused' }
    throw error
  }
}

/**
 * Hand-written entries changed after a cursor, oldest first, for a pull (Step
 * 58). An entry a sale wrote is not here — `sale_id IS NULL` is exactly the
 * entries this module owns; a sale's entry travels inside the sale, never on
 * its own (D021, D038).
 */
export async function changedStandaloneBakiEntries(
  shopId: string,
  afterCursor: number,
  limit: number,
): Promise<Array<{ entry: BakiEntry; seq: number }>> {
  const { rows } = await getPool().query(
    `SELECT ${BAKI_COLUMNS}, change_seq FROM baki_entry
     WHERE shop_id = $1 AND sale_id IS NULL AND change_seq > $2
     ORDER BY change_seq ASC
     LIMIT $3`,
    [shopId, afterCursor, limit],
  )
  return rows.map((row) => ({ entry: bakiFromRow(row), seq: Number(row.change_seq) }))
}
