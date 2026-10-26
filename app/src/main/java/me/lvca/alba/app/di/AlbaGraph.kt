package me.lvca.alba.app.di

import android.content.Context
import me.lvca.alba.app.demo.DemoMediaImporter
import me.lvca.alba.app.diagnostics.SentryAlbaDiagnostics
import me.lvca.alba.app.platform.AndroidAutomaticBackupPolicy
import me.lvca.alba.app.platform.GalleryExporter
import me.lvca.alba.app.platform.OnboardingPreferences
import me.lvca.alba.app.platform.PhotoEditStore
import me.lvca.alba.app.platform.networkStatus
import me.lvca.alba.app.sharing.SharedActivityStore
import me.lvca.alba.app.transfers.TransferScheduler
import me.lvca.alba.app.work.SharedActivitySchedule
import me.lvca.alba.app.work.UserTransferScheduler
import me.lvca.alba.app.work.WorkManagerWakeupScheduler
import me.lvca.alba.engine.cache.MediaFileCache
import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.companion.DefaultPhotoCompanion
import me.lvca.alba.engine.companion.GalleryBatchActions
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.RoomUploadCheckpointStore
import me.lvca.alba.engine.model.MaintenanceWakeup
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.repository.DefaultGalleryRepository
import me.lvca.alba.engine.source.AndroidMediaStoreSource
import me.lvca.alba.protocol.account.AndroidEncryptedSessionStore
import me.lvca.alba.protocol.account.ICloudAccountManager
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.auth.AppleAuthenticationService
import me.lvca.alba.protocol.fixture.FixtureICloudCatalogSource
import me.lvca.alba.protocol.network.OkHttpAppleTransport
import me.lvca.alba.protocol.photos.AccountAwareICloudCatalogSource
import me.lvca.alba.protocol.photos.EncryptedUploadJournal
import me.lvca.alba.protocol.photos.LiveICloudCatalogSource
import me.lvca.alba.protocol.photos.LiveICloudPhotoProtocol
import java.io.File

class AlbaGraph(context: Context, val diagnostics: SentryAlbaDiagnostics) {
    private val appContext = context.applicationContext
    val database = AlbaDatabase.create(appContext)
    private val uploadJournal = EncryptedUploadJournal(
        RoomUploadCheckpointStore(database),
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
        uploadDirectory = File(appContext.cacheDir, "demo-imports").apply { deleteRecursively(); mkdirs() },
    )
    private val cloudCatalog = AccountAwareICloudCatalogSource(demoCatalog, liveCatalog, { accountManager.state.value == ICloudAccountState.Demo }) { accountManager.session.value }
    val galleryRepository = DefaultGalleryRepository(
        database = database,
        cloudSource = cloudCatalog,
        deviceSource = mediaStore,
        cache = MediaFileCache(appContext),
    )
    val exporter = GalleryExporter(appContext, galleryRepository)
    val sharedActivity = SharedActivityStore(appContext)
    fun scheduleSharedActivity(enabled: Boolean) = SharedActivitySchedule.apply(appContext, enabled)
    private suspend fun clearSignedOutData() {
        sharedActivity.clear()
        batches.cancelActive()
        galleryRepository.clearCloudCatalog()
        exporter.clearShares()
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
    val batches = GalleryBatchActions(database.libraryDao(), galleryRepository, photos,
        saveToPhone = { id ->
            val media = galleryRepository.findMedia(id)
                ?: error(StoredMessage.THE_PHOTO_IS_NO_LONGER_AVAILABLE.code)
            exporter.saveToGallery(media)
        }) { cloudCatalog.accountLabel }
    val photoEdits = PhotoEditStore(appContext, galleryRepository)
    val demoImporter = DemoMediaImporter(appContext, accountManager, demoCatalog)
    val transferScheduler = TransferScheduler { UserTransferScheduler(appContext).enqueue() }
    val network = networkStatus(appContext)
}
