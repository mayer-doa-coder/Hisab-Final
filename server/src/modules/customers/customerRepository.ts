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
 * Customers: enough for a credit sale to name who owes the money (Steps 40
 * and 46), and for the customer endpoints to list, create and read them
 * (Step 58). Editing and deleting a customer are not here yet.
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
 * The customer with this name in this shop, if one exists. Matching ignores
 * case and surrounding spaces — this is what stops a shop ending up with two
 * rows for the same person because a name was typed with different spacing
 * or capitals on two occasions (D035).
 */
export async function findCustomerByName(
  shopId: string,
  name: string,
  db: Queryable = getPool(),
): Promise<Customer | null> {
  const trimmed = name.trim()
  const { rows } = await db.query<CustomerRow>(
    `SELECT * FROM customer
     WHERE shop_id = $1 AND deleted_at IS NULL AND lower(name) = lower($2)
     ORDER BY change_seq ASC
     LIMIT 1`,
    [shopId, trimmed],
  )
  return rows[0] === undefined ? null : toCustomer(rows[0])
}

/**
 * The customer with this name in this shop, created if this is the first
 * time — the same rule the phone uses (CustomerRepository.findOrCreate), so a
 * credit sale made through the API and one made on the phone treat a name
 * the same way.
 */
export async function findOrCreateCustomerByName(
  shopId: string,
  name: string,
  newId: string,
  db: Queryable = getPool(),
): Promise<Customer> {
  const trimmed = name.trim()
  const existing = await findCustomerByName(shopId, trimmed, db)
  if (existing !== null) return existing

  await createCustomer(shopId, { id: newId, name: trimmed, phone: null }, db)
  return (await findCustomer(shopId, newId, db))!
}

/** Everyone this shop has recorded, by name or phone (Step 58). Matching ignores case. */
export async function listCustomers(
  shopId: string,
  query: string,
  limit = 200,
): Promise<Customer[]> {
  const q = query.trim()
  const { rows } = await getPool().query<CustomerRow>(
    `SELECT * FROM customer
     WHERE shop_id = $1 AND deleted_at IS NULL
       AND ($2 = '' OR name ILIKE '%' || $2 || '%' OR phone ILIKE '%' || $2 || '%')
     ORDER BY lower(name) ASC
     LIMIT $3`,
    [shopId, q, limit],
  )
  return rows.map(toCustomer)
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
