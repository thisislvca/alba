package me.lvca.alba.app

import android.app.Application
import me.lvca.alba.app.di.AlbaGraph
import me.lvca.alba.app.diagnostics.DiagnosticOperation
import me.lvca.alba.app.diagnostics.DiagnosticOutcome
import me.lvca.alba.app.diagnostics.SentryAlbaDiagnostics
import me.lvca.alba.app.work.LegacyWorkerFactory
import androidx.work.Configuration

class AlbaApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            // Keep WorkManager IDs separate from the user-initiated transfer job.
            .setJobSchedulerJobIdRange(10_000, 19_999)
            .setWorkerFactory(LegacyWorkerFactory())
            .build()

    lateinit var diagnostics: SentryAlbaDiagnostics
        private set

    override fun onCreate() {
        super.onCreate()
        diagnostics = SentryAlbaDiagnostics(this)
        diagnostics.initialize()
        diagnostics.start(DiagnosticOperation.APP_INITIALIZED).finish(DiagnosticOutcome.SUCCESS)
    }

    val graph: AlbaGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AlbaGraph(this, diagnostics) }
}
