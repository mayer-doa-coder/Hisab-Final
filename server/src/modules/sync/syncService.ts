import { getPool } from '../../db/pool.js'
import { PRODUCT_UNITS, type Product, type ProductInput } from '../../domain/product.js'
import { REVISION_CONFLICT } from '../../domain/revision.js'
import type { SyncEventEnvelope } from '../../domain/syncEvent.js'
import {
  changedProducts,
  createProduct,
  deleteProduct,
  updateProduct,
} from '../products/productRepository.js'

export const INITIAL_CURSOR = 0

/** Language-neutral status codes — never localized (D011). */
export const UNSUPPORTED_ENTITY = 'UNSUPPORTED_ENTITY'
export const INVALID_PAYLOAD = 'INVALID_PAYLOAD'
export const UNKNOWN_ENTITY = 'UNKNOWN_ENTITY'

export type ApplyOutcome =
  | { readonly eventId: string; readonly status: 'applied' }
  | { readonly eventId: string; readonly status: 'already-applied' }
  | { readonly eventId: string; readonly status: 'conflict'; readonly code: typeof REVISION_CONFLICT }
  | { readonly eventId: string; readonly status: 'rejected'; readonly code: string }

/**
 * Claims the event id before doing any work. The insert either succeeds — this
 * request owns the event — or hits the primary key, meaning it has already
 * been applied. Doing it this way round, rather than checking first and
 * inserting after, means two copies of the same push arriving at once cannot
 * both get through (D004).
 */
async function claimEvent(shopId: string, event: SyncEventEnvelope): Promise<boolean> {
  const { rows } = await getPool().query(
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

async function applyProductEvent(
  shopId: string,
  event: SyncEventEnvelope,
): Promise<ApplyOutcome> {
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

/**
 * Applies one pushed event to the real data (Step 32). The same event id is
 * never applied twice; an event whose base revision is stale is refused and
 * left unclaimed, so the phone can retry it after pulling.
 */
export async function applyEvent(
  shopId: string,
  event: SyncEventEnvelope,
): Promise<ApplyOutcome> {
  if (event.entityType !== 'Product') {
    return { eventId: event.eventId, status: 'rejected', code: UNSUPPORTED_ENTITY }
  }

  const claimed = await claimEvent(shopId, event)
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
 * Turns a changed product into the same envelope shape a phone pushes, so one
 * shape travels in both directions (D026). The event id is the product and its
 * revision together, which lets a device skip a change it already has.
 */
function toEnvelope(product: Product): SyncEventEnvelope {
  const operation =
    product.deletedAt !== null ? 'delete' : product.revision === 1 ? 'create' : 'update'

  return {
    eventId: `${product.id}:${product.revision}`,
    entityType: 'Product',
    entityId: product.id,
    operation,
    payload: product,
    baseRevision: product.revision > 1 ? product.revision - 1 : null,
    clientTimestamp: product.updatedAt,
  }
}

/**
 * What this shop has to catch up on (Step 33). Because it reads the product
 * table rather than a log of pushes, a product created straight through the
 * REST endpoints shows up here too.
 */
export async function changesSince(
  shopId: string,
  cursor: number,
): Promise<{ events: SyncEventEnvelope[]; cursor: number }> {
  const changed = await changedProducts(shopId, cursor)
  return { events: changed.products.map(toEnvelope), cursor: changed.cursor }
}
