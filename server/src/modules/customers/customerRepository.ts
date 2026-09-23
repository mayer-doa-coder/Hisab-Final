import type { PoolClient } from 'pg'
import { getPool } from '../../db/pool.js'
import type { Customer } from '../../domain/customer.js'

interface CustomerRow {
  id: string
  shop_id: string
  name: string
  phone: string | null
  revision: number
  updated_at: Date
  deleted_at: Date | null
  change_seq: string
}

function toCustomer(row: CustomerRow): Customer {
  return {
    id: row.id,
    shopId: row.shop_id,
    name: row.name,
    phone: row.phone,
    revision: row.revision,
    updatedAt: row.updated_at.toISOString(),
    deletedAt: row.deleted_at === null ? null : row.deleted_at.toISOString(),
  }
}

type Queryable = Pick<PoolClient, 'query'>

/**
 * Just enough of Customer for a credit sale to name who owes the money
 * (Steps 40 and 46). Editing, deleting and the customer endpoints are M3
 * (Step 58).
 *
 * Every function takes shopId first and uses it in the WHERE clause; the
 * caller always passes the id from the session token, never from the request
 * (D015).
 */
export async function findCustomer(
  shopId: string,
  id: string,
  db: Queryable = getPool(),
): Promise<Customer | null> {
  const { rows } = await db.query<CustomerRow>(
    'SELECT * FROM customer WHERE shop_id = $1 AND id = $2',
    [shopId, id],
  )
  return rows[0] === undefined ? null : toCustomer(rows[0])
}

/**
 * Stores a customer a phone created offline, under the id the phone made
 * (D018). A second arrival of the same id is not an error — it is the same
 * customer, sent again — so it is left as it was.
 *
 * Returns false when that id already belongs to a different shop: ids are
 * global, and one shop must never be able to claim another shop's row.
 */
export async function createCustomer(
  shopId: string,
  customer: { id: string; name: string; phone: string | null },
  db: Queryable = getPool(),
): Promise<boolean> {
  await db.query(
    `INSERT INTO customer (id, shop_id, name, phone, revision, updated_at)
     VALUES ($1, $2, $3, $4, 1, now())
     ON CONFLICT (id) DO NOTHING`,
    [customer.id, shopId, customer.name.trim(), customer.phone],
  )
  return (await findCustomer(shopId, customer.id, db)) !== null
}

/**
 * The customer with this name in this shop, created if this is the first
 * time — the same rule the phone uses (CustomerRepository.findOrCreate), so a
 * credit sale made through the API and one made on the phone treat a name
 * the same way. Matching ignores case and surrounding spaces.
 */
export async function findOrCreateCustomerByName(
  shopId: string,
  name: string,
  newId: string,
  db: Queryable = getPool(),
): Promise<Customer> {
  const trimmed = name.trim()
  const { rows } = await db.query<CustomerRow>(
    `SELECT * FROM customer
     WHERE shop_id = $1 AND deleted_at IS NULL AND lower(name) = lower($2)
     ORDER BY change_seq ASC
     LIMIT 1`,
    [shopId, trimmed],
  )
  if (rows[0] !== undefined) return toCustomer(rows[0])

  await createCustomer(shopId, { id: newId, name: trimmed, phone: null }, db)
  return (await findCustomer(shopId, newId, db))!
}

/** Customers changed after a cursor, oldest first, for a pull (Step 47). */
export async function changedCustomers(
  shopId: string,
  afterCursor: number,
  limit: number,
): Promise<Array<{ customer: Customer; seq: number }>> {
  const { rows } = await getPool().query<CustomerRow>(
    `SELECT * FROM customer
     WHERE shop_id = $1 AND change_seq > $2
     ORDER BY change_seq ASC
     LIMIT $3`,
    [shopId, afterCursor, limit],
  )
  return rows.map((row) => ({ customer: toCustomer(row), seq: Number(row.change_seq) }))
}
