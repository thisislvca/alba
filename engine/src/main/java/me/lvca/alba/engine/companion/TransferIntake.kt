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
import me.lvca.alba.engine.model.BackupProgress
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.PhotoCompanionException
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.UploadTicket
import me.lvca.alba.engine.model.UserSelectedPhoto
import me.lvca.alba.engine.model.WakeupScheduler
import me.lvca.alba.engine.source.BackupFolders
import me.lvca.alba.engine.source.DeviceMediaRecord
import me.lvca.alba.engine.source.ExactOriginalEvidence
import me.lvca.alba.engine.source.ExactOriginalSource
import me.lvca.alba.engine.source.LocalDiscoveryCheckpoint
import me.lvca.alba.engine.source.UnsupportedUploadException
import me.lvca.alba.engine.source.UploadMediaFormat
import java.io.OutputStream
import java.security.MessageDigest
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

    /**
     * Stages at most one newly discovered item for automatic backup. Items already in the journal are passed over, and
     * items the uploader refuses are recorded as skipped with their reason, so none silently disappears and none
     * blocks the items after it. Returns how many transfers it queued.
     */
    suspend fun discoverAndStageAutomatic(binding: AccountBinding): Int {
        if (!authority.mayUseNetwork()) return 0
        val enrollment = authority.currentEnrollment(binding) ?: return 0
        val permissionFingerprint = enrollment.permissionFingerprint
        val scope = BackupScope.valueOf(enrollment.scope)
        val folders = BackupFolders.extraFolders(scope, authority.settings.deviceFolders)

        val checkpointId = discoveryCheckpointId(binding, enrollment, scope, folders)
        val checkpoint = localAssetDao.findMediaStoreCheckpoint(checkpointId)
            ?.takeIf { it.permissionFingerprint == permissionFingerprint }
            ?.let {
            LocalDiscoveryCheckpoint(
                it.volumeName, it.volumeVersion, it.generationModified, it.mediaStoreId,
            )
        }
        val page = originals.discoverEligibleMedia(
            checkpoint, AUTO_DISCOVERY_PAGE_SIZE, scope, folders, enrollment.consentedAtEpochMillis,
            enrollment.baseline(),
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
        suspend fun saveCheckpoint(at: LocalDiscoveryCheckpoint) {
            localAssetDao.upsertMediaStoreCheckpoint(
                MediaStoreCheckpointEntity(
                    checkpointId, at.volumeName, at.volumeVersion,
                    permissionFingerprint, at.generationModified, at.mediaStoreId, scannedAt,
                ),
            )
        }
        // Cached rows can belong to an older enrollment; use only the scoped page.
        for (item in page.items) {
            val passed = LocalDiscoveryCheckpoint(page.checkpoint.volumeName, page.checkpoint.volumeVersion,
                item.generationModified, item.mediaStoreId)
            if (transferDao.countTransfersForMedia(binding.accountId, item.id) > 0) {
                saveCheckpoint(passed)
                continue
            }
            if (!UploadMediaFormat.isCandidate(item.mimeType)) {
                recordSkipped(binding, enrollment, item, unsupportedReason(item.mimeType))
                saveCheckpoint(passed)
                continue
            }
            val queued = try {
                createTransfer(
                    binding = binding,
                    authorityType = AUTHORITY_AUTOMATIC,
                    authorityId = "enrollment:${enrollment.enrollmentId}:${enrollment.revision}",
                    enrollment = enrollment,
                    mediaId = item.id,
                    contentUri = item.contentUri,
                ) { output ->
                    originals.copyMediaStoreItem(item.contentUri, item.sourceRevision, output)
                }
                true
            } catch (refused: UnsupportedUploadException) {
                recordSkipped(binding, enrollment, item, refused.message ?: UnsupportedUploadException.MESSAGE)
                false
            } catch (refused: PhotoCompanionException.Refused) {
                // The same bytes are already queued or verified, or held back by an unresolved upload that shows itself.
                if (refused.reason !in PASSED_OVER) throw refused
                false
            }
            // Failed staging must not advance past the item; anything else above throws before this line.
            saveCheckpoint(passed)
            if (queued) {
                if (page.moreComing || item != page.items.last()) runSuspendCatching { scheduler.scheduleSoon() }
                return 1
            }
        }
        saveCheckpoint(page.checkpoint)
        if (page.moreComing) runSuspendCatching { scheduler.scheduleSoon() }
        return 0
    }

    /** Ids of everything automatic backup covers on the phone, and how many of them are verified in iCloud. */
    suspend fun backupProgress(binding: AccountBinding): BackupProgress {
        val enrollment = backupDao.findEnabledBackupEnrollment(binding.accountId) ?: return BackupProgress(0, 0)
        val scope = BackupScope.valueOf(enrollment.scope)
        val eligible = originals.eligibleMediaIds(
            scope, BackupFolders.extraFolders(scope, authority.settings.deviceFolders),
            enrollment.consentedAtEpochMillis, enrollment.baseline(),
        )
        val verified = transferDao.findVerifiedMediaIds(binding.accountId).toSet()
        return BackupProgress(backedUp = eligible.count { it in verified }, eligible = eligible.size)
    }

    /**
     * Stages again an item that stopped before any attempt was armed, so nothing of it reached Apple. It becomes the
     * user's own upload, which replaces the old row in the same transaction.
     */
    suspend fun retry(transferId: String): UploadTicket {
        val row = refuseIfNull(transferDao.findTransfer(transferId), StoredMessage.THIS_ITEM_IS_NO_LONGER_IN_THE_QUEUE)
        refuseUnless(
            row.state in RETRYABLE_STATES && row.activeAttemptId == null,
            StoredMessage.ONLY_ITEMS_THAT_SENT_NOTHING_CAN_BE_RETRIED,
        )
        val source = refuseIfNull(transferDao.findStagedSource(row.stagedSourceId), StoredMessage.THIS_ITEM_IS_NO_LONGER_IN_THE_QUEUE)
        val binding = accounts.requireActive()
        refuseUnless(
            row.accountId == binding.accountId,
            StoredMessage.THE_ICLOUD_ACCOUNT_CHANGED_BEFORE_UPLOAD_SELECT_THIS_PHOTO_AGAIN_FOR_THE_NEW_ACCOUNT,
        )
        val fromMediaStore = source.contentUri.startsWith(MEDIA_STORE_PREFIX)
        return createTransfer(
            binding = binding,
            authorityType = AUTHORITY_MANUAL,
            authorityId = "retry:${UUID.randomUUID()}",
            enrollment = null,
            mediaId = row.mediaId,
            contentUri = source.contentUri,
            replaces = row,
        ) { output ->
            if (fromMediaStore) originals.copyMediaStoreItem(source.contentUri, "", output)
            else originals.copySelected(source.contentUri, source.displayName, output)
        }
    }

    /** Retries every item [retry] accepts. One that is refused again keeps its row and reason. */
    suspend fun retryAll(binding: AccountBinding): Int = transferDao.findRetryable(binding.accountId).count { row ->
        try {
            retry(row.transferId)
            true
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            false
        }
    }

    /** Records an item automatic backup found but the uploader refuses, so the user sees it and why. */
    private suspend fun recordSkipped(
        binding: AccountBinding,
        enrollment: BackupEnrollmentEntity,
        item: DeviceMediaRecord,
        reason: String,
    ) {
        val sourceId = UUID.randomUUID().toString()
        val recordedAt = now()
        val authorityId = "enrollment:${enrollment.enrollmentId}:${enrollment.revision}"
        database.withTransaction {
            val active = refuseIfNull(accountDao.findActiveAccount(), StoredMessage.SIGN_IN_TO_ICLOUD_BEFORE_UPLOADING)
            refuseUnless(active.toBinding() == binding, StoredMessage.THE_ICLOUD_ACCOUNT_CHANGED_WHILE_STAGING_THIS_PHOTO)
            transferDao.insertStagedSource(
                StagedSourceEntity(
                    stagedSourceId = sourceId,
                    accountId = binding.accountId,
                    authEpoch = binding.authEpoch,
                    operationFence = binding.operationFence,
                    authorityType = AUTHORITY_AUTOMATIC,
                    authorityId = authorityId,
                    enrollmentId = enrollment.enrollmentId,
                    enrollmentRevision = enrollment.revision,
                    mediaId = item.id,
                    contentUri = item.contentUri,
                    displayName = item.fileName,
                    mimeType = item.mimeType,
                    lineageKey = "${item.volumeName}:${item.mediaStoreId}",
                    sourceRevision = item.sourceRevision,
                    originalAcquisition = "NOT_STAGED",
                    originalFormatRequested = false,
                    unredacted = false,
                    formatValidated = false,
                    // Nothing was staged: the token names no file and the row is released from the start.
                    ownedFileToken = "skipped:$sourceId",
                    byteCount = item.byteCount,
                    sha256Hex = "",
                    createdAtEpochMillis = recordedAt,
                    releasedAtEpochMillis = recordedAt,
                ),
            )
            transferDao.insertTransfer(
                TransferEntity(
                    transferId = UUID.randomUUID().toString(),
                    stagedSourceId = sourceId,
                    accountId = binding.accountId,
                    authEpoch = binding.authEpoch,
                    operationFence = binding.operationFence,
                    authorityType = AUTHORITY_AUTOMATIC,
                    authorityId = authorityId,
                    enrollmentId = enrollment.enrollmentId,
                    enrollmentRevision = enrollment.revision,
                    mediaId = item.id,
                    displayName = item.fileName,
                    state = TransferState.UNSUPPORTED,
                    stateVersion = 0,
                    activeAttemptId = null,
                    leaseToken = null,
                    leaseExpiresAtEpochMillis = null,
                    reconciliationDueAtEpochMillis = null,
                    reconciliationDeadlineEpochMillis = null,
                    reconciliationChangeToken = null,
                    verifiedAtEpochMillis = null,
                    createdAtEpochMillis = recordedAt,
                    updatedAtEpochMillis = recordedAt,
                    message = reason,
                ),
            )
        }
    }

    /**
     * Where discovery resumes for this enrollment. A JPEG-only enrollment keeps its original id. For the current scope
     * the folders and video access are part of it, so adding a folder or allowing videos looks again from enrollment
     * rather than skipping items the old checkpoint already passed.
     */
    private fun discoveryCheckpointId(
        binding: AccountBinding,
        enrollment: BackupEnrollmentEntity,
        scope: BackupScope,
        folders: Set<String>,
    ): String {
        val base = "${binding.accountId}:${enrollment.enrollmentId}:${enrollment.revision}:external"
        if (scope == BackupScope.CAMERA_JPEGS) return base
        val coverage = (folders.sorted() + "video=${originals.hasVideoAccess()}").joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(coverage.toByteArray(Charsets.UTF_8))
        return "$base:media:" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun BackupEnrollmentEntity.baseline(): LocalDiscoveryCheckpoint? = discoveryBaselineVersion?.let { version ->
        discoveryBaselineGeneration?.let { generation ->
            LocalDiscoveryCheckpoint("external", version, generation, Long.MAX_VALUE)
        }
    }

    private fun unsupportedReason(mimeType: String): String = when (mimeType.lowercase()) {
        "image/avif" -> UnsupportedUploadException.AVIF
        "image/heic-sequence", "image/heif-sequence" -> UnsupportedUploadException.IMAGE_SEQUENCE
        else -> if (mimeType.startsWith("video/")) UnsupportedUploadException.VIDEO_CODEC else UnsupportedUploadException.MESSAGE
    }

    private suspend fun createTransfer(
        binding: AccountBinding,
        authorityType: String,
        authorityId: String,
        enrollment: BackupEnrollmentEntity?,
        mediaId: String?,
        contentUri: String,
        replaces: TransferEntity? = null,
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
                if (replaces != null) {
                    // The old row must still be the one the user retried; a concurrent retry already replaced it.
                    refuseUnless(transferDao.deleteRetried(replaces.transferId) == 1, StoredMessage.THIS_ITEM_IS_NO_LONGER_IN_THE_QUEUE)
                    transferDao.deleteUnattemptedSource(replaces.stagedSourceId)
                }
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
        /** Items checked per discovery pass; at most one of them is staged. */
        const val AUTO_DISCOVERY_PAGE_SIZE = 20
        const val MEDIA_STORE_PREFIX = "content://media/"
        val RETRYABLE_STATES = setOf(TransferState.NEEDS_ATTENTION, TransferState.UNSUPPORTED)
        val PASSED_OVER = setOf(
            StoredMessage.THESE_EXACT_BYTES_ARE_ALREADY_QUEUED_OR_VERIFIED_IN_ICLOUD,
            StoredMessage.A_PREVIOUS_UPLOAD_OF_THESE_BYTES_IS_UNRESOLVED_AND_WILL_NOT_BE_REPEATED,
        )
    }
}
