package com.hisab.app.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.hisab.app.data.HisabDatabase
import java.io.IOException

/**
 * Runs one background sync (Steps 62-63), built the same way
 * `SyncViewModel.syncNow()` builds one for the sync screen (D045) — there is
 * exactly one place that knows how to assemble a `SyncEngine`.
 *
 * See [syncOutcomeFor] for what each kind of failure means to WorkManager.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = SyncSettings(applicationContext)
        val database = HisabDatabase.get(applicationContext)
        val engine = SyncEngine(database, HttpSyncApi(settings.serverUrl), settings)

        return try {
            engine.sync()
            Result.success()
        } catch (error: Throwable) {
            syncOutcomeFor(error)
        }
    }
}

/**
 * What one failed sync attempt means for WorkManager, kept as a plain
 * function so it can be tested without a device (`SyncWorkerOutcomeTest`).
 *
 * - Nothing saved to sign in with yet, or the saved token is no longer good
 *   (401/403): [Result.success]. Neither is a fault this worker can fix by
 *   trying again with no new information — only a person signing in again
 *   from the Sync screen can. Retrying would only spam the server for
 *   nothing, which Step 63 explicitly asks not to do.
 * - A network problem, a timeout, or any other server error:
 *   [Result.retry], so WorkManager tries again later under the exponential
 *   backoff `SyncScheduler` set on the request (Step 63), instead of
 *   silently waiting for the next scheduled period.
 * - Anything else is rethrown: a bug in this app, not a sync condition this
 *   function should decide the meaning of.
 */
internal fun syncOutcomeFor(error: Throwable): Result =
    when {
        error is NotSignedInException -> Result.success()
        error is SyncException && (error.statusCode == 401 || error.statusCode == 403) -> Result.success()
        error is IOException -> Result.retry()
        else -> throw error
    }
