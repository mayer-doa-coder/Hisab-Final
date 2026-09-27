package com.hisab.app.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What `SyncScheduler` actually enqueues (Steps 62-63) — checked against
 * WorkManager's own test harness, not a real network or a real sync: the
 * right kind of request, under a name that stays unique, requiring a
 * network, the way PHASE_GUIDE.md's checks ask for.
 */
@RunWith(AndroidJUnit4::class)
class SyncSchedulerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun periodicSyncIsEnqueuedOnceUnderItsOwnName() {
        SyncScheduler.schedulePeriodic(context)

        val infos =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK_NAME)
                .get()

        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.first().state)
        assertTrue(
            "the periodic request must require a network, not run with none",
            infos.first().constraints.requiredNetworkType == NetworkType.CONNECTED,
        )
    }

    @Test
    fun schedulingTwiceDoesNotDuplicateTheWork() {
        SyncScheduler.schedulePeriodic(context)
        SyncScheduler.schedulePeriodic(context)

        val infos =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK_NAME)
                .get()

        assertEquals("re-scheduling on every app start must not pile up requests", 1, infos.size)
    }

    @Test
    fun aNetworkAppearingEnqueuesAOneTimeSyncUnderItsOwnName() {
        SyncScheduler.triggerNow(context)

        val infos =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork(SyncScheduler.NETWORK_TRIGGERED_WORK_NAME)
                .get()

        assertEquals(1, infos.size)
        assertTrue(
            "the network-triggered request must require a network too",
            infos.first().constraints.requiredNetworkType == NetworkType.CONNECTED,
        )
    }

    @Test
    fun theNetworkFlappingDoesNotCancelASyncAlreadyRunning() {
        SyncScheduler.triggerNow(context)
        val first =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork(SyncScheduler.NETWORK_TRIGGERED_WORK_NAME)
                .get()
                .first()
                .id

        // A second "network available" callback while the first request is
        // still pending must not replace it (the KEEP policy in
        // SyncScheduler.triggerNow).
        SyncScheduler.triggerNow(context)
        val second =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork(SyncScheduler.NETWORK_TRIGGERED_WORK_NAME)
                .get()
                .first()
                .id

        assertEquals(first, second)
    }
}
