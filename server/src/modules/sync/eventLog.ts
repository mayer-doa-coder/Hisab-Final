import type { SyncEventEnvelope } from '../../domain/syncEvent.js'
import { checkRevision, REVISION_CONFLICT } from '../../domain/revision.js'

// The server-side sync log: what makes push idempotent (D004, Step 15) and
// what a pull (Step 16) retrieves changes from, and where an update/delete's
// base_revision gets checked (D017, Step 17). In-memory for now (D027) —
// real Product/Customer/Sale tables arrive with Postgres in M1.

interface StoredEvent {
  readonly sequence: number
  readonly shopId: string
  readonly event: SyncEventEnvelope
}

const eventsBySequence: StoredEvent[] = []
const seenEventIds = new Set<string>()

// Current revision of each mutable entity (Product, Customer), so an
// update/delete's base_revision can be checked against it. Keyed by
// "entityType:entityId". A create starts an entity at revision 1; an
// entity nothing has been applied for yet reads as revision 0.
const currentRevisions = new Map<string, number>()

let nextSequence = 1

function revisionKey(entityType: string, entityId: string): string {
  return `${entityType}:${entityId}`
}

export type ApplyOutcome =
  | { readonly eventId: string; readonly status: 'applied' }
  | { readonly eventId: string; readonly status: 'already-applied' }
  | {
      readonly eventId: string
      readonly status: 'conflict'
      readonly code: typeof REVISION_CONFLICT
    }

/**
 * Applies one event for one shop. Checks, in order:
 *
 * 1. Has this exact eventId already been applied? If so, no-op (D004) —
 *    pushing the same event twice must have the same effect as once.
 * 2. For update/delete, does base_revision still match this entity's
 *    current revision? If not, reject with REVISION_CONFLICT — never
 *    silently overwrite (D017). A rejected event is NOT marked processed:
 *    it was never applied, so pushing the same one again later (e.g. after
 *    the user reloads and retries) is re-checked fresh, not treated as a
 *    duplicate.
 *
 * On success, records the event (so a pull, Step 16, can retrieve it) and
 * bumps the entity's current revision.
 */
export function applyEvent(shopId: string, event: SyncEventEnvelope): ApplyOutcome {
  if (seenEventIds.has(event.eventId)) {
    return { eventId: event.eventId, status: 'already-applied' }
  }

  const key = revisionKey(event.entityType, event.entityId)

  if (event.operation === 'update' || event.operation === 'delete') {
    const current = currentRevisions.get(key) ?? 0
    const result = checkRevision(current, event.baseRevision ?? -1)
    if (!result.ok) {
      return { eventId: event.eventId, status: 'conflict', code: result.code }
    }
  }

  seenEventIds.add(event.eventId)
  eventsBySequence.push({ sequence: nextSequence, shopId, event })
  nextSequence += 1

  const nextRevision = event.operation === 'create' ? 1 : (currentRevisions.get(key) ?? 0) + 1
  currentRevisions.set(key, nextRevision)

  return { eventId: event.eventId, status: 'applied' }
}

export function isProcessed(eventId: string): boolean {
  return seenEventIds.has(eventId)
}

export function getCurrentRevision(entityType: string, entityId: string): number {
  return currentRevisions.get(revisionKey(entityType, entityId)) ?? 0
}

export const INITIAL_CURSOR = 0

/** Changes since the given cursor (exclusive), for one shop only, in order, plus the new cursor. */
export function changesSince(
  shopId: string,
  cursor: number,
): { readonly events: readonly SyncEventEnvelope[]; readonly cursor: number } {
  const matching = eventsBySequence.filter(
    (stored) => stored.shopId === shopId && stored.sequence > cursor,
  )
  const newCursor = matching.length > 0 ? matching[matching.length - 1]!.sequence : cursor
  return { events: matching.map((stored) => stored.event), cursor: newCursor }
}
