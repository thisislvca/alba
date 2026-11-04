package me.lvca.alba.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.lvca.alba.app.platform.BackupPreferences
import me.lvca.alba.app.work.WorkManagerWakeupScheduler

class ReconciliationTimerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // The run it asks for waits for the charger too when the user chose that.
        val preferences = BackupPreferences(applicationContext)
        WorkManagerWakeupScheduler(applicationContext) { preferences.settings.value }.scheduleSoon()
        return Result.success()
    }
}
