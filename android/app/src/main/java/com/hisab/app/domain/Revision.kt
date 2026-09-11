package com.hisab.app.domain

import java.time.Instant

// Conflict policy for mutable entities (Product, Customer, and any future
// Shop settings). Append-only ledger entities (Sale, StockMovement,
// BakiEntry) never go through this — they're never edited in place.
// See DECISIONS.md D017.

/** Language-neutral status code — see DECISIONS.md D011. Never localize this string itself. */
const val REVISION_CONFLICT = "REVISION_CONFLICT"

sealed interface RevisionCheckResult {
    data object Ok : RevisionCheckResult

    data object Conflict : RevisionCheckResult {
        const val CODE = REVISION_CONFLICT
    }
}

data class Revisioned<T>(
    val data: T,
    val revision: Int,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)

/**
 * A client write must include the revision it edited from (baseRevision).
 * If it no longer matches the server's current revision, the write is
 * rejected — never silently overwritten.
 */
fun checkRevision(
    currentRevision: Int,
    baseRevision: Int,
): RevisionCheckResult =
    if (currentRevision != baseRevision) {
        RevisionCheckResult.Conflict
    } else {
        RevisionCheckResult.Ok
    }

fun <T> createRevisioned(
    data: T,
    createdAt: Instant,
): Revisioned<T> = Revisioned(data = data, revision = 1, updatedAt = createdAt, deletedAt = null)

data class RevisionedWrite<T>(
    val result: RevisionCheckResult,
    val next: Revisioned<T>,
)

/**
 * Applies an update only if baseRevision still matches. On success, bumps
 * the revision and updatedAt. On conflict, returns the conflict result and
 * leaves the entity untouched.
 */
fun <T> applyRevisionedUpdate(
    current: Revisioned<T>,
    baseRevision: Int,
    newData: T,
    updatedAt: Instant,
): RevisionedWrite<T> {
    val result = checkRevision(current.revision, baseRevision)
    if (result !is RevisionCheckResult.Ok) {
        return RevisionedWrite(result, current)
    }
    return RevisionedWrite(
        result,
        current.copy(data = newData, revision = current.revision + 1, updatedAt = updatedAt),
    )
}

/**
 * Deletion is itself a revisioned, conflict-checked write — it sets a
 * tombstone rather than removing the row, so deletion can be synced like
 * any other change.
 */
fun <T> applyRevisionedDelete(
    current: Revisioned<T>,
    baseRevision: Int,
    deletedAt: Instant,
): RevisionedWrite<T> {
    val result = checkRevision(current.revision, baseRevision)
    if (result !is RevisionCheckResult.Ok) {
        return RevisionedWrite(result, current)
    }
    return RevisionedWrite(
        result,
        current.copy(revision = current.revision + 1, updatedAt = deletedAt, deletedAt = deletedAt),
    )
}
