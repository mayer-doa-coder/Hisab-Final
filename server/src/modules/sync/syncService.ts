import type { PoolClient } from 'pg'
import { getPool } from '../../db/pool.js'
import { withTransaction } from '../../db/transaction.js'
import type { Customer } from '../../domain/customer.js'
import { ID_TAKEN } from '../../domain/id.js'
import { PRODUCT_UNITS, type Product, type ProductInput } from '../../domain/product.js'
import { REVISION_CONFLICT } from '../../domain/revision.js'
import type { SaleTransaction } from '../../domain/sale.js'
import type { StockMovement } from '../../domain/stock.js'
import type { SyncEventEnvelope } from '../../domain/syncEvent.js'
import { changedCustomers, createCustomer } from '../customers/customerRepository.js'
import { changedStandaloneMovements, saveStandaloneMovement } from '../inventory/stockRepository.js'
import {
  changedProducts,
  createProduct,
  deleteProduct,
  updateProduct,
} from '../products/productRepository.js'
import {
  changedSales,
  findReversalOf,
  findSaleTransaction,
  saveSaleTransaction,
} from '../sales/saleRepository.js'
import {
  ALREADY_REVERSED,
  checkPushedSale,
  parseSaleTransaction,
  parseStandaloneMovement,
} from '../sales/saleValidation.js'

export const INITIAL_CURSOR = 0

/** How many changes one pull returns at most. A device asks again from the new cursor for more. */
export const PULL_PAGE_SIZE = 500

/** Language-neutral status codes — never localized (D011). */
export const UNSUPPORTED_ENTITY = 'UNSUPPORTED_ENTITY'
export const UNSUPPORTED_OPERATION = 'UNSUPPORTED_OPERATION'
export const INVALID_PAYLOAD = 'INVALID_PAYLOAD'
export const UNKNOWN_ENTITY = 'UNKNOWN_ENTITY'

export const ENTITY_PRODUCT = 'Product'
export const ENTITY_CUSTOMER = 'Customer'
export const ENTITY_SALE = 'Sale'
export const ENTITY_STOCK_MOVEMENT = 'StockMovement'

export type ApplyOutcome =
  | { readonly eventId: string; readonly status: 'applied' }
  | { readonly eventId: string; readonly status: 'already-applied' }
  | {
      readonly eventId: string
      readonly status: 'conflict'
      readonly code: typeof REVISION_CONFLICT
    }
  | { readonly eventId: string; readonly status: 'rejected'; readonly code: string }

/**
 * Claims the event id before doing any work. The insert either succeeds — this
 * request owns the event — or hits the primary key, meaning it has already
 * been applied. Doing it this way round, rather than checking first and
 * inserting after, means two copies of the same push arriving at once cannot
 * both get through (D004).
 */
async function claimEvent(
  db: Pick<PoolClient, 'query'>,
  shopId: string,
  event: SyncEventEnvelope,
): Promise<boolean> {
  const { rows } = await db.query(
    `INSERT INTO sync_event (event_id, shop_id, entity_type, entity_id, operation)
     VALUES ($1, $2, $3, $4, $5)
     ON CONFLICT (event_id) DO NOTHING
     RETURNING event_id`,
    [event.eventId, shopId, event.entityType, event.entityId, event.operation],
  )
  return rows.length > 0
}

/** Gives the claim back, so a rejected event can be sent again once the device catches up. */
async function releaseEvent(eventId: string): Promise<void> {
  await getPool().query('DELETE FROM sync_event WHERE event_id = $1', [eventId])
}

export async function isProcessed(eventId: string): Promise<boolean> {
  const { rows } = await getPool().query('SELECT 1 FROM sync_event WHERE event_id = $1', [eventId])
  return rows.length > 0
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

/**
 * A payload arrives as whatever the phone sent. Anything missing or of the
 * wrong type is refused rather than written half-formed.
 */
function toProductInput(payload: unknown): ProductInput | null {
  if (typeof payload !== 'object' || payload === null) return null
  const candidate = payload as Record<string, unknown>

  const name = candidate.name
  const sellingPricePoisha = candidate.sellingPricePoisha
  const purchasePricePoisha = candidate.purchasePricePoisha ?? null
  const aliases = candidate.aliases ?? []
  const unit = candidate.unit ?? 'piece'
  const active = candidate.active ?? true

  if (typeof name !== 'string' || name.trim() === '') return null
  if (!Number.isInteger(sellingPricePoisha) || (sellingPricePoisha as number) < 0) return null
  if (
    purchasePricePoisha !== null &&
    (!Number.isInteger(purchasePricePoisha) || (purchasePricePoisha as number) < 0)
  ) {
    return null
  }
  if (!isStringArray(aliases)) return null
  if (typeof unit !== 'string' || !(PRODUCT_UNITS as readonly string[]).includes(unit)) return null
  if (typeof active !== 'boolean') return null

  return {
    name,
    aliases,
    unit,
    purchasePricePoisha: purchasePricePoisha as number | null,
    sellingPricePoisha: sellingPricePoisha as number,
    active,
  }
}

async function applyProductEvent(shopId: string, event: SyncEventEnvelope): Promise<ApplyOutcome> {
  const rejected = (code: string): ApplyOutcome => ({
    eventId: event.eventId,
    status: 'rejected',
    code,
  })

  if (event.operation === 'delete') {
    const outcome = await deleteProduct(shopId, event.entityId, event.baseRevision ?? -1)
    if (outcome.status === 'conflict') {
      return { eventId: event.eventId, status: 'conflict', code: outcome.code }
    }
    if (outcome.status === 'not-found') return rejected(UNKNOWN_ENTITY)
    return { eventId: event.eventId, status: 'applied' }
  }

  const input = toProductInput(event.payload)
  if (input === null) return rejected(INVALID_PAYLOAD)

  if (event.operation === 'create') {
    await createProduct(shopId, event.entityId, input)
    return { eventId: event.eventId, status: 'applied' }
  }

  const outcome = await updateProduct(shopId, event.entityId, event.baseRevision ?? -1, input)
  if (outcome.status === 'conflict') {
    return { eventId: event.eventId, status: 'conflict', code: outcome.code }
  }
  if (outcome.status === 'not-found') return rejected(UNKNOWN_ENTITY)
  return { eventId: event.eventId, status: 'applied' }
}

/** Carries a refusal out of a transaction so the transaction — and the event's claim — rolls back. */
class NotApplied extends Error {
  constructor(readonly outcome: ApplyOutcome) {
    super(outcome.status)
  }
}

/**
 * Applies a ledger event (Customer, Sale, StockMovement) with the event's
 * claim inside the same database transaction as the data it writes.
 *
 * So the server either has the change and remembers the event, or has
 * neither. A crash half way leaves nothing claimed, and the phone's retry is
 * applied fresh — never mistaken for a duplicate of something that was lost
 * (D004, and the failure M4's Step 67 tests for). A refusal rolls the claim
 * back too, so the phone can send the event again once it has caught up.
 */
async function applyInOneTransaction(
  shopId: string,
  event: SyncEventEnvelope,
  apply: (db: PoolClient) => Promise<string | null>,
): Promise<ApplyOutcome> {
  try {
    return await withTransaction(async (db) => {
      if (!(await claimEvent(db, shopId, event))) {
        return { eventId: event.eventId, status: 'already-applied' } as const
      }
      const refusal = await apply(db)
      if (refusal !== null) {
        throw new NotApplied({ eventId: event.eventId, status: 'rejected', code: refusal })
      }
      return { eventId: event.eventId, status: 'applied' } as const
    })
  } catch (error) {
    if (error instanceof NotApplied) return error.outcome
    throw error
  }
}

function parseCustomer(payload: unknown): { name: string; phone: string | null } | null {
  if (typeof payload !== 'object' || payload === null) return null
  const { name, phone } = payload as Record<string, unknown>
  if (typeof name !== 'string' || name.trim() === '') return null
  if (phone !== null && phone !== undefined && typeof phone !== 'string') return null
  return { name, phone: (phone as string | null | undefined) ?? null }
}

/**
 * A customer made on a phone by a credit sale (Step 40). Only creating is
 * supported in M2 — editing and deleting customers is M3, so those are
 * refused rather than guessed at.
 */
async function applyCustomerEvent(shopId: string, event: SyncEventEnvelope): Promise<ApplyOutcome> {
  if (event.operation !== 'create') {
    return { eventId: event.eventId, status: 'rejected', code: UNSUPPORTED_OPERATION }
  }
  const customer = parseCustomer(event.payload)
  if (customer === null) {
    return { eventId: event.eventId, status: 'rejected', code: INVALID_PAYLOAD }
  }
  return applyInOneTransaction(shopId, event, async (db) =>
    (await createCustomer(shopId, { id: event.entityId, ...customer }, db)) ? null : ID_TAKEN,
  )
}

/**
 * A whole sale — or the reversal of one — made on a phone (Steps 39, 40, 43).
 * One event carries the sale, its lines, its stock movements and its baki
 * entry, and they are written in one transaction, so the server can never hold
 * half a sale (D021).
 *
 * The numbers are checked, not trusted: `checkPushedSale` recomputes the total
 * and confirms every movement and baki entry matches it.
 */
async function applySaleEvent(shopId: string, event: SyncEventEnvelope): Promise<ApplyOutcome> {
  if (event.operation !== 'create') {
    // Confirmed history is never edited or deleted (CLAUDE.md). A mistake is
    // a second, reversing sale — which is also a create.
    return { eventId: event.eventId, status: 'rejected', code: UNSUPPORTED_OPERATION }
  }
  const transaction = parseSaleTransaction(event.payload)
  if (transaction === null || transaction.sale.id !== event.entityId) {
    return { eventId: event.eventId, status: 'rejected', code: INVALID_PAYLOAD }
  }

  return applyInOneTransaction(shopId, event, async (db) => {
    // A retry of a sale already stored (under a different event id) is the
    // same sale, not a second one (D004).
    if ((await findSaleTransaction(shopId, transaction.sale.id, db)) !== null) return null

    let original: SaleTransaction | null = null
    if (transaction.sale.reversesSaleId !== null) {
      original = await findSaleTransaction(shopId, transaction.sale.reversesSaleId, db)
      if (original === null) return UNKNOWN_ENTITY
      if ((await findReversalOf(shopId, original.sale.id, db)) !== null) return ALREADY_REVERSED
    }

    const problem = checkPushedSale(transaction, original)
    if (problem !== null) return problem

    const saved = await saveSaleTransaction(db, shopId, transaction)
    switch (saved.status) {
      case 'created':
      case 'exists':
        return null
      case 'already-reversed':
        return ALREADY_REVERSED
      case 'refused':
        // The customer is not this shop's, or an id belongs to another shop.
        return UNKNOWN_ENTITY
    }
  })
}

/** A restock, damage, customer return or shelf count made on a phone (Step 41). */
async function applyStockMovementEvent(
  shopId: string,
  event: SyncEventEnvelope,
): Promise<ApplyOutcome> {
  if (event.operation !== 'create') {
    return { eventId: event.eventId, status: 'rejected', code: UNSUPPORTED_OPERATION }
  }
  const movement = parseStandaloneMovement(event.payload)
  if (movement === null || movement.id !== event.entityId) {
    return { eventId: event.eventId, status: 'rejected', code: INVALID_PAYLOAD }
  }
  return applyInOneTransaction(shopId, event, async (db) =>
    (await saveStandaloneMovement(db, shopId, movement)).status === 'refused' ? ID_TAKEN : null,
  )
}

/**
 * Applies one pushed event to the real data (Steps 32 and 47). The same event
 * id is never applied twice; an event whose base revision is stale is refused
 * and left unclaimed, so the phone can retry it after pulling.
 */
export async function applyEvent(shopId: string, event: SyncEventEnvelope): Promise<ApplyOutcome> {
  switch (event.entityType) {
    case ENTITY_CUSTOMER:
      return applyCustomerEvent(shopId, event)
    case ENTITY_SALE:
      return applySaleEvent(shopId, event)
    case ENTITY_STOCK_MOVEMENT:
      return applyStockMovementEvent(shopId, event)
    case ENTITY_PRODUCT:
      break
    default:
      return { eventId: event.eventId, status: 'rejected', code: UNSUPPORTED_ENTITY }
  }

  const claimed = await claimEvent(getPool(), shopId, event)
  if (!claimed) return { eventId: event.eventId, status: 'already-applied' }

  try {
    const outcome = await applyProductEvent(shopId, event)
    if (outcome.status !== 'applied') await releaseEvent(event.eventId)
    return outcome
  } catch (error) {
    await releaseEvent(event.eventId)
    throw error
  }
}

/**
 * A mutable entity as the same envelope shape a phone pushes, so one shape
 * travels in both directions (D026). The event id is the entity and its
 * revision together, which lets a device skip a change it already has.
 */
function mutableEnvelope(entityType: string, entity: Product | Customer): SyncEventEnvelope {
  const operation =
    entity.deletedAt !== null ? 'delete' : entity.revision === 1 ? 'create' : 'update'
  return {
    eventId: `${entity.id}:${entity.revision}`,
    entityType,
    entityId: entity.id,
    operation,
    payload: entity,
    baseRevision: entity.revision > 1 ? entity.revision - 1 : null,
    clientTimestamp: entity.updatedAt,
  }
}

/** A ledger row never changes after it is written, so it only ever arrives as a create. */
function ledgerEnvelope(
  entityType: string,
  id: string,
  payload: SaleTransaction | StockMovement,
  occurredAt: string,
): SyncEventEnvelope {
  return {
    eventId: `${entityType}:${id}`,
    entityType,
    entityId: id,
    operation: 'create',
    payload,
    baseRevision: null,
    clientTimestamp: occurredAt,
  }
}

/**
 * What this shop has to catch up on (Steps 33 and 47): products, customers,
 * sales and stand-alone stock movements changed after the cursor, oldest
 * first, at most one page.
 *
 * Every table numbers its changes from one shared counter, so the four lists
 * can be merged into one order. Each is read up to a page, merged, and cut to
 * a page — which gives exactly the oldest page across all of them, however
 * the changes are spread between tables. The cursor returned is the number of
 * the last change handed back; asking again from it continues the list.
 *
 * A sale arrives as one change holding its lines, stock movements and baki
 * entry, so a page boundary can never split a sale (D021).
 */
export async function changesSince(
  shopId: string,
  cursor: number,
  pageSize = PULL_PAGE_SIZE,
): Promise<{ events: SyncEventEnvelope[]; cursor: number }> {
  const [products, customers, sales, movements] = await Promise.all([
    changedProducts(shopId, cursor, pageSize),
    changedCustomers(shopId, cursor, pageSize),
    changedSales(shopId, cursor, pageSize),
    changedStandaloneMovements(shopId, cursor, pageSize),
  ])

  const merged: Array<{ seq: number; event: SyncEventEnvelope }> = [
    ...products.map(({ product, seq }) => ({
      seq,
      event: mutableEnvelope(ENTITY_PRODUCT, product),
    })),
    ...customers.map(({ customer, seq }) => ({
      seq,
      event: mutableEnvelope(ENTITY_CUSTOMER, customer),
    })),
    ...sales.map(({ transaction, seq }) => ({
      seq,
      event: ledgerEnvelope(
        ENTITY_SALE,
        transaction.sale.id,
        transaction,
        transaction.sale.time.occurredAt,
      ),
    })),
    ...movements.map(({ movement, seq }) => ({
      seq,
      event: ledgerEnvelope(ENTITY_STOCK_MOVEMENT, movement.id, movement, movement.time.occurredAt),
    })),
  ]
    .sort((a, b) => a.seq - b.seq)
    .slice(0, pageSize)

  const last = merged[merged.length - 1]
  return {
    events: merged.map((entry) => entry.event),
    cursor: last === undefined ? cursor : last.seq,
  }
}
