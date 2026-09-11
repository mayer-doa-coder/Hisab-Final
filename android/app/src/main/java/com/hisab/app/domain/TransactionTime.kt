package com.hisab.app.domain

import java.time.Instant

/**
 * Every transaction records when it actually happened (occurredAt, set by
 * the device — possibly offline, possibly hours before syncing) separately
 * from when the backend actually processed it (serverReceivedAt).
 *
 * Never treat server-receipt time as transaction time — an offline sale that
 * syncs hours later must still report its real occurredAt.
 * See PRD.md section 24, DECISIONS.md D019.
 *
 * `java.time.Instant` is used natively (no core-library desugaring needed):
 * it has been part of the Android platform since API 26, which is exactly
 * this project's minSdk (D025).
 */
data class TransactionTime(
    val occurredAt: Instant,
    val serverReceivedAt: Instant?,
) {
    val isSynced: Boolean get() = serverReceivedAt != null

    companion object {
        fun atCreation(occurredAt: Instant): TransactionTime = TransactionTime(occurredAt, serverReceivedAt = null)
    }
}

/** Called once, when the backend actually confirms it has processed the sync event. */
fun TransactionTime.markServerReceived(receivedAt: Instant): TransactionTime = copy(serverReceivedAt = receivedAt)
