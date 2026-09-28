package com.hisab.app.ui.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which one thing the sync screen tells the shopkeeper (Step 66). The codes
 * are language-neutral (D011); `SyncScreenTest` on the phone is what checks
 * the Bangla and English sentences themselves.
 */
class SyncStatusTest {
    private fun statusFor(
        running: Boolean = false,
        needsSignIn: Boolean = false,
        lastAttemptFailed: Boolean = false,
        hasSyncedBefore: Boolean = false,
        waitingToSend: Int = 0,
        refused: Int = 0,
    ) = syncStatusFor(running, needsSignIn, lastAttemptFailed, hasSyncedBefore, waitingToSend, refused)

    @Test
    fun `a phone that has never synced says so rather than claiming to be up to date`() {
        assertEquals(SyncStatusCode.SYNC_NEVER, statusFor().code)
    }

    @Test
    fun `nothing waiting, after a sync that worked, is the only thing that reads as synced`() {
        assertEquals(SyncStatusCode.SYNC_SYNCED, statusFor(hasSyncedBefore = true).code)
    }

    @Test
    fun `work saved here but not sent yet is counted, not hidden`() {
        val status = statusFor(hasSyncedBefore = true, waitingToSend = 3)
        assertEquals(SyncStatusCode.SYNC_PENDING, status.code)
        assertEquals(3, status.waitingToSend)
    }

    @Test
    fun `a phone that has never synced still reports what is waiting`() {
        assertEquals(SyncStatusCode.SYNC_PENDING, statusFor(waitingToSend = 2).code)
    }

    @Test
    fun `a server that could not be reached outranks the count of what is waiting`() {
        val status = statusFor(lastAttemptFailed = true, waitingToSend = 3)
        assertEquals(SyncStatusCode.SYNC_UNREACHABLE, status.code)
    }

    @Test
    fun `a refused change outranks a failed attempt - waiting will not fix it`() {
        val status = statusFor(lastAttemptFailed = true, waitingToSend = 3, refused = 1)
        assertEquals(SyncStatusCode.SYNC_CONFLICT, status.code)
        assertEquals(1, status.refused)
    }

    @Test
    fun `signing in comes before anything about data, because nothing can happen without it`() {
        val status = statusFor(needsSignIn = true, waitingToSend = 5, refused = 2)
        assertEquals(SyncStatusCode.SYNC_NOT_SIGNED_IN, status.code)
    }

    @Test
    fun `a sync in progress is what is shown while it runs`() {
        val status = statusFor(running = true, needsSignIn = true, refused = 2)
        assertEquals(SyncStatusCode.SYNC_RUNNING, status.code)
    }
}
