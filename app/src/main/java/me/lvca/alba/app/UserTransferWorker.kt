package me.lvca.alba.app

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import me.lvca.alba.app.work.TRANSFER_NOTIFICATION_ID
import me.lvca.alba.app.work.runUserTransfers
import me.lvca.alba.app.work.transferNotification

class UserTransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        setForeground(ForegroundInfo(TRANSFER_NOTIFICATION_ID, transferNotification(applicationContext),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        return try {
            runUserTransfers(applicationContext as AlbaApplication)
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}
