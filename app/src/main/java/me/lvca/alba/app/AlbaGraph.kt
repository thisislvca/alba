package me.lvca.alba.app

import android.content.Context
import me.lvca.alba.engine.cache.MediaFileCache
import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.companion.DefaultPhotoCompanion
import me.lvca.alba.engine.database.AlbaDatabase
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
import me.lvca.alba.protocol.photos.LiveICloudCatalogSource
import me.lvca.alba.protocol.photos.LiveICloudPhotoProtocol

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
