package me.lvca.alba.app.transfers

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import me.lvca.alba.engine.companion.BatchAction
import me.lvca.alba.engine.companion.GalleryBatchActions
import me.lvca.alba.engine.model.BackupChange
import me.lvca.alba.engine.model.BackupDraft
import me.lvca.alba.engine.model.BackupDraftId
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.BackupSetupEffect
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.engine.model.PhotoCompanion
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.app.R
import me.lvca.alba.app.messages.UserMessages
import me.lvca.alba.app.messages.localizedStoredMessage

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
