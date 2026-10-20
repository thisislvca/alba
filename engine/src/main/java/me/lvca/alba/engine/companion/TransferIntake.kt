package me.lvca.alba.engine.companion

import androidx.room.withTransaction
import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.BackupEnrollmentEntity
import me.lvca.alba.engine.database.LocalAssetEntity
import me.lvca.alba.engine.database.MediaStoreCheckpointEntity
import me.lvca.alba.engine.database.StagedSourceEntity
import me.lvca.alba.engine.database.TransferEntity
import me.lvca.alba.engine.database.TransferState
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.UploadTicket
import me.lvca.alba.engine.model.UserSelectedPhoto
import me.lvca.alba.engine.model.WakeupScheduler
import me.lvca.alba.engine.source.ExactOriginalEvidence
import me.lvca.alba.engine.source.ExactOriginalSource
import me.lvca.alba.engine.source.LocalDiscoveryCheckpoint
import me.lvca.alba.engine.source.mediaStoreRevision
import java.io.OutputStream
import java.util.UUID

/** Stages exact originals privately and queues them as READY transfers bound to the active account. */
internal class TransferIntake(
    private val database: AlbaDatabase,
    private val originals: ExactOriginalSource,
    private val staging: UploadStagingStore,
    private val scheduler: WakeupScheduler,
    private val accounts: AccountBindingSync,
    private val authority: AutomaticBackupAuthority,
    private val now: () -> Long,
) {
    private val accountDao = database.accountDao()
    private val backupDao = database.backupDao()
    private val localAssetDao = database.localAssetDao()
    private val transferDao = database.transferDao()

    suspend fun uploadSelected(photo: UserSelectedPhoto): UploadTicket {
        val binding = accounts.requireActive()
        return createTransfer(
            binding = binding,
            authorityType = AUTHORITY_MANUAL,
            authorityId = "selection:${UUID.randomUUID()}",
            enrollment = null,
            mediaId = null,
            contentUri = photo.contentUri,
        ) { output ->
            originals.copySelected(photo.contentUri, photo.displayName, output)
        }
    }

    suspend fun uploadDeviceMedia(mediaId: String): UploadTicket {
        val item = refuseIfNull(database.catalogDao().findById(mediaId), StoredMessage.UNKNOWN_DEVICE_PHOTO)
        refuseUnless(item.origin == "DEVICE", StoredMessage.ONLY_PHOTOS_ON_THIS_PHONE_CAN_BE_UPLOADED)
        val contentUri = refuseIfNull(item.localUri, StoredMessage.THIS_PHOTO_IS_NO_LONGER_AVAILABLE_ON_THE_PHONE)
        val binding = accounts.requireActive()
        return createTransfer(
            binding = binding,
            authorityType = AUTHORITY_MANUAL,
            authorityId = "device-selection:${UUID.randomUUID()}",
            enrollment = null,
            mediaId = mediaId,
            contentUri = contentUri,
        ) { output ->
            originals.copyMediaStoreItem(contentUri, item.sourceRevision, output)
        }
    }

    /** Stages at most one newly discovered photo for automatic backup. Returns how many transfers it queued. */
    suspend fun discoverAndStageAutomatic(binding: AccountBinding): Int {
        if (!authority.isUnmetered()) return 0
        val enrollment = authority.currentEnrollment(binding) ?: return 0
        val permissionFingerprint = enrollment.permissionFingerprint

        val checkpointId = "${binding.accountId}:${enrollment.enrollmentId}:${enrollment.revision}:external"
        val checkpoint = localAssetDao.findMediaStoreCheckpoint(checkpointId)
            ?.takeIf { it.permissionFingerprint == permissionFingerprint }
            ?.let {
            LocalDiscoveryCheckpoint(
                it.volumeName, it.volumeVersion, it.generationModified, it.mediaStoreId,
            )
        }
        val page = originals.discoverEligibleJpegs(
            checkpoint, AUTO_DISCOVERY_PAGE_SIZE, BackupScope.valueOf(enrollment.scope), enrollment.consentedAtEpochMillis,
            enrollment.discoveryBaselineVersion?.let { version ->
                enrollment.discoveryBaselineGeneration?.let { generation ->
                    LocalDiscoveryCheckpoint("external", version, generation, Long.MAX_VALUE)
                }
            },
        )
        val scannedAt = now()
        database.withTransaction {
            val current = requireNotNull(backupDao.findBackupEnrollment(binding.accountId))
            require(current == enrollment) { "Backup enrollment changed during discovery" }
            localAssetDao.upsertLocalAssets(page.items.map { item ->
                LocalAssetEntity(
                    localAssetId = item.id,
                    volumeName = item.volumeName,
                    volumeVersion = item.volumeVersion,
                    mediaStoreId = item.mediaStoreId,
                    generationModified = item.generationModified,
                    contentUri = item.contentUri,
                    lineageKey = "${item.volumeName}:${item.mediaStoreId}",
                    displayName = item.fileName,
                    mimeType = item.mimeType,
                    byteCount = item.byteCount,
                    capturedAtEpochMillis = item.capturedAtEpochMillis,
                    permissionFingerprint = permissionFingerprint,
                    lastSeenAtEpochMillis = scannedAt,
                )
            })
        }
        suspend fun saveCheckpoint() {
            localAssetDao.upsertMediaStoreCheckpoint(
                MediaStoreCheckpointEntity(
                    checkpointId, page.checkpoint.volumeName, page.checkpoint.volumeVersion,
                    permissionFingerprint, page.checkpoint.generationModified, page.checkpoint.mediaStoreId, scannedAt,
                ),
            )
        }
        if (page.moreComing) runSuspendCatching { scheduler.scheduleSoon() }
        // Cached rows can belong to an older enrollment; use only the scoped page.
        val item = page.items.firstOrNull()
        if (item == null) {
            saveCheckpoint()
            return 0
        }
        val local = requireNotNull(localAssetDao.findLocalAsset(item.id))
        if (transferDao.findTransferForMedia(binding.accountId, local.localAssetId) != null) {
            saveCheckpoint()
            return 0
        }
        createTransfer(
            binding = binding,
            authorityType = AUTHORITY_AUTOMATIC,
            authorityId = "enrollment:${enrollment.enrollmentId}:${enrollment.revision}",
            enrollment = enrollment,
            mediaId = local.localAssetId,
            contentUri = local.contentUri,
        ) { output ->
            originals.copyMediaStoreItem(
                local.contentUri,
                mediaStoreRevision(local.volumeVersion, local.volumeName, local.mediaStoreId, local.generationModified, local.byteCount),
                output,
            )
        }
        // Failed staging must not advance past the photo.
        saveCheckpoint()
        return 1
    }

    private suspend fun createTransfer(
        binding: AccountBinding,
        authorityType: String,
        authorityId: String,
        enrollment: BackupEnrollmentEntity?,
        mediaId: String?,
        contentUri: String,
        copy: suspend (OutputStream) -> ExactOriginalEvidence,
    ): UploadTicket {
        val staged = staging.stageExact(copy)
        val sourceId = UUID.randomUUID().toString()
        val transferId = UUID.randomUUID().toString()
        val createdAt = now()
        try {
            database.withTransaction {
                val active = refuseIfNull(accountDao.findActiveAccount(), StoredMessage.SIGN_IN_TO_ICLOUD_BEFORE_UPLOADING)
                refuseUnless(active.toBinding() == binding, StoredMessage.THE_ICLOUD_ACCOUNT_CHANGED_WHILE_STAGING_THIS_PHOTO)
                if (enrollment != null) {
                    refuseUnless(
                        authority.stillAuthorizes(enrollment.enrollmentId, enrollment.revision, binding),
                        StoredMessage.AUTOMATIC_BACKUP_PERMISSION_OR_CONSENT_CHANGED,
                    )
                }
                refuseUnless(
                    transferDao.findExistingTransferForBytes(binding.accountId, staged.sha256Hex) == null,
                    StoredMessage.THESE_EXACT_BYTES_ARE_ALREADY_QUEUED_OR_VERIFIED_IN_ICLOUD,
                )
                refuseUnless(
                    transferDao.countActiveSuppressions(
                        binding.accountId,
                        staged.evidence.lineageKey,
                        staged.sha256Hex,
                    ) == 0,
                    StoredMessage.A_PREVIOUS_UPLOAD_OF_THESE_BYTES_IS_UNRESOLVED_AND_WILL_NOT_BE_REPEATED,
                )
                transferDao.insertStagedSource(
                    StagedSourceEntity(
                        stagedSourceId = sourceId,
                        accountId = binding.accountId,
                        authEpoch = binding.authEpoch,
                        operationFence = binding.operationFence,
                        authorityType = authorityType,
                        authorityId = authorityId,
                        enrollmentId = enrollment?.enrollmentId,
                        enrollmentRevision = enrollment?.revision,
                        mediaId = mediaId,
                        contentUri = contentUri,
                        displayName = staged.evidence.displayName,
                        mimeType = staged.evidence.mimeType,
                        lineageKey = staged.evidence.lineageKey,
                        sourceRevision = staged.evidence.sourceRevision,
                        originalAcquisition = staged.evidence.acquisitionMethod,
                        originalFormatRequested = staged.evidence.originalFormatRequested,
                        unredacted = staged.evidence.unredacted,
                        formatValidated = staged.evidence.formatValidated,
                        lastModifiedAtEpochMillis = staged.evidence.lastModifiedAtEpochMillis,
                        ownedFileToken = staged.ownedFileToken,
                        byteCount = staged.byteCount,
                        sha256Hex = staged.sha256Hex,
                        createdAtEpochMillis = createdAt,
                    ),
                )
                transferDao.insertTransfer(
                    TransferEntity(
                        transferId = transferId,
                        stagedSourceId = sourceId,
                        accountId = binding.accountId,
                        authEpoch = binding.authEpoch,
                        operationFence = binding.operationFence,
                        authorityType = authorityType,
                        authorityId = authorityId,
                        enrollmentId = enrollment?.enrollmentId,
                        enrollmentRevision = enrollment?.revision,
                        mediaId = mediaId,
                        displayName = staged.evidence.displayName,
                        state = TransferState.READY,
                        stateVersion = 0,
                        activeAttemptId = null,
                        leaseToken = null,
                        leaseExpiresAtEpochMillis = null,
                        reconciliationDueAtEpochMillis = null,
                        reconciliationDeadlineEpochMillis = null,
                        reconciliationChangeToken = null,
                        verifiedAtEpochMillis = null,
                        createdAtEpochMillis = createdAt,
                        updatedAtEpochMillis = createdAt,
                        message = StoredMessage.WAITING_TO_UPLOAD.code,
                    ),
                )
            }
        } catch (error: Throwable) {
            staging.deleteOwned(staged.ownedFileToken)
            throw error
        }
        if (authorityType == AUTHORITY_AUTOMATIC) runSuspendCatching { scheduler.scheduleSoon() }
        return UploadTicket(TransferId(transferId))
    }

    private companion object {
        const val AUTO_DISCOVERY_PAGE_SIZE = 1
    }
}
