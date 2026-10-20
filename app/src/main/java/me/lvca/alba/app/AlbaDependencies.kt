package me.lvca.alba.app

import kotlinx.coroutines.flow.Flow
import me.lvca.alba.engine.companion.GalleryBatchActions
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.MaintenanceWakeup
import me.lvca.alba.engine.model.PhotoCompanion
import me.lvca.alba.protocol.account.ICloudAccountManager
import me.lvca.alba.app.demo.DemoMediaImporter
import me.lvca.alba.app.di.AlbaGraph
import me.lvca.alba.app.diagnostics.AlbaDiagnostics
import me.lvca.alba.app.platform.GalleryExporter
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.platform.PhotoEditStore
import me.lvca.alba.app.transfers.TransferScheduler

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
    val diagnostics: AlbaDiagnostics,
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
            diagnostics = graph.diagnostics,
        )
    }
}

/** Whether this build can send diagnostics, and whether the user allows it. */
data class DiagnosticsConsent(val available: Boolean = false, val enabled: Boolean = false)
