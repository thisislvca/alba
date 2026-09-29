package dev.mela.engine.model

import android.content.IntentSender
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow

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
)

enum class BackupScope {
    CAMERA_JPEGS,
}

data class BackupDraft(
    val id: BackupDraftId,
    val destinationLabel: String,
    val disclosure: String,
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
)

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

    suspend fun prepareTrash(items: Set<String>): ReclaimProposal

    suspend fun beginTrash(proposal: ReclaimProposalId): TrashHandoff

    suspend fun recordTrashResult(attempt: TrashAttemptId, result: SystemConsentCallback)

    suspend fun accountWillChange(): AccountChangeResult
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

fun interface AutomaticBackupPolicy {
    fun isUnmetered(): Boolean
}

interface WakeupScheduler {
    suspend fun scheduleAt(notBeforeEpochMillis: Long) = scheduleSoon()

    suspend fun scheduleSoon()

    suspend fun setAutomaticBackupEnabled(enabled: Boolean)
}
