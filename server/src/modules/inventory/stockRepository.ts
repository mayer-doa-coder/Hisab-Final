import type { PoolClient } from 'pg'
import { getPool } from '../../db/pool.js'
import { pgErrorCode, UNIQUE_VIOLATION } from '../../db/transaction.js'
import { quantity, type Quantity } from '../../domain/quantity.js'
import type { StockMovement } from '../../domain/stock.js'
import { movementFromRow } from '../sales/saleRepository.js'

type Queryable = Pick<PoolClient, 'query'>

/**
 * Stock on the server, always as a SUM over stock_movement — never a stored
 * number (D002, D020). Every function takes the session's shopId and filters
 * on it (D015).
 */

export interface ProductStock {
  readonly productId: string
  readonly name: string
  readonly unit: string
  readonly quantity: Quantity
}

/** Every live product in the shop with the stock its movements add up to; a product that never moved reads 0. */
export async function currentStock(shopId: string): Promise<ProductStock[]> {
  const { rows } = await getPool().query<{
    id: string
    name: string
    unit: string
    stock: string
  }>(
    `SELECT p.id, p.name, p.unit,
            COALESCE((
              SELECT SUM(m.quantity_delta_scaled)
              FROM stock_movement m
              WHERE m.shop_id = p.shop_id AND m.product_id = p.id
            ), 0) AS stock
     FROM product p
     WHERE p.shop_id = $1 AND p.deleted_at IS NULL
     ORDER BY lower(p.name) ASC`,
    [shopId],
  )
  return rows.map((row) => ({
    productId: row.id,
    name: row.name,
    unit: row.unit,
    quantity: quantity(Number(row.stock)),
  }))
}

export async function stockOf(
  shopId: string,
  productId: string,
  db: Queryable = getPool(),
): Promise<Quantity> {
  const { rows } = await db.query<{ stock: string }>(
    `SELECT COALESCE(SUM(quantity_delta_scaled), 0) AS stock
     FROM stock_movement WHERE shop_id = $1 AND product_id = $2`,
    [shopId, productId],
  )
  return quantity(Number(rows[0]!.stock))
}

/** One product's movements, newest first. */
export async function movementsOf(
  shopId: string,
  productId: string,
  limit: number,
): Promise<StockMovement[]> {
  const { rows } = await getPool().query(
    `SELECT * FROM stock_movement
     WHERE shop_id = $1 AND product_id = $2
     ORDER BY occurred_at DESC, id DESC
     LIMIT $3`,
    [shopId, productId, limit],
  )
  return rows.map(movementFromRow)
}

export type MovementOutcome =
  | { readonly status: 'created'; readonly movement: StockMovement }
  /** The same id was already stored for this shop — a retry, not a second movement (D004). */
  | { readonly status: 'exists'; readonly movement: StockMovement }
  /** That id already belongs to another shop's row. */
  | { readonly status: 'refused' }

async function findMovement(
  db: Queryable,
  shopId: string,
  id: string,
): Promise<StockMovement | null> {
  const { rows } = await db.query('SELECT * FROM stock_movement WHERE shop_id = $1 AND id = $2', [
    shopId,
    id,
  ])
  return rows[0] === undefined ? null : movementFromRow(rows[0])
}

/**
 * Stores a movement that stands on its own — a restock, damage, customer
 * return or shelf count. A sale's movements are written with the sale
 * (`saveSaleTransaction`), never through here.
 */
export async function saveStandaloneMovement(
  db: Queryable,
  shopId: string,
  movement: StockMovement,
): Promise<MovementOutcome> {
  try {
    const inserted = await db.query(
      `INSERT INTO stock_movement
         (id, shop_id, product_id, movement_type, quantity_delta_scaled, source_reference, occurred_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7)
       ON CONFLICT (id) DO NOTHING
       RETURNING id`,
      [
        movement.id,
        shopId,
        movement.productId,
        movement.type,
        movement.quantityDelta,
        movement.sourceReference,
        movement.time.occurredAt,
      ],
    )
    const stored = await findMovement(db, shopId, movement.id)
    if (stored === null) return { status: 'refused' }
    return { status: inserted.rowCount === 0 ? 'exists' : 'created', movement: stored }
  } catch (error) {
    if (pgErrorCode(error) === UNIQUE_VIOLATION) return { status: 'refused' }
    throw error
  }
}

/**
 * Stand-alone movements changed after a cursor, oldest first, for a pull
 * (Step 47). Movements that belong to a sale are not here: they travel inside
 * the sale.
 */
export async function changedStandaloneMovements(
  shopId: string,
  afterCursor: number,
  limit: number,
): Promise<Array<{ movement: StockMovement; seq: number }>> {
  const { rows } = await getPool().query(
    `SELECT * FROM stock_movement
     WHERE shop_id = $1 AND sale_id IS NULL AND change_seq > $2
     ORDER BY change_seq ASC
     LIMIT $3`,
    [shopId, afterCursor, limit],
  )
  return rows.map((row) => ({ movement: movementFromRow(row), seq: Number(row.change_seq) }))
}
