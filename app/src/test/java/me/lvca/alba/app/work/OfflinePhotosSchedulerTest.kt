package me.lvca.alba.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The offline set fills as one unique piece of work, only on Wi-Fi while charging, with battery and storage not low. */
@RunWith(AndroidJUnit4::class)
class OfflinePhotosSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    fun theFillWaitsForWifiChargingBatteryAndStorageAndIsQueuedOnce() {
        val scheduler = WorkManagerOfflinePhotosScheduler(context)
        scheduler.schedule()
        val first = work().single()
        // Start, signing in again and new photos all schedule it: what is queued stays.
        scheduler.schedule()

        val queued = work().single()
        assertEquals(first.id, queued.id)
        assertEquals(WorkInfo.State.ENQUEUED, queued.state)
        with(queued.constraints) {
            assertEquals(NetworkType.UNMETERED, requiredNetworkType)
            assertTrue(requiresCharging())
            assertTrue(requiresBatteryNotLow())
            assertTrue(requiresStorageNotLow())
        }
    }

    @Test
    fun aBudgetChangeReplacesTheQueuedFillAndCancelStopsIt() {
        val scheduler = WorkManagerOfflinePhotosScheduler(context)
        scheduler.schedule()
        val first = work().single()

        scheduler.schedule(replace = true)
        val current = work().single { it.state == WorkInfo.State.ENQUEUED }
        assertNotEquals(first.id, current.id)
        // The old one is cancelled and pruned: it never runs.
        val old = WorkManager.getInstance(context).getWorkInfoById(first.id).get()
        assertTrue(old == null || old.state == WorkInfo.State.CANCELLED)

        scheduler.cancel()
        assertTrue(work().all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun applesLongerWaitFollowsTheRunThatWasThrottled() {
        val scheduler = WorkManagerOfflinePhotosScheduler(context)
        scheduler.schedule()
        scheduler.scheduleAfter(delayMillis = 2 * 60 * 60 * 1_000L)

        // It waits for the run that asked for it, then for Apple's wait, under the same name.
        val later = work().single { it.state == WorkInfo.State.BLOCKED }
        assertEquals(2 * 60 * 60 * 1_000L, later.initialDelayMillis)
        assertEquals(WorkInfo.State.ENQUEUED, work().single { it.id != later.id }.state)
    }

    private fun work() = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(WorkManagerOfflinePhotosScheduler.WORK_NAME).get()
}
