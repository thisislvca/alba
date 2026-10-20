package me.lvca.alba.app

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import me.lvca.alba.engine.companion.BatchAction
import me.lvca.alba.engine.companion.GalleryBatchActions
import me.lvca.alba.engine.model.BackupChange
import me.lvca.alba.engine.model.BackupDraft
import me.lvca.alba.engine.model.BackupDraftId
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.BackupSetupEffect
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.PhotoCompanion
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.protocol.account.ICloudAccountManager
import me.lvca.alba.protocol.account.ICloudAccountState

/** Starts the platform job that runs queued transfers. */
fun interface TransferScheduler {
    fun schedule()
}

/**
 * How uploads and automatic backup behave for the open library: the real, durable queue for an iCloud account, or the
 * in-memory walkthrough for the demo library. Callers pick the mode once per action and never branch on the account.
 */
internal interface TransferMode {
    val transfers: Flow<List<TransferView>>
    val backup: Flow<BackupView>

    /** Uploads phone media the user picked or shared to Alba. */
    suspend fun uploadPicked(uris: List<Uri>)

    /** Uploads phone photos that the gallery already shows. */
    suspend fun uploadGalleryMedia(mediaIds: List<String>)

    suspend fun beginBackup(): BackupDraft
    suspend fun acceptBackup(draftId: BackupDraftId): BackupSetupEffect
    suspend fun finishBackup(draftId: BackupDraftId)
    suspend fun disableBackup()
    suspend fun continuePastUnresolved(transferId: TransferId)
}

/** The durable queue: uploads and backup go to the user's iCloud library through the photo companion. */
internal class LiveTransferMode(
    private val photos: PhotoCompanion,
    private val batches: GalleryBatchActions,
    private val scheduler: TransferScheduler,
    private val messages: UserMessages,
) : TransferMode {
    override val transfers: Flow<List<TransferView>> = photos.observeTransfers()
    override val backup: Flow<BackupView> = photos.observeBackup()

    override suspend fun uploadPicked(uris: List<Uri>) {
        batches.enqueue(BatchAction.PICKER_UPLOAD, uris.map(Uri::toString))
        scheduler.schedule()
    }

    override suspend fun uploadGalleryMedia(mediaIds: List<String>) {
        batches.enqueue(BatchAction.UPLOAD, mediaIds)
        scheduler.schedule()
    }

    override suspend fun beginBackup(): BackupDraft = photos.beginBackupEnrollment(BackupScope.CAMERA_JPEGS)

    override suspend fun acceptBackup(draftId: BackupDraftId): BackupSetupEffect =
        photos.acceptBackupDisclosure(draftId).also { effect ->
            if (effect is BackupSetupEffect.Refused) messages.show(localizedStoredMessage(effect.reason))
        }

    override suspend fun finishBackup(draftId: BackupDraftId) {
        messages.show(localizedStoredMessage(photos.finishBackupEnrollment(draftId).message))
    }

    override suspend fun disableBackup() {
        messages.show(localizedStoredMessage(photos.changeBackup(BackupChange.Disable).message))
    }

    override suspend fun continuePastUnresolved(transferId: TransferId) {
        photos.continuePastUnresolved(transferId)
        messages.show(R.string.message_that_uncertain_item_stays_blocked_alba_can_continue_with_other_photos)
        scheduler.schedule()
    }
}

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
