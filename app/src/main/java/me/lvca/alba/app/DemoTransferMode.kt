package me.lvca.alba.app

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import me.lvca.alba.engine.model.BackupDraft
import me.lvca.alba.engine.model.BackupDraftId
import me.lvca.alba.engine.model.BackupSetupEffect
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.protocol.account.ICloudAccountManager
import me.lvca.alba.protocol.account.ICloudAccountState

/**
 * The demo walkthrough: "uploads" copy media into the demo library and backup animates sample rows. Nothing reaches
 * Apple, the phone's photos, or the durable queue.
 */
internal class DemoTransferMode(
    scope: CoroutineScope,
    private val accountManager: ICloudAccountManager,
    private val repository: GalleryRepository,
    private val importer: DemoMediaImporter,
    private val messages: UserMessages,
    private val catalogMedia: (String) -> GalleryMedia?,
) : TransferMode {
    private val demo = DemoBackup(scope)
    override val transfers: Flow<List<TransferView>> = demo.transfers
    override val backup: Flow<BackupView> = demo.backup

    override suspend fun uploadPicked(uris: List<Uri>) = import(uris)

    override suspend fun uploadGalleryMedia(mediaIds: List<String>) =
        import(mediaIds.mapNotNull { id -> catalogMedia(id)?.originalReference?.let(Uri::parse) })

    /** Imports the bundled sample photo. */
    suspend fun importSample() = import(listOf(null))

    private suspend fun import(uris: List<Uri?>) {
        var importedAny = false
        try {
            uris.forEach { importer.import(it); importedAny = true }
        } finally {
            // Publish successful copies even when a later selection is inaccessible or unsupported.
            if (importedAny && isDemo()) repository.refresh(includeDeviceMedia = false)
        }
        if (importedAny && isDemo()) messages.show(R.string.demo_import_complete)
    }

    override suspend fun beginBackup(): BackupDraft = demo.begin()

    override suspend fun acceptBackup(draftId: BackupDraftId): BackupSetupEffect =
        if (isDemo() && demo.accept(draftId)) BackupSetupEffect.RequestMediaPermissions(emptyList())
        else BackupSetupEffect.Refused(StoredMessage.AUTOMATIC_BACKUP_IS_OFF.code)

    override suspend fun finishBackup(draftId: BackupDraftId) {
        if (isDemo()) demo.finish(draftId)
    }

    override suspend fun disableBackup() = reset()

    /** The demo's backup screen drives its scenarios through reserved transfer ids. */
    override suspend fun continuePastUnresolved(transferId: TransferId) {
        when (transferId.value) {
            CONTROL_INTERRUPT -> demo.interrupt()
            CONTROL_WIFI -> demo.pauseForWifi()
            CONTROL_CLEANUP -> demo.cleanUpSamples()
            else -> demo.retry(transferId)
        }
    }

    fun reset() = demo.reset()

    private fun isDemo() = accountManager.state.value == ICloudAccountState.Demo

    companion object {
        const val CONTROL_INTERRUPT = "demo-control:interrupt"
        const val CONTROL_WIFI = "demo-control:wifi"
        const val CONTROL_CLEANUP = "demo-control:cleanup"

        /** Drafts the demo created; the live companion never sees them. */
        fun owns(draftId: BackupDraftId) = DemoBackup.owns(draftId)
    }
}
