// The one envelope shape used for every push (Step 14, docs/PHASE_GUIDE.md).
// Mirrored on Android by SyncOutboxEntity
// (android/app/src/main/java/com/hisab/app/data/sync/SyncOutboxEntity.kt) —
// same fields, same names, so the two sides never need a translation layer
// for this (D026: one shape, used end to end).

export type SyncOperation = 'create' | 'update' | 'delete'

export interface SyncEventEnvelope {
  readonly eventId: string
  readonly entityType: string
  readonly entityId: string
  readonly operation: SyncOperation
  readonly payload: unknown
  /** Required for update/delete of a mutable entity (Product, Customer) — see D017. Null for create. */
  readonly baseRevision: number | null
  /** ISO 8601. When it happened on the device — never when the server received it (D019). */
  readonly clientTimestamp: string
}
