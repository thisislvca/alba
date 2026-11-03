package me.lvca.alba.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Asking for a backup run many times leaves one run waiting, never a chain of empty ones. */
@RunWith(AndroidJUnit4::class)
class WakeupSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    fun manyRequestsLeaveOneRunWaiting() = runBlocking {
        val scheduler = WorkManagerWakeupScheduler(context)
        repeat(50) { scheduler.scheduleSoon() }
        val waiting = work().filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        assertEquals(1, waiting.size)
    }

    @Test
    fun aRequestBehindARunningOrFinishedRunIsQueuedOnceAndOneAlreadyWaitingCoversIt() {
        assertTrue("Nothing queued", needsAnotherRun(emptyList()))
        assertTrue("Running, nothing behind it: an item the UI just queued needs its own run",
            needsAnotherRun(listOf(WorkInfo.State.RUNNING)))
        assertTrue(needsAnotherRun(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED)))
        assertFalse(needsAnotherRun(listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)))
        assertFalse(needsAnotherRun(listOf(WorkInfo.State.ENQUEUED)))
    }

    private fun work() = WorkManager.getInstance(context).getWorkInfosForUniqueWork("mela-photo-maintenance").get()
}
