import type { PoolClient } from 'pg'
import { getPool } from '../../db/pool.js'
import { FOREIGN_KEY_VIOLATION, pgErrorCode, UNIQUE_VIOLATION } from '../../db/transaction.js'
import type { BakiEntry } from '../../domain/bakiEntry.js'
import type { EntityId } from '../../domain/id.js'
import { money } from '../../domain/money.js'
import { quantity } from '../../domain/quantity.js'
import type { SaleItem, SaleTransaction } from '../../domain/sale.js'
import type { StockMovement } from '../../domain/stock.js'

type Queryable = Pick<PoolClient, 'query'>

interface SaleRow {
  id: string
  shop_id: string
  total_poisha: string
  payment: 'cash' | 'credit'
  customer_id: string | null
  reverses_sale_id: string | null
  occurred_at: Date
  server_received_at: Date
  change_seq: string
}

interface ItemRow {
  sale_id: string
  product_id: string
  quantity_scaled: string
  unit_price_poisha: string
}

interface MovementRow {
  id: string
  product_id: string
  movement_type: StockMovement['type']
  quantity_delta_scaled: string
  source_reference: string | null
  sale_id: string | null
  occurred_at: Date
  server_received_at: Date
}

interface BakiRow {
  id: string
  customer_id: string
  amount_delta_poisha: string
  entry_type: BakiEntry['type']
  reference: string | null
  sale_id: string | null
  due_date: string | null
  occurred_at: Date
  server_received_at: Date
}

/**
 * node-postgres returns BIGINT as text so a huge value is never rounded.
 * Poisha and scaled quantities are far below that limit, so converting keeps
 * them integers (D019).
 */
const int = (value: string): number => Number(value)

export function movementFromRow(row: MovementRow): StockMovement {
  return {
    id: row.id as EntityId,
    productId: row.product_id as EntityId,
    type: row.movement_type,
    quantityDelta: quantity(int(row.quantity_delta_scaled)),
    sourceReference: row.source_reference,
    time: {
      occurredAt: row.occurred_at.toISOString(),
      serverReceivedAt: row.server_received_at.toISOString(),
    },
  }
}

function bakiFromRow(row: BakiRow): BakiEntry {
  return {
    id: row.id as EntityId,
    customerId: row.customer_id as EntityId,
    amountDelta: money(int(row.amount_delta_poisha)),
    type: row.entry_type,
    reference: row.reference,
    dueDate: row.due_date,
    time: {
      occurredAt: row.occurred_at.toISOString(),
      serverReceivedAt: row.server_received_at.toISOString(),
    },
  }
}

/**
 * Loads whole sale transactions — sale, lines, movements and baki entry — for
 * a set of sale rows, with one query per kind of row rather than one per
 * sale.
 */
async function assemble(db: Queryable, sales: SaleRow[]): Promise<SaleTransaction[]> {
  if (sales.length === 0) return []
  const ids = sales.map((sale) => sale.id)

  const [items, movements, baki] = await Promise.all([
    db.query<ItemRow>('SELECT * FROM sale_item WHERE sale_id = ANY($1) ORDER BY product_id', [ids]),
    db.query<MovementRow>(
      'SELECT * FROM stock_movement WHERE sale_id = ANY($1) ORDER BY product_id, id',
      [ids],
    ),
    db.query<BakiRow>(
      `SELECT id, customer_id, amount_delta_poisha, entry_type, reference, sale_id,
              to_char(due_date, 'YYYY-MM-DD') AS due_date, occurred_at, server_received_at
       FROM baki_entry WHERE sale_id = ANY($1)`,
      [ids],
    ),
  ])

  return sales.map((row) => {
    const time = {
      occurredAt: row.occurred_at.toISOString(),
      serverReceivedAt: row.server_received_at.toISOString(),
    }
    const saleItems: SaleItem[] = items.rows
      .filter((item) => item.sale_id === row.id)
      .map((item) => ({
        saleId: row.id as EntityId,
        productId: item.product_id as EntityId,
        quantity: quantity(int(item.quantity_scaled)),
        unitPrice: money(int(item.unit_price_poisha)),
      }))
    const bakiRow = baki.rows.find((entry) => entry.sale_id === row.id)

    return {
      sale: {
        id: row.id as EntityId,
        shopId: row.shop_id,
        total: money(int(row.total_poisha)),
        payment: row.payment,
        customerId: row.customer_id as EntityId | null,
        reversesSaleId: row.reverses_sale_id as EntityId | null,
        time,
      },
      items: saleItems,
      stockMovements: movements.rows
        .filter((movement) => movement.sale_id === row.id)
        .map(movementFromRow),
      bakiEntry: bakiRow === undefined ? null : bakiFromRow(bakiRow),
    }
  })
}

export async function findSaleTransaction(
  shopId: string,
  id: string,
  db: Queryable = getPool(),
): Promise<SaleTransaction | null> {
  const { rows } = await db.query<SaleRow>('SELECT * FROM sale WHERE shop_id = $1 AND id = $2', [
    shopId,
    id,
  ])
  const [transaction] = await assemble(db, rows)
  return transaction ?? null
}

/** The reversal of a sale, if one was recorded — how "already undone?" is answered. */
export async function findReversalOf(
  shopId: string,
  saleId: string,
  db: Queryable = getPool(),
): Promise<string | null> {
  const { rows } = await db.query<{ id: string }>(
    'SELECT id FROM sale WHERE shop_id = $1 AND reverses_sale_id = $2',
    [shopId, saleId],
  )
  return rows[0]?.id ?? null
}

export type SaveOutcome =
  | { readonly status: 'created'; readonly transaction: SaleTransaction }
  /** The same sale id was already stored for this shop — a retry, not a second sale (D004). */
  | { readonly status: 'exists'; readonly transaction: SaleTransaction }
  | { readonly status: 'already-reversed' }
  /** A row id is already used by another shop, or the customer it names is not this shop's. */
  | { readonly status: 'refused' }

/**
 * Writes a whole sale transaction. Must be called inside a transaction
 * (`withTransaction`), so the sale, its lines, its movements and its baki
 * entry are stored together or not at all (D021).
 *
 * The shop is always the one passed in — the session's — whatever the
 * transaction itself says (D015). Callers check the numbers first
 * (`checkPushedSale`, or the domain functions that built it).
 */
export async function saveSaleTransaction(
  db: PoolClient,
  shopId: string,
  transaction: SaleTransaction,
): Promise<SaveOutcome> {
  const { sale, items, stockMovements, bakiEntry } = transaction

  // The foreign key only proves the customer exists somewhere. It has to be
  // this shop's customer, or one shop could put baki on another shop's
  // customer by sending their id (D015).
  if (sale.customerId !== null) {
    const { rowCount } = await db.query('SELECT 1 FROM customer WHERE shop_id = $1 AND id = $2', [
      shopId,
      sale.customerId,
    ])
    if (rowCount === 0) return { status: 'refused' }
  }

  // A savepoint, so a refused insert can be undone without throwing away the
  // caller's whole transaction (which also holds the sync event's claim).
  await db.query('SAVEPOINT save_sale')
  try {
    const inserted = await db.query(
      `INSERT INTO sale (id, shop_id, total_poisha, payment, customer_id, reverses_sale_id, occurred_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7)
       ON CONFLICT (id) DO NOTHING
       RETURNING id`,
      [
        sale.id,
        shopId,
        sale.total,
        sale.payment,
        sale.customerId,
        sale.reversesSaleId,
        sale.time.occurredAt,
      ],
    )

    if (inserted.rowCount === 0) {
      await db.query('ROLLBACK TO SAVEPOINT save_sale')
      const existing = await findSaleTransaction(shopId, sale.id, db)
      return existing === null ? { status: 'refused' } : { status: 'exists', transaction: existing }
    }

    for (const item of items) {
      await db.query(
        `INSERT INTO sale_item (sale_id, product_id, shop_id, quantity_scaled, unit_price_poisha)
         VALUES ($1, $2, $3, $4, $5)`,
        [sale.id, item.productId, shopId, item.quantity, item.unitPrice],
      )
    }

    for (const movement of stockMovements) {
      await db.query(
        `INSERT INTO stock_movement
           (id, shop_id, product_id, movement_type, quantity_delta_scaled, source_reference, sale_id, occurred_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
        [
          movement.id,
          shopId,
          movement.productId,
          movement.type,
          movement.quantityDelta,
          movement.sourceReference,
          sale.id,
          movement.time.occurredAt,
        ],
      )
    }

    if (bakiEntry !== null) {
      await db.query(
        `INSERT INTO baki_entry
           (id, shop_id, customer_id, amount_delta_poisha, entry_type, reference, sale_id, due_date, occurred_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
        [
          bakiEntry.id,
          shopId,
          bakiEntry.customerId,
          bakiEntry.amountDelta,
          bakiEntry.type,
          bakiEntry.reference,
          sale.id,
          bakiEntry.dueDate,
          bakiEntry.time.occurredAt,
        ],
      )
    }

    await db.query('RELEASE SAVEPOINT save_sale')
  } catch (error) {
    await db.query('ROLLBACK TO SAVEPOINT save_sale')
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

  return { status: 'created', transaction: (await findSaleTransaction(shopId, sale.id, db))! }
}

/** Newest first — what a history list shows. */
export async function listSaleTransactions(
  shopId: string,
  limit: number,
): Promise<SaleTransaction[]> {
  const db = getPool()
  const { rows } = await db.query<SaleRow>(
    'SELECT * FROM sale WHERE shop_id = $1 ORDER BY occurred_at DESC, id DESC LIMIT $2',
    [shopId, limit],
  )
  return assemble(db, rows)
}

/**
 * Sales changed after a cursor, oldest first, each as a whole transaction, for
 * a pull (Step 47). A sale's movements and baki entry travel inside it, never
 * on their own, so a device can never receive half a sale.
 */
export async function changedSales(
  shopId: string,
  afterCursor: number,
  limit: number,
): Promise<Array<{ transaction: SaleTransaction; seq: number }>> {
  const db = getPool()
  const { rows } = await db.query<SaleRow>(
    `SELECT * FROM sale WHERE shop_id = $1 AND change_seq > $2 ORDER BY change_seq ASC LIMIT $3`,
    [shopId, afterCursor, limit],
  )
  const transactions = await assemble(db, rows)
  return transactions.map((transaction, index) => ({
    transaction,
    seq: int(rows[index]!.change_seq),
  }))
}
