package com.hisab.app.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `SyncWorker.doWork()` end to end (Step 62-63). No fake `SyncApi` here —
 * `SyncWorker` builds its own `HttpSyncApi`, the same way `SyncViewModel`
 * does (D045), so there is no seam to substitute one. Each case below is a
 * real condition instead: no saved session, and a server address nothing is
 * listening on. `SyncWorkerOutcomeTest` covers the exception-to-`Result`
 * mapping itself, in isolation, without a device.
 *
 * Unlike `SyncEngineTest`, this does not use an in-memory database: a
 * `SyncWorker` always opens the app's own real database through
 * `HisabDatabase.get()`, exactly as it will in production. Both cases here
 * fail before either engine call reaches a DAO write, so nothing already on
 * the phone is touched.
 */
@RunWith(AndroidJUnit4::class)
class SyncWorkerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearSavedSettings()
    }

    @After
    fun tearDown() {
        clearSavedSettings()
    }

    private fun clearSavedSettings() {
        context
            .getSharedPreferences("hisab_sync", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun withNoSavedSessionTheWorkerSucceedsWithNothingToDo() =
        runBlocking {
            val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()

            assertEquals(Result.success(), worker.doWork())
        }

    @Test
    fun anUnreachableServerIsRetriedRatherThanFailedOutright() =
        runBlocking {
            val settings = SyncSettings(context)
            settings.email = "rahim@example.com"
            settings.token = "test-token"
            // Nothing listens on this port; HttpSyncApi's connection attempt
            // fails with a genuine IOException, the same as a phone with no
            // signal — no fake needed to produce a real network failure.
            settings.serverUrl = "http://127.0.0.1:1"

            val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()

            assertEquals(Result.retry(), worker.doWork())
        }
}
