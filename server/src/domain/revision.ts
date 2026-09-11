/**
 * Conflict policy for mutable entities (Product, Customer, and any future
 * Shop settings). Append-only ledger entities (Sale, StockMovement,
 * BakiEntry) never go through this — they're never edited in place.
 * See DECISIONS.md D017.
 */

/** Language-neutral status code — see DECISIONS.md D011. Never localize this string itself. */
export const REVISION_CONFLICT = 'REVISION_CONFLICT' as const

export interface Revisioned<T> {
  readonly data: T
  readonly revision: number
  readonly updatedAt: string
  readonly deletedAt: string | null
}

export type RevisionCheckResult =
  { readonly ok: true } | { readonly ok: false; readonly code: typeof REVISION_CONFLICT }

/**
 * A client write must include the revision it edited from (baseRevision).
 * If it no longer matches the server's current revision, the write is
 * rejected — never silently overwritten.
 */
export function checkRevision(currentRevision: number, baseRevision: number): RevisionCheckResult {
  if (currentRevision !== baseRevision) {
    return { ok: false, code: REVISION_CONFLICT }
  }
  return { ok: true }
}

export function createRevisioned<T>(data: T, createdAt: Date): Revisioned<T> {
  return { data, revision: 1, updatedAt: createdAt.toISOString(), deletedAt: null }
}

/**
 * Applies an update only if baseRevision still matches. On success, bumps
 * the revision and updatedAt. On conflict, returns the conflict result and
 * leaves the entity untouched.
 */
export function applyRevisionedUpdate<T>(
  current: Revisioned<T>,
  baseRevision: number,
  newData: T,
  updatedAt: Date,
): { readonly result: RevisionCheckResult; readonly next: Revisioned<T> } {
  const result = checkRevision(current.revision, baseRevision)
  if (!result.ok) {
    return { result, next: current }
  }
  return {
    result,
    next: {
      data: newData,
      revision: current.revision + 1,
      updatedAt: updatedAt.toISOString(),
      deletedAt: current.deletedAt,
    },
  }
}

/**
 * Deletion is itself a revisioned, conflict-checked write — it sets a
 * tombstone rather than removing the row, so deletion can be synced like
 * any other change.
 */
export function applyRevisionedDelete<T>(
  current: Revisioned<T>,
  baseRevision: number,
  deletedAt: Date,
): { readonly result: RevisionCheckResult; readonly next: Revisioned<T> } {
  const result = checkRevision(current.revision, baseRevision)
  if (!result.ok) {
    return { result, next: current }
  }
  return {
    result,
    next: {
      data: current.data,
      revision: current.revision + 1,
      updatedAt: deletedAt.toISOString(),
      deletedAt: deletedAt.toISOString(),
    },
  }
}
