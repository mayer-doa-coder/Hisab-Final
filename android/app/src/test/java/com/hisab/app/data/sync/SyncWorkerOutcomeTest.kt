package com.hisab.app.data.sync

import androidx.work.ListenableWorker.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * What `SyncWorker.doWork()` does with a failed sync (Step 63), tested as a
 * plain function so it needs no device — see `SyncWorker.syncOutcomeFor`.
 */
class SyncWorkerOutcomeTest {
    @Test
    fun `nothing saved to sign in with yet is not a failure - there is simply nothing to do`() {
        assertEquals(Result.success(), syncOutcomeFor(NotSignedInException()))
    }

    @Test
    fun `a dead session is not retried - only a person signing in again can fix it`() {
        assertEquals(Result.success(), syncOutcomeFor(SyncException("nope", statusCode = 401)))
        assertEquals(Result.success(), syncOutcomeFor(SyncException("nope", statusCode = 403)))
    }

    @Test
    fun `a server error is retried later, not given up on`() {
        assertEquals(Result.retry(), syncOutcomeFor(SyncException("server exploded", statusCode = 500)))
    }

    @Test
    fun `a plain network failure is retried later too`() {
        assertEquals(Result.retry(), syncOutcomeFor(IOException("connection refused")))
    }

    @Test
    fun `anything else is a bug, not a sync condition - it is not swallowed`() {
        val error = IllegalStateException("should never happen")
        val thrown = assertThrows(IllegalStateException::class.java) { syncOutcomeFor(error) }
        assertEquals(error, thrown)
    }
}
