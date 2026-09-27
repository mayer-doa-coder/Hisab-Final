package com.hisab.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

/**
 * Starts and re-triggers the background sync (Steps 62-63). `SyncEngine`
 * itself knows nothing about WorkManager (D045) — this is the only place
 * that does, and `SyncWorker` is the only thing that runs it.
 *
 * Two requests share that one worker, not two different implementations:
 * - [schedulePeriodic] is the durable backstop. It is what makes a sync
 *   attempt survive the app being killed (Step 64) or the phone restarting
 *   (Step 65): WorkManager keeps this request in its own on-disk queue,
 *   which the OS reschedules after a reboot by itself — nothing in this app
 *   listens for `BOOT_COMPLETED`.
 * - [triggerNow] is enqueued the moment a network appears (see
 *   `HisabApplication`'s network callback), so "turning wifi on" does not
 *   have to wait for the periodic interval to come around (Step 62).
 *
 * Both requests carry the same connectivity constraint and the same
 * exponential backoff, so a run `SyncWorker` marks retryable is retried
 * later rather than given up on, or repeated immediately (Step 63).
 */
object SyncScheduler {
    const val PERIODIC_WORK_NAME = "periodic_sync"
    const val NETWORK_TRIGGERED_WORK_NAME = "network_triggered_sync"

    /** The shortest interval `PeriodicWorkRequest` allows — its own enforced floor. */
    private const val PERIODIC_INTERVAL_MINUTES = 15L

    private val CONSTRAINTS = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(CONSTRAINTS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()

        // KEEP: re-running this at every process start (HisabApplication.onCreate)
        // must not reset an already-running schedule back to zero.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Called when the phone gets a network again, so a sync does not wait for the next period. */
    fun triggerNow(context: Context) {
        val request =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(CONSTRAINTS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()

        // KEEP, not REPLACE: a network flapping on/off/on must not cancel a
        // sync that is already running because of the first "on".
        WorkManager.getInstance(context).enqueueUniqueWork(
            NETWORK_TRIGGERED_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
