package me.lvca.alba.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.work.WorkManagerWakeupScheduler.Companion.IMMEDIATE_CHARGING_WORK_NAME
import me.lvca.alba.app.work.WorkManagerWakeupScheduler.Companion.IMMEDIATE_WORK_NAME
import me.lvca.alba.app.work.WorkManagerWakeupScheduler.Companion.PERIODIC_WORK_NAME
import me.lvca.alba.engine.model.BackupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every backup run, the soon one that may become the foreground batch and the periodic one, waits for the charger when
 * asked, and changing that choice never cancels a run.
 */
@RunWith(AndroidJUnit4::class)
class BackupWorkConstraintsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var settings = BackupSettings()
    private lateinit var scheduler: WorkManagerWakeupScheduler
    private val workManager get() = WorkManager.getInstance(context)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        scheduler = WorkManagerWakeupScheduler(context) { settings }
    }

    @Test
    fun backupRunsRequireChargingOnlyWhenTheUserChoseIt() = runBlocking {
        scheduler.scheduleSoon()
        scheduler.setAutomaticBackupEnabled(true)
        assertFalse(waiting(IMMEDIATE_WORK_NAME).constraints.requiresCharging())
        assertFalse(waiting(PERIODIC_WORK_NAME).constraints.requiresCharging())

        settings = BackupSettings(onlyWhileCharging = true)
        scheduler.setAutomaticBackupEnabled(true)
        scheduler.scheduleSoon()

        val soon = waiting(IMMEDIATE_CHARGING_WORK_NAME)
        assertTrue(soon.constraints.requiresCharging())
        assertEquals(NetworkType.CONNECTED, soon.constraints.requiredNetworkType)
        val periodic = waiting(PERIODIC_WORK_NAME)
        assertTrue(periodic.constraints.requiresCharging())
        assertEquals(NetworkType.UNMETERED, periodic.constraints.requiredNetworkType)

        scheduler.scheduleSoon()
        assertEquals("A run already waiting under the same choice covers the request", soon.id, waiting(IMMEDIATE_CHARGING_WORK_NAME).id)
        assertTrue("Changing the choice cancels nothing", all().none { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun turningChargingOffStartsARunWithoutWaitingForTheQueuedOneAndCancelsNothing() = runBlocking {
        // A pass is running and a charging-only run is queued behind it.
        val running = startBlockingRun(IMMEDIATE_CHARGING_WORK_NAME)
        settings = BackupSettings(onlyWhileCharging = true)
        scheduler.scheduleSoon()
        val queued = work(IMMEDIATE_CHARGING_WORK_NAME).single { it.id != running }
        assertEquals(WorkInfo.State.BLOCKED, queued.state)

        settings = BackupSettings()
        scheduler.setAutomaticBackupEnabled(true)
        scheduler.scheduleSoon()

        val freed = waiting(IMMEDIATE_WORK_NAME)
        assertFalse("The new run does not wait for the charger", freed.constraints.requiresCharging())
        assertEquals(WorkInfo.State.RUNNING, state(running))
        assertEquals("The queued run is left alone, not cancelled", WorkInfo.State.BLOCKED, state(queued.id))
        assertFalse(waiting(PERIODIC_WORK_NAME).constraints.requiresCharging())
    }

    @Test
    fun aRunningBatchIsNeverCancelledByChangingTheChargingChoice() = runBlocking {
        val running = startBlockingRun(IMMEDIATE_WORK_NAME)
        repeat(3) {
            settings = BackupSettings(onlyWhileCharging = it % 2 == 0)
            scheduler.setAutomaticBackupEnabled(true)
            scheduler.scheduleSoon()
            assertEquals(WorkInfo.State.RUNNING, state(running))
        }
        settings = BackupSettings()
        scheduler.scheduleSoon()
        assertEquals(WorkInfo.State.RUNNING, state(running))
        assertTrue(all().none { it.state == WorkInfo.State.CANCELLED })
    }

    /** Starts a run under [name] that stays running until the test ends, as a long foreground batch does. */
    private fun startBlockingRun(name: String): java.util.UUID {
        val request = OneTimeWorkRequestBuilder<BlockingWorker>().setConstraints(Constraints.NONE).build()
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request).result.get()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(request.id)
        assertEquals(WorkInfo.State.RUNNING, state(request.id))
        return request.id
    }

    private fun state(id: java.util.UUID) = workManager.getWorkInfoById(id).get()!!.state

    private fun work(name: String) = workManager.getWorkInfosForUniqueWork(name).get()

    private fun all() = listOf(IMMEDIATE_WORK_NAME, IMMEDIATE_CHARGING_WORK_NAME, PERIODIC_WORK_NAME).flatMap(::work)

    private fun waiting(name: String) = work(name).single { it.state == WorkInfo.State.ENQUEUED }

    /** A pass that runs until it is stopped. */
    class BlockingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = awaitCancellation()
    }
}
