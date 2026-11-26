package me.lvca.alba.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.lvca.alba.app.platform.BackupPreferences
import me.lvca.alba.app.work.WorkManagerWakeupScheduler

/**
 * A new photo or video reached MediaStore, possibly with Alba closed: arm the next watch, then ask for a backup run.
 * The run is the same one every other wake asks for, so a burst of photos becomes one run that drains them all; it
 * finds them past the enrollment baseline as it always does.
 */
class NewMediaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val preferences = BackupPreferences(applicationContext)
        val scheduler = WorkManagerWakeupScheduler(applicationContext) { preferences.settings.value }
        // Armed first, so a photo taken while backup runs wakes it again; a stopped watch arms nothing.
        if (!isStopped) scheduler.rearmNewMediaTrigger()
        scheduler.scheduleSoon()
        return Result.success()
    }
}
