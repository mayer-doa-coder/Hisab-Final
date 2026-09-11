package com.hisab.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TimeTest {
    @Test
    fun `a transaction created offline has no server_received_at yet`() {
        val time = TransactionTime.atCreation(Instant.parse("2026-01-01T08:00:00Z"))
        assertNull(time.serverReceivedAt)
        assertFalse(time.isSynced)
    }

    // The scenario D019/D016 explicitly call out: an offline sale that syncs
    // hours later must keep reporting when it actually happened, not when
    // the server happened to receive it. Same case as
    // server/src/domain/time.test.ts.
    @Test
    fun `occurredAt and serverReceivedAt can differ by hours`() {
        val occurredAt = Instant.parse("2026-01-01T08:00:00Z") // sale made in the shop, offline
        val receivedAt = Instant.parse("2026-01-01T14:00:00Z") // phone reconnects hours later

        val created = TransactionTime.atCreation(occurredAt)
        val synced = created.markServerReceived(receivedAt)

        assertEquals(occurredAt, synced.occurredAt)
        assertEquals(receivedAt, synced.serverReceivedAt)
        assertTrue(synced.occurredAt != synced.serverReceivedAt)
        assertTrue(synced.isSynced)
    }
}
