package me.lvca.alba.app.work

import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.lvca.alba.app.NewMediaWorker
import me.lvca.alba.app.work.WorkManagerWakeupScheduler.Companion.IMMEDIATE_WORK_NAME
import me.lvca.alba.app.work.WorkManagerWakeupScheduler.Companion.NEW_MEDIA_WORK_NAME
import me.lvca.alba.engine.model.BackupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * A new photo backs up within seconds, even with Alba closed: while backup is on, a one-shot WorkManager job watches
 * MediaStore's images and videos, waits for the network and charger backup may use, asks for one backup run and
 * arms the next watch. Turning backup off removes the watch.
 */
@RunWith(AndroidJUnit4::class)
class NewMediaTriggerTest {
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
    fun turningBackupOnWatchesNewPhotosAndVideosUnderTheBackupSettings() = runBlocking {
        scheduler.setAutomaticBackupEnabled(true)

        val watch = waitingWatch()
        val constraints = watch.constraints
        assertEquals(
            setOf(
                Constraints.ContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true),
                Constraints.ContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true),
            ),
            constraints.contentUriTriggers,
        )
        assertEquals(5_000L, constraints.contentTriggerUpdateDelayMillis)
        assertEquals(30_000L, constraints.contentTriggerMaxDelayMillis)
        assertEquals("Wi-Fi only by default", NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresCharging())

        settings = BackupSettings(photosOnMobileData = true, onlyWhileCharging = true)
        scheduler.setAutomaticBackupEnabled(true)

        val changed = waitingWatch()
        assertNotEquals("The watch is replaced with the new settings", watch.id, changed.id)
        assertEquals(NetworkType.NOT_ROAMING, changed.constraints.requiredNetworkType)
        assertTrue(changed.constraints.requiresCharging())
        assertEquals(1, unfinished(NEW_MEDIA_WORK_NAME).size)
    }

    @Test
    fun turningBackupOffRemovesTheWatchAndNothingArmsItAgain() = runBlocking {
        scheduler.setAutomaticBackupEnabled(true)
        scheduler.setAutomaticBackupEnabled(false)
        assertTrue(unfinished(NEW_MEDIA_WORK_NAME).isEmpty())

        // A watch that fired just before backup was turned off, and the periodic run, arm nothing.
        TestListenableWorkerBuilder<NewMediaWorker>(context).build().doWork()
        scheduler.ensureNewMediaTrigger()
        assertTrue(unfinished(NEW_MEDIA_WORK_NAME).isEmpty())
    }

    @Test
    fun aBurstOfNewPhotosAsksForOneBackupRunAndEachFiredWatchArmsTheNext() = runBlocking {
        scheduler.setAutomaticBackupEnabled(true)
        val first = waitingWatch().id

        fire(first)
        val second = waitingWatch().id
        assertNotEquals("A content-triggered job fires once, so the run arms another", first, second)
        assertEquals(1, waitingRuns())

        // More photos while that run still waits to start: the watch fires again, and the same run covers them.
        fire(second)
        assertEquals(1, waitingRuns())
        assertEquals(1, unfinished(NEW_MEDIA_WORK_NAME).size)
    }

    @Test
    fun theBackstopArmsAWatchOnlyWhenNoneIsWaiting() = runBlocking {
        scheduler.setAutomaticBackupEnabled(true)
        val armed = waitingWatch().id

        scheduler.ensureNewMediaTrigger()

        assertEquals(armed, waitingWatch().id)
        // Backup turned on before Alba watched MediaStore: the periodic run arms the watch.
        workManager.cancelUniqueWork(NEW_MEDIA_WORK_NAME).result.get()
        scheduler.ensureNewMediaTrigger()
        assertEquals(1, unfinished(NEW_MEDIA_WORK_NAME).size)
    }

    /** MediaStore changed and the network and charger allow it: the watch runs to the end. */
    private suspend fun fire(id: UUID) {
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)
        withTimeout(10_000) { workManager.getWorkInfoByIdFlow(id).first { it?.state?.isFinished == true } }
        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(id).get()!!.state)
    }

    private fun waitingWatch() = workManager.getWorkInfosForUniqueWork(NEW_MEDIA_WORK_NAME).get()
        .single { it.state == WorkInfo.State.ENQUEUED }

    private fun waitingRuns() = workManager.getWorkInfosForUniqueWork(IMMEDIATE_WORK_NAME).get()
        .count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }

    private fun unfinished(name: String) = workManager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }
}
