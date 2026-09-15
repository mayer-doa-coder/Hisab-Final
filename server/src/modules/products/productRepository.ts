import { getPool } from '../../db/pool.js'
import type { Product, ProductInput } from '../../domain/product.js'
import { REVISION_CONFLICT } from '../../domain/revision.js'

/** One row exactly as Postgres returns it: snake_case, and BIGINT as text. */
interface ProductRow {
  id: string
  shop_id: string
  name: string
  aliases: string[]
  unit: string
  purchase_price_poisha: string | null
  selling_price_poisha: string
  active: boolean
  revision: number
  updated_at: Date
  deleted_at: Date | null
  change_seq: string
}

/**
 * node-postgres hands BIGINT back as a string, so that a value too large for
 * a JavaScript number is never silently rounded. Prices in poisha are far
 * below that limit, so converting here is safe and keeps money an integer.
 */
function toPoisha(value: string | null): number | null {
  return value === null ? null : Number(value)
}

function toProduct(row: ProductRow): Product {
  return {
    id: row.id,
    shopId: row.shop_id,
    name: row.name,
    aliases: row.aliases,
    unit: row.unit,
    purchasePricePoisha: toPoisha(row.purchase_price_poisha),
    sellingPricePoisha: toPoisha(row.selling_price_poisha) ?? 0,
    active: row.active,
    revision: row.revision,
    updatedAt: row.updated_at.toISOString(),
    deletedAt: row.deleted_at === null ? null : row.deleted_at.toISOString(),
  }
}

const UNIQUE_VIOLATION = '23505'

export type CreateOutcome =
  | { readonly status: 'created'; readonly product: Product }
  /** The same id was already stored — a retried request, not a new product (D004). */
  | { readonly status: 'exists'; readonly product: Product }

export type UpdateOutcome =
  | { readonly status: 'updated'; readonly product: Product }
  | { readonly status: 'conflict'; readonly code: typeof REVISION_CONFLICT }
  | { readonly status: 'not-found' }

/**
 * Every function here takes shopId first and uses it in the WHERE clause.
 * The caller always passes the id from the session token, never from the
 * request (D015), so one shop can never read or change another shop's rows.
 */
export async function createProduct(
  shopId: string,
  id: string,
  input: ProductInput,
): Promise<CreateOutcome> {
  try {
    const { rows } = await getPool().query<ProductRow>(
      `INSERT INTO product
         (id, shop_id, name, aliases, unit, purchase_price_poisha, selling_price_poisha, active, revision, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, 1, now())
       RETURNING *`,
      [
        id,
        shopId,
        input.name.trim(),
        input.aliases,
        input.unit,
        input.purchasePricePoisha,
        input.sellingPricePoisha,
        input.active,
      ],
    )
    return { status: 'created', product: toProduct(rows[0]!) }
  } catch (error) {
    if ((error as { code?: string }).code !== UNIQUE_VIOLATION) throw error

    const existing = await findProduct(shopId, id)
    if (existing === null) throw error
    return { status: 'exists', product: existing }
  }
}

export async function findProduct(shopId: string, id: string): Promise<Product | null> {
  const { rows } = await getPool().query<ProductRow>(
    'SELECT * FROM product WHERE shop_id = $1 AND id = $2',
    [shopId, id],
  )
  return rows[0] === undefined ? null : toProduct(rows[0])
}

/**
 * The list and the search are one query, as they are on the phone: an empty
 * search means "everything". A search matches the name or any alias, ignoring
 * case (Step 30).
 */
export async function listProducts(
  shopId: string,
  options: { query?: string; includeInactive?: boolean } = {},
): Promise<Product[]> {
  const query = (options.query ?? '').trim()
  const { rows } = await getPool().query<ProductRow>(
    `SELECT * FROM product
     WHERE shop_id = $1
       AND deleted_at IS NULL
       AND ($2::boolean OR active)
       AND (
         $3::text = ''
         OR name ILIKE '%' || $3::text || '%'
         OR EXISTS (SELECT 1 FROM unnest(aliases) AS alias WHERE alias ILIKE '%' || $3::text || '%')
       )
     ORDER BY active DESC, lower(name) ASC`,
    [shopId, options.includeInactive ?? false, query],
  )
  return rows.map(toProduct)
}

/**
 * An edit must say which revision it was made from. If that no longer matches,
 * nothing is written and the caller is told — a stale write is never applied
 * over a newer one (D017).
 */
export async function updateProduct(
  shopId: string,
  id: string,
  baseRevision: number,
  input: ProductInput,
): Promise<UpdateOutcome> {
  const { rows } = await getPool().query<ProductRow>(
    `UPDATE product
     SET name = $4,
         aliases = $5,
         unit = $6,
         purchase_price_poisha = $7,
         selling_price_poisha = $8,
         active = $9,
         revision = revision + 1,
         updated_at = now(),
         change_seq = nextval('product_change_seq')
     WHERE shop_id = $1 AND id = $2 AND revision = $3 AND deleted_at IS NULL
     RETURNING *`,
    [
      shopId,
      id,
      baseRevision,
      input.name.trim(),
      input.aliases,
      input.unit,
      input.purchasePricePoisha,
      input.sellingPricePoisha,
      input.active,
    ],
  )

  if (rows[0] !== undefined) return { status: 'updated', product: toProduct(rows[0]) }

  // Nothing changed: either there is no such product for this shop, or its
  // revision has moved on since the caller read it.
  const existing = await findProduct(shopId, id)
  if (existing === null || existing.deletedAt !== null) return { status: 'not-found' }
  return { status: 'conflict', code: REVISION_CONFLICT }
}

/**
 * Deleting sets a tombstone instead of removing the row, so other devices can
 * be told about the deletion (D017). It is revision-checked like any edit.
 */
export async function deleteProduct(
  shopId: string,
  id: string,
  baseRevision: number,
): Promise<UpdateOutcome> {
  const { rows } = await getPool().query<ProductRow>(
    `UPDATE product
     SET deleted_at = now(),
         revision = revision + 1,
         updated_at = now(),
         change_seq = nextval('product_change_seq')
     WHERE shop_id = $1 AND id = $2 AND revision = $3 AND deleted_at IS NULL
     RETURNING *`,
    [shopId, id, baseRevision],
  )

  if (rows[0] !== undefined) return { status: 'updated', product: toProduct(rows[0]) }

  const existing = await findProduct(shopId, id)
  if (existing === null || existing.deletedAt !== null) return { status: 'not-found' }
  return { status: 'conflict', code: REVISION_CONFLICT }
}

/**
 * Everything in this shop that changed after the given point, oldest first,
 * for a device catching up (Step 33). Deleted products are included — a
 * device has to hear about a deletion too.
 *
 * The cursor is the change number of the last row handed back, so asking
 * again with it returns only what happened since.
 */
export async function changedProducts(
  shopId: string,
  afterCursor: number,
  limit = 500,
): Promise<{ products: Product[]; cursor: number }> {
  const { rows } = await getPool().query<ProductRow>(
    `SELECT * FROM product
     WHERE shop_id = $1 AND change_seq > $2
     ORDER BY change_seq ASC
     LIMIT $3`,
    [shopId, afterCursor, limit],
  )

  const last = rows[rows.length - 1]
  return {
    products: rows.map(toProduct),
    cursor: last === undefined ? afterCursor : Number(last.change_seq),
  }
}
