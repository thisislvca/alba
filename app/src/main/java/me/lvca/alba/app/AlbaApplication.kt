package me.lvca.alba.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import me.lvca.alba.engine.cache.MediaFileCache
import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.companion.DefaultPhotoCompanion
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.model.AutomaticBackupPolicy
import me.lvca.alba.engine.model.MaintenanceWakeup
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.WakeupScheduler
import me.lvca.alba.engine.repository.DefaultGalleryRepository
import me.lvca.alba.engine.source.AndroidMediaStoreSource
import me.lvca.alba.protocol.account.AndroidEncryptedSessionStore
import me.lvca.alba.protocol.account.ICloudAccountManager
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import me.lvca.alba.protocol.auth.AppleAuthenticationService
import me.lvca.alba.protocol.fixture.FixtureICloudCatalogSource
import me.lvca.alba.protocol.network.OkHttpAppleTransport
import me.lvca.alba.protocol.photos.AccountAwareICloudCatalogSource
import me.lvca.alba.protocol.photos.LiveICloudCatalogSource
import me.lvca.alba.protocol.photos.LiveICloudPhotoProtocol
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

class AlbaApplication : Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
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

class AlbaGraph(context: Context, val diagnostics: SentryAlbaDiagnostics) {
    private val appContext = context.applicationContext
    val database = AlbaDatabase.create(appContext)
    private val uploadJournal = me.lvca.alba.protocol.photos.EncryptedUploadJournal(
        me.lvca.alba.engine.database.RoomUploadCheckpointStore(database),
    )
    private val transport = OkHttpAppleTransport()
    private val authentication = AppleAuthenticationService(transport, telemetry = diagnostics)
    val accountManager = ICloudAccountManager(
        authentication = authentication,
        sessionStore = AndroidEncryptedSessionStore(appContext),
        telemetry = diagnostics,
        demoMode = OnboardingPreferences(appContext),
        onLocalSignOut = ::clearSignedOutData,
    )
    private val mediaStore = AndroidMediaStoreSource(appContext)
    private val liveCatalog = LiveICloudCatalogSource(
        transport = transport,
        isAuthorized = { accountManager.authorizedSession != null },
        sessionProvider = { accountManager.session.value },
        onSessionUpdated = accountManager::updateSession,
        onSessionRejected = accountManager::rejectSession,
        sharedJournal = uploadJournal,
    )
    val demoCatalog = FixtureICloudCatalogSource(
        includeSharedAlbums = true,
        openPhoto = { index -> appContext.assets.open(if (index in setOf(19, 20)) "demo/ocean.webp" else "demo/${index.toString().padStart(2, '0')}.webp") },
        openVideo = { appContext.assets.open("fixture_video.mp4") },
        uploadDirectory = java.io.File(appContext.cacheDir, "demo-imports").apply { deleteRecursively(); mkdirs() },
    )
    private val cloudCatalog = AccountAwareICloudCatalogSource(demoCatalog, liveCatalog, { accountManager.state.value == me.lvca.alba.protocol.account.ICloudAccountState.Demo }) { accountManager.session.value }
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
            uploadJournal = uploadJournal,
        ),
        scheduler = WorkManagerWakeupScheduler(appContext),
        automaticBackupPolicy = AndroidAutomaticBackupPolicy(appContext),
    )
    val maintenance: MaintenanceWakeup = photos
    val batches = me.lvca.alba.engine.companion.GalleryBatchActions(database.libraryDao(), galleryRepository, photos,
        saveToPhone = { id ->
            val media = galleryRepository.findMedia(id)
                ?: error(StoredMessage.THE_PHOTO_IS_NO_LONGER_AVAILABLE.code)
            GalleryExporter(appContext, galleryRepository).saveToGallery(media)
        }) { cloudCatalog.accountLabel }
    val exporter = GalleryExporter(appContext, galleryRepository)
    val photoEdits = PhotoEditStore(appContext, galleryRepository)
    val demoImporter = DemoMediaImporter(appContext, accountManager, demoCatalog)
    val transferScheduler = TransferScheduler { UserTransferScheduler(appContext).enqueue() }
    val network = networkStatus(appContext)
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
        // Named before the Alba rename and stored on devices; keep it.
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
        // Named before the Alba rename and stored on devices; keep it.
        const val IMMEDIATE_WORK_NAME = "mela-photo-maintenance"
        const val PERIODIC_WORK_NAME = "mela-automatic-photo-backup"
    }
}

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

class BackupWakeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withTimeoutOrNull(8 * 60_000L) { runPass() } ?: Result.retry()

    private suspend fun runPass(): Result {
        val app = applicationContext as AlbaApplication
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
            val summary = app.graph.maintenance.wake(me.lvca.alba.engine.model.WakeReason.BACKGROUND)
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
