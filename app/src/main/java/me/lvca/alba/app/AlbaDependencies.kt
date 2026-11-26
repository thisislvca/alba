package me.lvca.alba.app

import kotlinx.coroutines.flow.Flow
import me.lvca.alba.app.account.AccountPhotoStore
import me.lvca.alba.app.demo.DemoMediaImporter
import me.lvca.alba.app.di.AlbaGraph
import me.lvca.alba.app.diagnostics.AlbaDiagnostics
import me.lvca.alba.app.platform.BackupPreferences
import me.lvca.alba.app.platform.GalleryExporter
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.platform.PhotoEditStore
import me.lvca.alba.app.platform.StorageLimitPreferences
import me.lvca.alba.app.sharing.SharedActivityStore
import me.lvca.alba.app.transfers.TransferScheduler
import me.lvca.alba.app.work.CatalogSyncScheduler
import me.lvca.alba.app.work.OfflinePhotosScheduler
import me.lvca.alba.engine.companion.GalleryBatchActions
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.MaintenanceWakeup
import me.lvca.alba.engine.model.PhotoCompanion
import me.lvca.alba.protocol.account.ICloudAccountManager

/**
 * Everything [AlbaViewModel] needs, all required. Production code builds it from [AlbaGraph]; a test replaces single
 * collaborators with `copy`.
 */
data class AlbaDependencies(
    val repository: GalleryRepository,
    val accountManager: ICloudAccountManager,
    val photos: PhotoCompanion,
    val maintenance: MaintenanceWakeup,
    val batches: GalleryBatchActions,
    val transferScheduler: TransferScheduler,
    val exporter: GalleryExporter,
    val photoEdits: PhotoEditStore,
    val demoImporter: DemoMediaImporter,
    val network: Flow<NetworkStatus?>,
    /** Whether the phone is charging; null while unknown. */
    val charging: Flow<Boolean?>,
    val diagnostics: AlbaDiagnostics,
    val sharedActivity: SharedActivityStore,
    val backupPreferences: BackupPreferences,
    val storageLimits: StorageLimitPreferences,
    val catalogSync: CatalogSyncScheduler,
    val offlinePhotos: OfflinePhotosScheduler,
    /** Stops a background fill of the offline set in progress and waits for it to end. */
    val stopOfflineFill: suspend () -> Unit,
    /** The account's photo and name, saved so its avatar shows from the first frame. */
    val accountPhotos: AccountPhotoStore,
) {
    companion object {
        fun from(graph: AlbaGraph) = AlbaDependencies(
            repository = graph.galleryRepository,
            accountManager = graph.accountManager,
            photos = graph.photos,
            maintenance = graph.maintenance,
            batches = graph.batches,
            transferScheduler = graph.transferScheduler,
            exporter = graph.exporter,
            photoEdits = graph.photoEdits,
            demoImporter = graph.demoImporter,
            network = graph.network,
            charging = graph.charging,
            diagnostics = graph.diagnostics,
            sharedActivity = graph.sharedActivity,
            backupPreferences = graph.backupPreferences,
            storageLimits = graph.storageLimits,
            catalogSync = graph.catalogSync,
            offlinePhotos = graph.offlinePhotos,
            stopOfflineFill = graph.offlineFill::stopAndJoin,
            accountPhotos = graph.accountPhotos,
        )
    }
}

/** Whether this build can send diagnostics, and whether the user allows it. */
data class DiagnosticsConsent(val available: Boolean = false, val enabled: Boolean = false)
