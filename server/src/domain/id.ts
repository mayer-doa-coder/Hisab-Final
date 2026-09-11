import { randomUUID } from 'node:crypto'

/**
 * Every entity that can be created offline (Product, Customer, Sale,
 * StockMovement, BakiEntry — not just SyncOutbox events) gets a globally
 * unique ID generated on the device at creation time. That ID is the
 * entity's permanent primary key, locally and on the server — there is no
 * separate local-ID-to-server-ID mapping step.
 * See DECISIONS.md D018.
 *
 * Uses Node's built-in UUID generator — no dependency needed (D006: keep
 * dependencies minimal).
 */
export type EntityId = string & { readonly __brand: 'EntityId' }

export function generateId(): EntityId {
  return randomUUID() as EntityId
}
