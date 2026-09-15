package com.hisab.app.data.sync

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * One row per pending (or already-sent) local change. Written in the same
 * local transaction as the business-data change it represents (D003) — that
 * wiring starts in M1, once a real feature (Product) exists to write it.
 *
 * Mirrors the push envelope the server expects
 * (server/src/domain/syncEvent.ts): eventId, entityType, entityId,
 * operation, payload, baseRevision, clientTimestamp. `id` and
 * `status`/`retryCount` are local-only bookkeeping and never leave the
 * device (D026 — one shape, used end to end; no mapper needed here since
 * these fields simply aren't part of the envelope sent over the wire).
 */
@Entity(
    tableName = "sync_outbox",
    indices = [Index(value = ["eventId"], unique = true)],
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Globally unique — generated on the device, never reused (D004, D018). */
    val eventId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    /** Opaque JSON, not parsed at this layer. */
    val payload: String,
    /** Required for update/delete of a mutable entity (Product, Customer) — see D017. Null for create. */
    val baseRevision: Int?,
    val clientTimestamp: Instant,
    val status: String = STATUS_PENDING,
    val retryCount: Int = 0,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_SENT = "sent"
        const val STATUS_ACKNOWLEDGED = "acknowledged"

        /** The server refused it: the product had already moved on (D017). Kept, not retried blindly. */
        const val STATUS_CONFLICT = "conflict"

        /** The server would not take it at all — a bad payload or an unknown entity. */
        const val STATUS_REJECTED = "rejected"
    }
}
