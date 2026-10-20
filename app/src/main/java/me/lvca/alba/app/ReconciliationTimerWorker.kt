package me.lvca.alba.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.lvca.alba.app.work.WorkManagerWakeupScheduler

class ReconciliationTimerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WorkManagerWakeupScheduler(applicationContext).scheduleSoon()
        return Result.success()
    }
}
