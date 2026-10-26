package me.lvca.alba.engine.model

import android.content.IntentSender
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.io.OutputStream

@JvmInline
value class TransferId(val value: String)

@JvmInline
value class BackupDraftId(val value: String)

@JvmInline
value class ReclaimProposalId(val value: String)

@JvmInline
value class TrashAttemptId(val value: String)

data class UserSelectedPhoto(
    val contentUri: String,
    val displayName: String? = null,
)

data class UploadTicket(val id: TransferId)

enum class TransferViewState {
    WAITING,
    UPLOADING,
    CHECKING_ICLOUD,
    VERIFIED,
    UNRESOLVED,
    SKIPPED,
    NEEDS_SIGN_IN,
    NEEDS_ATTENTION,
    UNSUPPORTED,
}

data class TransferView(
    val id: TransferId,
    val mediaId: String?,
    val displayName: String,
    val state: TransferViewState,
    val byteCount: Long,
    val updatedAtEpochMillis: Long,
    val verifiedAtEpochMillis: Long? = null,
    val message: String? = null,
    /** Automatic backup found it, rather than the user choosing it. */
    val automatic: Boolean = false,
    /** Nothing was sent for it, so staging it again from the phone is safe. */
    val retryable: Boolean = false,
)

/** What automatic backup reads. Names are persisted with each enrollment; keep them stable. */
enum class BackupScope {
    /** Enrollments consented before backup covered videos and every accepted format. Kept as they were agreed. */
    CAMERA_JPEGS,

    /** Every photo and video format the uploader accepts, from the camera folder and the folders the user adds. */
    CAMERA_MEDIA,
    ;

    companion object {
        /** The scope a new enrollment asks the user to agree to. */
        val CURRENT = CAMERA_MEDIA
    }
}

/** A disclosure shown for [scope]. The UI words it; [revision] is what the user's consent is recorded against. */
data class BackupDraft(
    val id: BackupDraftId,
    val destinationLabel: String,
    val scope: BackupScope,
    val revision: String,
)

sealed interface BackupSetupEffect {
    data class RequestMediaPermissions(val permissions: List<String>) : BackupSetupEffect
    data class Refused(val reason: String) : BackupSetupEffect
}

data class BackupView(
    val enabled: Boolean,
    val destinationLabel: String? = null,
    val scope: BackupScope? = null,
    val message: String? = null,
    /** False when the user agreed to an older disclosure, so what backup covers now needs their consent again. */
    val disclosureCurrent: Boolean = true,
    /** When iCloud refused an upload for lack of storage; backup waits instead of failing item by item. */
    val storageFullSinceEpochMillis: Long? = null,
)

/** How much of what automatic backup covers is verified in iCloud. */
data class BackupProgress(
    val backedUp: Int,
    val eligible: Int,
)

/** Phone items with a current exact iCloud proof, which Android's trash could take off the phone. */
data class FreeUpSpaceCandidates(
    val mediaIds: List<String>,
    val byteCount: Long,
)

/** A folder on the phone that automatic backup can include. [id] is stable across scans. */
data class DeviceFolder(
    val id: String,
    val name: String,
    val relativePath: String,
    val itemCount: Int,
    val isCamera: Boolean,
)

/** Manual uploads finished and still to go in the current run. */
data class TransferProgress(val done: Int, val total: Int)

sealed interface BackupChange {
    data object Disable : BackupChange
}

@JvmInline
value class SyncTicket(val value: String)

data class ReclaimProposal(
    val id: ReclaimProposalId,
    val eligibleMediaIds: Set<String>,
    val expiresAtEpochMillis: Long,
    val blockedReason: String? = null,
)

data class TrashHandoff(
    val attemptId: TrashAttemptId,
    val intentSender: IntentSender,
)

enum class SystemConsentCallback {
    APPROVED,
    DENIED,
    CANCELED,
}

interface PhotoCompanion {
    fun observeTransfers(): Flow<List<TransferView>>

    fun observeBackup(): Flow<BackupView>

    suspend fun uploadSelected(photo: UserSelectedPhoto): UploadTicket

    suspend fun uploadDeviceMedia(mediaId: String): UploadTicket

    suspend fun beginBackupEnrollment(scope: BackupScope): BackupDraft

    suspend fun acceptBackupDisclosure(draft: BackupDraftId): BackupSetupEffect

    suspend fun finishBackupEnrollment(draft: BackupDraftId): BackupView

    suspend fun changeBackup(change: BackupChange): BackupView

    suspend fun syncNow(): SyncTicket

    /** Execute manual uploads from the user-visible platform transfer runner. */
    suspend fun runUserUploads()

    suspend fun hasPendingUserUploads(): Boolean

    /** Stop unsent uploads; retain uncertain attempts for read-only verification. */
    suspend fun cancelUserUploads()

    suspend fun continuePastUnresolved(transfer: TransferId)

    /**
     * Re-checks [items] against freshly read phone and iCloud bytes. Without [allowPartial] one failure blocks the whole
     * proposal; with it, items that fail are left out and only an empty result is blocked.
     */
    suspend fun prepareTrash(
        items: Set<String>,
        allowPartial: Boolean = false,
        onProgress: suspend (checked: Int, total: Int) -> Unit = { _, _ -> },
    ): ReclaimProposal

    /** Backup status, settings, retry and "Free up space". */
    val backup: BackupControls

    suspend fun beginTrash(proposal: ReclaimProposalId): TrashHandoff

    suspend fun recordTrashResult(attempt: TrashAttemptId, result: SystemConsentCallback)

    suspend fun accountWillChange(): AccountChangeResult
}

/** What the Backup page and the transfer runner need beyond uploading: status, settings, retry and free space. */
interface BackupControls {
    /** Phone items backed up with a current proof, for "Free up space". */
    suspend fun freeUpSpaceCandidates(): FreeUpSpaceCandidates

    /** Stages an item again whose earlier try sent nothing to Apple. Uncertain uploads are never retried here. */
    suspend fun retryTransfer(transfer: TransferId): UploadTicket

    /** Retries every item [retryTransfer] accepts. Returns how many it queued. */
    suspend fun retryAllTransfers(): Int

    /** How much of what backup covers is verified, counted from the phone and the journal. */
    suspend fun backupProgress(): BackupProgress

    /** Folders on the phone that hold photos or videos, for choosing what backup includes. */
    suspend fun deviceFolders(): List<DeviceFolder>

    /**
     * The user changed backup settings: re-plan background work for the networks they now allow. Pausing stops the
     * running pass; an upload it interrupts resumes later or goes to read-only verification, never to a blind resend.
     */
    suspend fun backupSettingsChanged()

    /** The user asked to try again after iCloud storage was full. */
    suspend fun retryAfterStorageFull()

    /** Manual uploads done and in total since [sinceEpochMillis], for the transfer notification. */
    suspend fun userUploadProgress(sinceEpochMillis: Long): TransferProgress
}

sealed interface AccountChangeResult {
    data object Allowed : AccountChangeResult
    data class Blocked(val reason: String) : AccountChangeResult
}

enum class WakeReason {
    PROCESS_STARTED,
    FOREGROUND,
    BACKGROUND,
    USER_REQUESTED,
    USER_TRANSFER,
}

data class WakeSummary(
    val recoveredInvocations: Int,
    val reconciledTransfers: Int,
    val stagedAutomaticTransfers: Int,
    val invokedTransfers: Int,
    val moreWorkScheduled: Boolean,
)

interface MaintenanceWakeup {
    suspend fun wake(reason: WakeReason): WakeSummary
}

data class ICloudDestination(
    val accountId: String,
    val authSessionId: String,
    val label: String,
)

data class AccountBinding(
    val accountId: String,
    val authEpoch: Long,
    val operationFence: Long,
)

data class UploadAcceptance(
    val requestUuid: String?,
    val masterRecordName: String?,
    val assetRecordName: String?,
    val duplicateHint: Boolean?,
    val uploadJobId: String? = null,
)

sealed interface UploadProcessingStatus {
    data object Unknown : UploadProcessingStatus
    data class Processing(val progress: Int?) : UploadProcessingStatus
    data object Complete : UploadProcessingStatus
    data class Failed(val code: Int) : UploadProcessingStatus
}

data class RemoteRecordRef(
    val name: String,
    val changeTag: String,
)

data class RemotePairCandidate(
    val master: RemoteRecordRef,
    val asset: RemoteRecordRef,
    val relationIsCurrent: Boolean,
    val masterDeleted: Boolean,
    val assetDeleted: Boolean,
    val originalResourceIdentity: String,
)

data class CandidatePage(
    val candidates: List<RemotePairCandidate>,
    val nextChangeToken: String?,
    val moreComing: Boolean,
)

data class RemoteOriginalObservation(
    val resourceIdentity: String,
    val byteCount: Long,
)

interface OneShotUploadSource {
    val byteCount: Long

    val sha256Hex: String

    fun openOnce(): InputStream
}

/**
 * Apple refused to reserve an upload because the account's iCloud storage is full. It is raised only before any byte is
 * sent, so the attempt stays safely resumable; backup pauses instead of failing each item.
 */
class ICloudStorageFullException(message: String = "iCloud storage is full") : RuntimeException(message)

interface PreparedICloudUpload {
    suspend fun startOnce(attemptId: String): UploadAcceptance

    fun cancel()
}

interface ICloudPhotoProtocol {
    fun isWriteAuthorized(): Boolean = true

    suspend fun canResume(binding: AccountBinding, attemptId: String): Boolean = false
    fun currentDestination(): ICloudDestination?

    suspend fun captureChangeToken(binding: AccountBinding): String

    suspend fun prepareOriginal(
        binding: AccountBinding,
        fileName: String,
        source: OneShotUploadSource,
        lastModifiedAtEpochMillis: Long? = null,
    ): PreparedICloudUpload

    suspend fun uploadProcessingStatus(
        binding: AccountBinding,
        acceptance: UploadAcceptance,
    ): UploadProcessingStatus = UploadProcessingStatus.Unknown

    suspend fun findUploadCandidates(
        binding: AccountBinding,
        afterChangeToken: String,
        acceptance: UploadAcceptance?,
        limit: Int,
    ): CandidatePage

    suspend fun streamFreshOriginal(
        binding: AccountBinding,
        pair: RemotePairCandidate,
        output: OutputStream,
    ): RemoteOriginalObservation
}

/** The network automatic backup sees now. */
enum class BackupNetwork { OFFLINE, UNMETERED, METERED, ROAMING }

/** The user's backup settings. Everything that spends mobile data is off until they turn it on. */
data class BackupSettings(
    val photosOnMobileData: Boolean = false,
    val videosOnMobileData: Boolean = false,
    val whileRoaming: Boolean = false,
    /** Extra [DeviceFolder.id]s the user chose, beyond the camera folder. */
    val deviceFolders: Set<String> = emptySet(),
    /** The user paused backup; nothing automatic runs until they resume it. */
    val paused: Boolean = false,
) {
    /** Whether automatic backup may upload a photo, or a video when [video], on [network]. */
    fun allows(network: BackupNetwork, video: Boolean): Boolean = !paused && when (network) {
        BackupNetwork.UNMETERED -> true
        BackupNetwork.METERED -> if (video) videosOnMobileData else photosOnMobileData
        BackupNetwork.ROAMING -> whileRoaming && (if (video) videosOnMobileData else photosOnMobileData)
        BackupNetwork.OFFLINE -> false
    }

    /** Whether anything automatic may use the network on [network]. */
    fun allowsAny(network: BackupNetwork): Boolean = allows(network, video = false) || allows(network, video = true)

    /** Whether background work may need a metered network. */
    val usesMobileData: Boolean get() = photosOnMobileData || videosOnMobileData
}

fun interface AutomaticBackupPolicy {
    fun isUnmetered(): Boolean

    fun network(): BackupNetwork = if (isUnmetered()) BackupNetwork.UNMETERED else BackupNetwork.METERED

    fun settings(): BackupSettings = BackupSettings()
}

interface WakeupScheduler {
    suspend fun scheduleAt(notBeforeEpochMillis: Long) = scheduleSoon()

    suspend fun scheduleSoon()

    /** Plans periodic backup, or stops planning it. Called again whenever the networks backup may use change. */
    suspend fun setAutomaticBackupEnabled(enabled: Boolean)

    /** Stops a background backup pass that is running now. */
    suspend fun cancelRunningBackup() = Unit
}
