package dev.mela.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.first
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.mela.engine.cache.MediaFileCache
import dev.mela.engine.cache.UploadStagingStore
import dev.mela.engine.companion.DefaultPhotoCompanion
import dev.mela.engine.database.MelaDatabase
import dev.mela.engine.model.MaintenanceWakeup
import dev.mela.engine.model.AutomaticBackupPolicy
import dev.mela.engine.model.WakeupScheduler
import dev.mela.engine.repository.DefaultGalleryRepository
import dev.mela.engine.source.AndroidMediaStoreSource
import dev.mela.protocol.account.AndroidEncryptedSessionStore
import dev.mela.protocol.account.ICloudAccountManager
import dev.mela.protocol.account.ICloudAccountState
import dev.mela.protocol.account.SessionStatus
import dev.mela.protocol.auth.AppleAuthenticationService
import dev.mela.protocol.fixture.FixtureICloudCatalogSource
import dev.mela.protocol.network.OkHttpAppleTransport
import dev.mela.protocol.photos.AccountAwareICloudCatalogSource
import dev.mela.protocol.photos.LiveICloudCatalogSource
import dev.mela.protocol.photos.LiveICloudPhotoProtocol
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

class MelaApplication : Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            // Keep WorkManager IDs separate from the user-initiated transfer job.
            .setJobSchedulerJobIdRange(10_000, 19_999)
            .build()

    lateinit var diagnostics: SentryMelaDiagnostics
        private set

    override fun onCreate() {
        super.onCreate()
        diagnostics = SentryMelaDiagnostics(this)
        diagnostics.initialize()
        diagnostics.start(DiagnosticOperation.APP_INITIALIZED).finish(DiagnosticOutcome.SUCCESS)
    }

    val graph: MelaGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { MelaGraph(this, diagnostics) }
}

class MelaGraph(context: Context, val diagnostics: SentryMelaDiagnostics) {
    private val appContext = context.applicationContext
    val database = MelaDatabase.create(appContext)
    private val transport = OkHttpAppleTransport()
    private val authentication = AppleAuthenticationService(transport, telemetry = diagnostics)
    val accountManager = ICloudAccountManager(
        authentication = authentication,
        sessionStore = AndroidEncryptedSessionStore(appContext),
        telemetry = diagnostics,
        onLocalSignOut = ::clearSignedOutData,
    )
    private val mediaStore = AndroidMediaStoreSource(appContext)
    private val liveCatalog = LiveICloudCatalogSource(
        transport = transport,
        isAuthorized = { accountManager.authorizedSession != null },
        sessionProvider = { accountManager.session.value },
        onSessionUpdated = accountManager::updateSession,
        onSessionRejected = accountManager::rejectSession,
        sharedJournal = dev.mela.protocol.photos.EncryptedUploadJournal(database.libraryDao()),
    )
    private val cloudCatalog = AccountAwareICloudCatalogSource(FixtureICloudCatalogSource(
        includeSharedAlbums = true,
        openPhoto = { index -> appContext.assets.open(if (index in setOf(19, 20)) "demo/ocean.webp" else "demo/${index.toString().padStart(2, '0')}.webp") },
        openVideo = { appContext.assets.open("fixture_video.mp4") },
    ), liveCatalog) { accountManager.session.value }
    val galleryRepository = DefaultGalleryRepository(
        database = database,
        cloudSource = cloudCatalog,
        deviceSource = mediaStore,
        cache = MediaFileCache(appContext),
    )
    private suspend fun clearSignedOutData() {
        batches.cancelActive()
        galleryRepository.clearCloudCatalog()
        GalleryExporter(appContext, galleryRepository).clearShares()
    }

    val photos = DefaultPhotoCompanion(
        database = database,
        originals = mediaStore,
        staging = UploadStagingStore(appContext),
        protocol = LiveICloudPhotoProtocol(
            transport = transport,
            sessionProvider = { accountManager.session.value },
            isAuthorized = { accountManager.authorizedSession != null },
            onSessionUpdated = accountManager::updateSession,
            onSessionRejected = accountManager::rejectSession,
            uploadJournal = dev.mela.protocol.photos.EncryptedUploadJournal(database.libraryDao()),
        ),
        scheduler = WorkManagerWakeupScheduler(appContext),
        automaticBackupPolicy = AndroidAutomaticBackupPolicy(appContext),
    )
    val maintenance: MaintenanceWakeup = photos
    val batches = dev.mela.engine.companion.GalleryBatchActions(database.libraryDao(), galleryRepository, photos,
        saveToPhone = { id ->
            val media = galleryRepository.findMedia(id)
                ?: error("The photo is no longer available.")
            GalleryExporter(appContext, galleryRepository).saveToGallery(media)
        }) { cloudCatalog.accountLabel }
}

class AndroidAutomaticBackupPolicy(context: Context) : AutomaticBackupPolicy {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun isUnmetered(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

class WorkManagerWakeupScheduler(context: Context) : WakeupScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val backgroundNetwork = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .build()
    private val connectedNetwork = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    override suspend fun scheduleSoon() {
        val request = OneTimeWorkRequestBuilder<BackupWakeWorker>()
            .setConstraints(connectedNetwork)
            .setInitialDelay(3, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    override suspend fun scheduleAt(notBeforeEpochMillis: Long) {
        // A separate timer avoids cancelling the maintenance worker that schedules it.
        val request = OneTimeWorkRequestBuilder<ReconciliationTimerWorker>()
            .setInitialDelay((notBeforeEpochMillis - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork("mela-reconciliation-timer", ExistingWorkPolicy.REPLACE, request)
    }

    override suspend fun setAutomaticBackupEnabled(enabled: Boolean) {
        if (!enabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWakeWorker>(15, TimeUnit.MINUTES)
            .setConstraints(backgroundNetwork)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private companion object {
        const val IMMEDIATE_WORK_NAME = "mela-photo-maintenance"
        const val PERIODIC_WORK_NAME = "mela-automatic-photo-backup"
    }
}

class BackupWakeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withTimeoutOrNull(8 * 60_000L) { runPass() } ?: Result.retry()

    private suspend fun runPass(): Result {
        val app = applicationContext as MelaApplication
        val span = app.diagnostics.start(
            DiagnosticOperation.MAINTENANCE_WAKE,
            mapOf(DiagnosticAttribute.REASON to "background"),
        )
        return try {
            if (backgroundSessionDecision(
                    state = app.graph.accountManager.state.value,
                    hasSavedSession = app.graph.accountManager.session.value != null,
                ) == BackgroundSessionDecision.PAUSE
            ) {
                span.finish(DiagnosticOutcome.SUCCESS)
                return Result.success()
            }
            app.graph.accountManager.restore()
            when (backgroundSessionDecision(
                state = app.graph.accountManager.state.value,
                hasSavedSession = app.graph.accountManager.session.value != null,
            )) {
                BackgroundSessionDecision.RUN -> Unit
                BackgroundSessionDecision.RETRY -> {
                    span.finish(DiagnosticOutcome.FAILED)
                    return Result.retry()
                }
                BackgroundSessionDecision.PAUSE -> {
                    span.finish(DiagnosticOutcome.SUCCESS)
                    return Result.success()
                }
            }
            val summary = app.graph.maintenance.wake(dev.mela.engine.model.WakeReason.BACKGROUND)
            span.finish(
                DiagnosticOutcome.SUCCESS,
                mapOf(
                    DiagnosticAttribute.RECOVERED_COUNT to summary.recoveredInvocations,
                    DiagnosticAttribute.RECONCILED_COUNT to summary.reconciledTransfers,
                    DiagnosticAttribute.STAGED_COUNT to summary.stagedAutomaticTransfers,
                    DiagnosticAttribute.INVOKED_COUNT to summary.invokedTransfers,
                    DiagnosticAttribute.MORE_WORK to summary.moreWorkScheduled,
                ),
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            span.finish(DiagnosticOutcome.CANCELLED)
            throw cancelled
        } catch (error: Exception) {
            span.finish(DiagnosticOutcome.FAILED, error = error)
            // Retry the durable state machine, never replay an uncertain HTTP upload.
            if (backgroundSessionDecision(
                    state = app.graph.accountManager.state.value,
                    hasSavedSession = app.graph.accountManager.session.value != null,
                ) == BackgroundSessionDecision.PAUSE
            ) Result.success() else Result.retry()
        }
    }
}

internal enum class BackgroundSessionDecision { RUN, RETRY, PAUSE }

internal fun backgroundSessionDecision(
    state: ICloudAccountState,
    hasSavedSession: Boolean,
): BackgroundSessionDecision = when {
    !hasSavedSession -> BackgroundSessionDecision.RUN
    state is ICloudAccountState.SignedIn && state.status == SessionStatus.VERIFIED ->
        BackgroundSessionDecision.RUN
    state is ICloudAccountState.SignedIn && state.status == SessionStatus.EXPIRED ->
        BackgroundSessionDecision.PAUSE
    state is ICloudAccountState.SignedIn && state.status == SessionStatus.PHOTOS_NOT_ENABLED ->
        BackgroundSessionDecision.PAUSE
    else -> BackgroundSessionDecision.RETRY
}

class ReconciliationTimerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WorkManagerWakeupScheduler(applicationContext).scheduleSoon()
        return Result.success()
    }
}
