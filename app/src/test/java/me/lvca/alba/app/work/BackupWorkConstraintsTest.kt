package me.lvca.alba.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import me.lvca.alba.engine.model.BackupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Every backup run, the soon one that may turn into the foreground batch and the periodic one, waits for the charger when asked. */
@RunWith(AndroidJUnit4::class)
class BackupWorkConstraintsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var settings = BackupSettings()
    private lateinit var scheduler: WorkManagerWakeupScheduler

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        scheduler = WorkManagerWakeupScheduler(context) { settings }
    }

    @Test
    fun backupRunsRequireChargingOnlyWhenTheUserChoseIt() = runBlocking {
        scheduler.scheduleSoon()
        scheduler.setAutomaticBackupEnabled(true)
        assertFalse(waiting(IMMEDIATE).constraints.requiresCharging())
        assertFalse(waiting(PERIODIC).constraints.requiresCharging())

        settings = BackupSettings(onlyWhileCharging = true)
        scheduler.setAutomaticBackupEnabled(true)
        scheduler.scheduleSoon()

        val soon = waiting(IMMEDIATE)
        assertTrue("The waiting run is replaced, not left without the charger", soon.constraints.requiresCharging())
        assertEquals(NetworkType.CONNECTED, soon.constraints.requiredNetworkType)
        val periodic = waiting(PERIODIC)
        assertTrue(periodic.constraints.requiresCharging())
        assertEquals(NetworkType.UNMETERED, periodic.constraints.requiredNetworkType)
        assertEquals(1, work(IMMEDIATE).count { it.state == WorkInfo.State.ENQUEUED })

        scheduler.scheduleSoon()
        assertEquals("A run already waiting under the same choice covers the request", soon.id, waiting(IMMEDIATE).id)

        settings = BackupSettings()
        scheduler.scheduleSoon()
        assertFalse("Turning it off frees a run that waited for the charger", waiting(IMMEDIATE).constraints.requiresCharging())
    }

    @Test
    fun aRunWaitingUnderAnotherChargingChoiceIsReplacedOnlyWhenNoneRuns() {
        val enqueued = WorkInfo.State.ENQUEUED
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, immediateWorkPolicy(emptyList(), onlyWhileCharging = true))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, immediateWorkPolicy(listOf(WorkInfo.State.RUNNING to false), true))
        assertNull(immediateWorkPolicy(listOf(enqueued to true), onlyWhileCharging = true))
        assertEquals(ExistingWorkPolicy.REPLACE, immediateWorkPolicy(listOf(enqueued to false), onlyWhileCharging = true))
        val runningAndBlocked = listOf(WorkInfo.State.RUNNING to false, WorkInfo.State.BLOCKED to false)
        assertNull("Replacing would stop the running pass", immediateWorkPolicy(runningAndBlocked, onlyWhileCharging = true))
    }

    private fun work(name: String) = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    private fun waiting(name: String) = work(name).single { it.state == WorkInfo.State.ENQUEUED }

    private companion object {
        const val IMMEDIATE = "mela-photo-maintenance"
        const val PERIODIC = "mela-automatic-photo-backup"
    }
}
