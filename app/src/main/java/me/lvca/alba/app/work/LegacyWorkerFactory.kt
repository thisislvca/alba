package me.lvca.alba.app.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import me.lvca.alba.app.BackupWakeWorker
import me.lvca.alba.app.ReconciliationTimerWorker
import me.lvca.alba.app.UserTransferWorker

/** WorkManager stores worker class names, so work queued before the package rename still names dev.mela classes. */
internal class LegacyWorkerFactory : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        LEGACY_WORKERS[workerClassName]
            ?.getDeclaredConstructor(Context::class.java, WorkerParameters::class.java)
            ?.newInstance(appContext, workerParameters)

    companion object {
        val LEGACY_WORKERS: Map<String, Class<out ListenableWorker>> = mapOf(
            "dev.mela.app.BackupWakeWorker" to BackupWakeWorker::class.java,
            "dev.mela.app.ReconciliationTimerWorker" to ReconciliationTimerWorker::class.java,
            "dev.mela.app.UserTransferWorker" to UserTransferWorker::class.java,
        )
    }
}
