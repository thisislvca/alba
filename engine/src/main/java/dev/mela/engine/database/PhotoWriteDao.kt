package dev.mela.engine.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class TransferProjection(
    val transferId: String,
    val mediaId: String?,
    val displayName: String,
    val state: String,
    val byteCount: Long,
    val updatedAtEpochMillis: Long,
    val verifiedAtEpochMillis: Long?,
    val message: String?,
)

data class BackupProjection(
    val enabled: Boolean,
    val destinationLabel: String,
    val scope: String,
)

data class ReleasableStageProjection(
    val stagedSourceId: String,
    val ownedFileToken: String,
)

@Dao
interface PhotoWriteDao {
    @Query(
        """
        SELECT t.transferId, t.mediaId, t.displayName, t.state,
               s.byteCount, t.updatedAtEpochMillis, t.verifiedAtEpochMillis, t.message
        FROM transfers t
        JOIN staged_sources s ON s.stagedSourceId = t.stagedSourceId
        JOIN accounts a ON a.accountId = t.accountId AND a.active = 1
        ORDER BY t.updatedAtEpochMillis DESC, t.transferId DESC
        """,
    )
    fun observeTransfers(): Flow<List<TransferProjection>>

    @Query(
        """
        SELECT e.enabled, a.destinationLabel, e.scope
        FROM backup_enrollments e
        JOIN accounts a ON a.accountId = e.accountId
        WHERE a.active = 1
        LIMIT 1
        """,
    )
    fun observeBackup(): Flow<BackupProjection?>

    @Query("SELECT * FROM accounts WHERE active = 1 LIMIT 1")
    suspend fun findActiveAccount(): AccountEntity?

    @Query("SELECT * FROM accounts WHERE accountId = :accountId")
    suspend fun findAccount(accountId: String): AccountEntity?

    @Query("UPDATE accounts SET active = 0, operationFence = operationFence + 1, updatedAtEpochMillis = :now WHERE active = 1")
    suspend fun deactivateActiveAccounts(now: Long): Int

    @Query(
        """
        UPDATE transfers
        SET state = 'NEEDS_ATTENTION', stateVersion = stateVersion + 1,
            updatedAtEpochMillis = :now, message = :message
        WHERE accountId = :accountId AND state = 'READY'
        """,
    )
    suspend fun markReadyTransfersNeedAttention(accountId: String, now: Long, message: String): Int

    @Query(
        """
        UPDATE transfers
        SET state = 'NEEDS_ATTENTION', stateVersion = stateVersion + 1,
            updatedAtEpochMillis = :now, message = :message
        WHERE accountId = :accountId AND authorityType = 'AUTOMATIC' AND state = 'READY'
        """,
    )
    suspend fun markReadyAutomaticTransfersNeedAttention(accountId: String, now: Long, message: String): Int

    @Query("""
        UPDATE transfers SET state = 'NEEDS_ATTENTION', stateVersion = stateVersion + 1,
            leaseToken = NULL, leaseExpiresAtEpochMillis = NULL,
            updatedAtEpochMillis = :now, message = 'Upload canceled. Select the file again to upload it.'
        WHERE accountId = :accountId AND authorityType = 'MANUAL' AND state = 'READY'
    """)
    suspend fun cancelReadyManualTransfers(accountId: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM transfers WHERE accountId = :accountId AND authorityType = 'MANUAL' AND state IN ('READY', 'SENDING')")
    suspend fun countPendingManualTransfers(accountId: String): Int

    @Upsert
    suspend fun upsertAccount(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStagedSource(source: StagedSourceEntity)

    @Query("SELECT * FROM staged_sources WHERE stagedSourceId = :sourceId")
    suspend fun findStagedSource(sourceId: String): StagedSourceEntity?

    @Query("SELECT ownedFileToken FROM staged_sources WHERE releasedAtEpochMillis IS NULL")
    suspend fun findOwnedStageTokens(): List<String>

    @Query(
        """
        SELECT s.stagedSourceId, s.ownedFileToken FROM staged_sources s
        JOIN transfers t ON t.stagedSourceId = s.stagedSourceId
        WHERE t.state IN ('VERIFIED', 'ABANDONED', 'NEEDS_ATTENTION', 'UNSUPPORTED')
          AND s.releasedAtEpochMillis IS NULL
        """,
    )
    suspend fun findReleasableStages(): List<ReleasableStageProjection>

    @Query(
        """
        UPDATE staged_sources
        SET releasedAtEpochMillis = :releasedAt
        WHERE stagedSourceId IN (:sourceIds) AND releasedAtEpochMillis IS NULL
        """,
    )
    suspend fun markStagesReleased(sourceIds: List<String>, releasedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransfer(transfer: TransferEntity)

    @Query("SELECT * FROM transfers WHERE transferId = :transferId")
    suspend fun findTransfer(transferId: String): TransferEntity?

    @Query(
        """
        SELECT t.transferId FROM transfers t
        JOIN staged_sources s ON s.stagedSourceId = t.stagedSourceId
        WHERE t.accountId = :accountId AND s.sha256Hex = :sha256Hex
          AND t.state NOT IN ('NEEDS_ATTENTION', 'UNSUPPORTED', 'ABANDONED')
        ORDER BY t.createdAtEpochMillis DESC LIMIT 1
        """,
    )
    suspend fun findExistingTransferForBytes(accountId: String, sha256Hex: String): String?

    @Query(
        """
        SELECT * FROM transfers
        WHERE accountId = :accountId AND state = 'READY' AND (:allowAutomatic OR authorityType != 'AUTOMATIC')
          AND (:allowManual OR authorityType != 'MANUAL')
          AND (leaseExpiresAtEpochMillis IS NULL OR leaseExpiresAtEpochMillis < :now)
        ORDER BY CASE authorityType WHEN 'MANUAL' THEN 0 ELSE 1 END,
                 createdAtEpochMillis, transferId
        LIMIT 1
        """,
    )
    suspend fun findNextReady(accountId: String, now: Long, allowAutomatic: Boolean = true, allowManual: Boolean = true): TransferEntity?

    @Query(
        """
        UPDATE transfers
        SET leaseToken = :leaseToken,
            leaseExpiresAtEpochMillis = :leaseExpiresAt,
            stateVersion = stateVersion + 1,
            updatedAtEpochMillis = :now
        WHERE transferId = :transferId AND state = 'READY'
          AND stateVersion = :expectedVersion
          AND (leaseExpiresAtEpochMillis IS NULL OR leaseExpiresAtEpochMillis < :now)
        """,
    )
    suspend fun claimReady(
        transferId: String,
        expectedVersion: Long,
        leaseToken: String,
        leaseExpiresAt: Long,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE transfers
        SET leaseToken = NULL, leaseExpiresAtEpochMillis = NULL,
            stateVersion = stateVersion + 1, updatedAtEpochMillis = :now,
            message = :message
        WHERE transferId = :transferId AND state = 'READY' AND leaseToken = :leaseToken
        """,
    )
    suspend fun releaseReadyLease(transferId: String, leaseToken: String, now: Long, message: String): Int

    @Query(
        """
        UPDATE transfers
        SET state = 'NEEDS_ATTENTION', stateVersion = stateVersion + 1,
            leaseToken = NULL, leaseExpiresAtEpochMillis = NULL,
            updatedAtEpochMillis = :now, message = :message
        WHERE transferId = :transferId AND state = 'READY' AND leaseToken = :leaseToken
        """,
    )
    suspend fun markLeasedTransferNeedsAttention(
        transferId: String,
        leaseToken: String,
        now: Long,
        message: String,
    ): Int

    @Query(
        """
        UPDATE transfers
        SET state = 'SENDING', activeAttemptId = :attemptId,
            leaseToken = NULL, leaseExpiresAtEpochMillis = NULL,
            stateVersion = stateVersion + 1, updatedAtEpochMillis = :now,
            message = 'Uploading to iCloud'
        WHERE transferId = :transferId AND state = 'READY'
          AND stateVersion = :expectedVersion AND leaseToken = :leaseToken
          AND accountId = :accountId AND authEpoch = :authEpoch
          AND operationFence = :operationFence
        """,
    )
    suspend fun armSending(
        transferId: String,
        expectedVersion: Long,
        leaseToken: String,
        accountId: String,
        authEpoch: Long,
        operationFence: Long,
        attemptId: String,
        now: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(attempt: UploadAttemptEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun acquireWriteSlot(slot: AccountWriteSlotEntity)

    @Query("SELECT * FROM account_write_slots WHERE accountId = :accountId")
    suspend fun findWriteSlot(accountId: String): AccountWriteSlotEntity?

    @Query("DELETE FROM account_write_slots WHERE accountId = :accountId AND attemptId = :attemptId")
    suspend fun releaseWriteSlot(accountId: String, attemptId: String): Int

    @Query("SELECT * FROM upload_attempts WHERE attemptId = :attemptId")
    suspend fun findAttempt(attemptId: String): UploadAttemptEntity?

    @Query("SELECT * FROM upload_acceptances WHERE attemptId = :attemptId")
    suspend fun findAcceptance(attemptId: String): UploadAcceptanceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAcceptance(acceptance: UploadAcceptanceEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUncertainty(uncertainty: UploadUncertaintyEntity): Long

    @Query(
        """
        UPDATE transfers
        SET state = 'RECONCILING', stateVersion = stateVersion + 1,
            reconciliationDueAtEpochMillis = :dueAt,
            reconciliationDeadlineEpochMillis = COALESCE(reconciliationDeadlineEpochMillis, :deadline),
            updatedAtEpochMillis = :now, message = 'Checking the exact original in iCloud'
        WHERE transferId = :transferId AND activeAttemptId = :attemptId
          AND state IN ('SENDING', 'OUTCOME_UNKNOWN', 'RECONCILING', 'READ_FAILURE')
        """,
    )
    suspend fun markReconciling(
        transferId: String,
        attemptId: String,
        dueAt: Long,
        deadline: Long,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE transfers
        SET state = 'OUTCOME_UNKNOWN', stateVersion = stateVersion + 1,
            reconciliationDueAtEpochMillis = :now,
            reconciliationDeadlineEpochMillis = COALESCE(reconciliationDeadlineEpochMillis, :deadline),
            leaseToken = NULL, leaseExpiresAtEpochMillis = NULL,
            updatedAtEpochMillis = :now,
            message = 'Upload result was lost. Mela will check iCloud without uploading again.'
        WHERE transferId = :transferId AND activeAttemptId = :attemptId
          AND state = 'SENDING'
        """,
    )
    suspend fun markOutcomeUnknown(
        transferId: String,
        attemptId: String,
        now: Long,
        deadline: Long,
    ): Int

    @Query("SELECT * FROM transfers WHERE state = 'SENDING'")
    suspend fun findInterruptedSending(): List<TransferEntity>

    @Query(
        """
        SELECT * FROM transfers
        WHERE accountId = :accountId AND (:allowAutomatic OR authorityType != 'AUTOMATIC')
          AND state IN ('OUTCOME_UNKNOWN', 'RECONCILING', 'READ_FAILURE')
          AND (reconciliationDueAtEpochMillis IS NULL OR reconciliationDueAtEpochMillis <= :now)
        ORDER BY updatedAtEpochMillis, transferId
        LIMIT 1
        """,
    )
    suspend fun findNextReconciliation(accountId: String, now: Long, allowAutomatic: Boolean = true): TransferEntity?

    @Query(
        """
        UPDATE transfers
        SET state = 'READ_FAILURE', stateVersion = stateVersion + 1,
            reconciliationDueAtEpochMillis = :dueAt, updatedAtEpochMillis = :now,
            message = :message
        WHERE transferId = :transferId AND activeAttemptId = :attemptId
          AND state IN ('OUTCOME_UNKNOWN', 'RECONCILING', 'READ_FAILURE')
        """,
    )
    suspend fun markReadFailure(
        transferId: String,
        attemptId: String,
        dueAt: Long,
        now: Long,
        message: String,
    ): Int

    @Query(
        """
        UPDATE transfers
        SET reconciliationChangeToken = :changeToken,
            reconciliationDueAtEpochMillis = :dueAt,
            state = 'RECONCILING', stateVersion = stateVersion + 1,
            updatedAtEpochMillis = :now
        WHERE transferId = :transferId AND activeAttemptId = :attemptId
          AND state IN ('OUTCOME_UNKNOWN', 'RECONCILING', 'READ_FAILURE')
        """,
    )
    suspend fun advanceReconciliationToken(
        transferId: String,
        attemptId: String,
        changeToken: String,
        dueAt: Long,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE transfers
        SET state = :state, stateVersion = stateVersion + 1,
            reconciliationDueAtEpochMillis = NULL, updatedAtEpochMillis = :now,
            message = :message
        WHERE transferId = :transferId AND activeAttemptId = :attemptId
          AND state IN ('OUTCOME_UNKNOWN', 'RECONCILING', 'READ_FAILURE')
        """,
    )
    suspend fun markUnresolvedTerminal(
        transferId: String,
        attemptId: String,
        state: String,
        now: Long,
        message: String,
    ): Int

    @Query(
        """
        UPDATE transfers
        SET state = 'ABANDONED', stateVersion = stateVersion + 1,
            updatedAtEpochMillis = :now,
            message = 'This uncertain item remains blocked from upload. Other photos may continue.'
        WHERE transferId = :transferId AND activeAttemptId = :attemptId
          AND state IN ('AMBIGUOUS', 'NOT_OBSERVED')
        """,
    )
    suspend fun abandonUnresolved(transferId: String, attemptId: String, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRemotePair(pair: RemoteAssetPairEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReconciliationMatch(match: ReconciliationMatchEntity): Long

    @Query(
        """
        SELECT p.* FROM remote_asset_pairs p
        JOIN reconciliation_matches m ON m.remotePairId = p.remotePairId
        WHERE m.attemptId = :attemptId
        ORDER BY p.remotePairId
        """,
    )
    suspend fun findReconciliationMatches(attemptId: String): List<RemoteAssetPairEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAssetLink(link: AssetLinkEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVerificationProof(proof: VerificationProofEntity)

    @Query(
        """
        UPDATE transfers
        SET state = 'VERIFIED', stateVersion = stateVersion + 1,
            reconciliationDueAtEpochMillis = NULL,
            verifiedAtEpochMillis = :verifiedAt, updatedAtEpochMillis = :verifiedAt,
            message = 'Verified in iCloud'
        WHERE transferId = :transferId AND activeAttemptId = :attemptId
          AND state IN ('OUTCOME_UNKNOWN', 'RECONCILING', 'READ_FAILURE')
        """,
    )
    suspend fun markVerified(
        transferId: String,
        attemptId: String,
        verifiedAt: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSuppression(suppression: UploadSuppressionEntity): Long

    @Query(
        """
        SELECT COUNT(*) FROM upload_suppressions
        WHERE accountId = :accountId AND active = 1
          AND (lineageKey = :lineageKey OR sha256Hex = :sha256Hex)
        """,
    )
    suspend fun countActiveSuppressions(accountId: String, lineageKey: String, sha256Hex: String): Int

    @Query("SELECT COUNT(*) FROM transfers WHERE accountId = :accountId AND state NOT IN ('VERIFIED', 'ABANDONED')")
    suspend fun countOpenTransfers(accountId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM transfers
        WHERE accountId = :accountId AND (
            state = 'READY' OR
            (state IN ('OUTCOME_UNKNOWN', 'RECONCILING', 'READ_FAILURE') AND
             (reconciliationDueAtEpochMillis IS NULL OR reconciliationDueAtEpochMillis <= :now))
        )
        """,
    )
    suspend fun countDueWork(accountId: String, now: Long): Int

    @Query("""
        SELECT MIN(COALESCE(reconciliationDueAtEpochMillis, :now)) FROM transfers
        WHERE accountId = :accountId AND (:allowAutomatic OR authorityType != 'AUTOMATIC') AND state IN ('OUTCOME_UNKNOWN', 'RECONCILING', 'READ_FAILURE')
    """)
    suspend fun earliestReconciliationDue(accountId: String, now: Long, allowAutomatic: Boolean = true): Long?

    @Query("""
        SELECT COUNT(*) FROM transfers WHERE accountId = :accountId AND state = 'READY'
          AND (:allowAutomatic OR authorityType != 'AUTOMATIC')
          AND (:allowManual OR authorityType != 'MANUAL')
    """)
    suspend fun countRunnableReady(accountId: String, allowAutomatic: Boolean, allowManual: Boolean = true): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBackupDraft(draft: BackupEnrollmentDraftEntity)

    @Query("SELECT * FROM backup_enrollment_drafts WHERE draftId = :draftId")
    suspend fun findBackupDraft(draftId: String): BackupEnrollmentDraftEntity?

    @Query(
        """
        UPDATE backup_enrollment_drafts
        SET phase = 'CONSENTED', acceptedAtEpochMillis = :acceptedAt
        WHERE draftId = :draftId AND phase = 'SHOWN' AND expiresAtEpochMillis > :acceptedAt
        """,
    )
    suspend fun acceptBackupDraft(draftId: String, acceptedAt: Long): Int

    @Query(
        """
        UPDATE backup_enrollment_drafts SET phase = 'COMPLETED'
        WHERE draftId = :draftId AND phase = 'CONSENTED'
        """,
    )
    suspend fun completeBackupDraft(draftId: String): Int

    @Upsert
    suspend fun upsertBackupEnrollment(enrollment: BackupEnrollmentEntity)

    @Query("SELECT * FROM backup_enrollments WHERE accountId = :accountId LIMIT 1")
    suspend fun findBackupEnrollment(accountId: String): BackupEnrollmentEntity?

    @Query("SELECT * FROM backup_enrollments WHERE accountId = :accountId AND enabled = 1 LIMIT 1")
    suspend fun findEnabledBackupEnrollment(accountId: String): BackupEnrollmentEntity?

    @Query(
        """
        UPDATE backup_enrollments
        SET enabled = 0, revision = revision + 1, operationFence = :operationFence,
            updatedAtEpochMillis = :now
        WHERE accountId = :accountId AND enabled = 1
        """,
    )
    suspend fun disableBackup(accountId: String, operationFence: Long, now: Long): Int

    @Upsert
    suspend fun upsertLocalAssets(assets: List<LocalAssetEntity>)

    @Query("SELECT * FROM transfers WHERE accountId = :accountId AND mediaId = :mediaId AND state NOT IN ('NEEDS_ATTENTION', 'UNSUPPORTED', 'ABANDONED') LIMIT 1")
    suspend fun findTransferForMedia(accountId: String, mediaId: String): TransferEntity?

    @Query("SELECT * FROM local_assets WHERE localAssetId = :localAssetId")
    suspend fun findLocalAsset(localAssetId: String): LocalAssetEntity?

    @Query("SELECT * FROM media_store_checkpoints WHERE checkpointId = :checkpointId")
    suspend fun findMediaStoreCheckpoint(checkpointId: String): MediaStoreCheckpointEntity?

    @Upsert
    suspend fun upsertMediaStoreCheckpoint(checkpoint: MediaStoreCheckpointEntity)

    @Query(
        """
        SELECT l.* FROM local_assets l
        LEFT JOIN transfers t ON t.mediaId = l.localAssetId AND t.accountId = :accountId
          AND t.state NOT IN ('NEEDS_ATTENTION', 'UNSUPPORTED', 'ABANDONED')
        WHERE t.transferId IS NULL AND l.mimeType IN ('image/jpeg', 'image/jpg')
          AND (:volumeName = 'external' OR l.volumeName = :volumeName) AND l.volumeVersion = :volumeVersion
          AND l.permissionFingerprint = :permissionFingerprint
        ORDER BY l.generationModified, l.mediaStoreId
        LIMIT 1
        """,
    )
    suspend fun findNextUnqueuedLocalAsset(
        accountId: String,
        volumeName: String,
        volumeVersion: String,
        permissionFingerprint: String,
    ): LocalAssetEntity?

    @Query(
        """
        SELECT p.* FROM verification_proofs p
        WHERE p.mediaId = :mediaId AND p.accountId = :accountId
          AND p.invalidatedAtEpochMillis IS NULL
        ORDER BY p.verifiedAtEpochMillis DESC LIMIT 1
        """,
    )
    suspend fun findCurrentProof(mediaId: String, accountId: String): VerificationProofEntity?

    @Query("SELECT * FROM verification_proofs WHERE proofId = :proofId")
    suspend fun findProof(proofId: String): VerificationProofEntity?

    @Query("SELECT * FROM asset_links WHERE linkId = :linkId")
    suspend fun findAssetLink(linkId: String): AssetLinkEntity?

    @Query("SELECT * FROM remote_asset_pairs WHERE remotePairId = :pairId")
    suspend fun findRemotePair(pairId: String): RemoteAssetPairEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReclaimProposal(proposal: ReclaimProposalEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReclaimProposalItems(items: List<ReclaimProposalItemEntity>)

    @Query("SELECT * FROM reclaim_proposals WHERE proposalId = :proposalId")
    suspend fun findReclaimProposal(proposalId: String): ReclaimProposalEntity?

    @Query("SELECT * FROM reclaim_proposal_items WHERE proposalId = :proposalId")
    suspend fun findReclaimProposalItems(proposalId: String): List<ReclaimProposalItemEntity>

    @Query(
        """
        UPDATE reclaim_proposals
        SET state = 'HANDED_OFF'
        WHERE proposalId = :proposalId AND state = 'READY'
          AND expiresAtEpochMillis > :now
        """,
    )
    suspend fun markReclaimProposalHandedOff(proposalId: String, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrashAttempt(attempt: TrashAttemptEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrashObligations(obligations: List<TrashObligationEntity>)

    @Query("SELECT * FROM trash_attempts WHERE trashAttemptId = :attemptId")
    suspend fun findTrashAttempt(attemptId: String): TrashAttemptEntity?

    @Query("SELECT * FROM trash_obligations WHERE trashAttemptId = :attemptId")
    suspend fun findTrashObligations(attemptId: String): List<TrashObligationEntity>

    @Query(
        """
        UPDATE trash_attempts
        SET consentDisposition = :disposition, callbackAtEpochMillis = :now,
            callbackResult = :callbackResult
        WHERE trashAttemptId = :attemptId
          AND consentDisposition IN ('HANDED_OFF_EXECUTION_POSSIBLE', 'RESULT_UNKNOWN_EXECUTION_STILL_POSSIBLE')
        """,
    )
    suspend fun recordTrashCallback(
        attemptId: String,
        disposition: String,
        callbackResult: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE trash_attempts
        SET consentDisposition = 'RESULT_UNKNOWN_EXECUTION_STILL_POSSIBLE'
        WHERE consentDisposition = 'HANDED_OFF_EXECUTION_POSSIBLE'
        """,
    )
    suspend fun recoverTrashHandoffs(): Int

    @Query(
        """
        UPDATE trash_obligations
        SET uriState = :state, trashExpiresAtEpochMillis = :expiresAt,
            resolvedAtEpochMillis = :resolvedAt
        WHERE trashAttemptId = :attemptId AND mediaId = :mediaId
        """,
    )
    suspend fun updateTrashObligation(
        attemptId: String,
        mediaId: String,
        state: String,
        expiresAt: Long?,
        resolvedAt: Long?,
    ): Int

    @Query(
        """
        UPDATE trash_attempts
        SET consentDisposition = :disposition
        WHERE trashAttemptId = :attemptId
        """,
    )
    suspend fun setTrashDisposition(attemptId: String, disposition: String): Int

    @Query(
        """
        SELECT * FROM trash_attempts
        WHERE consentDisposition IN ('CALLBACK_OBSERVED_APPROVED', 'RESULT_UNKNOWN_EXECUTION_STILL_POSSIBLE')
        """,
    )
    suspend fun findApprovedTrashAttempts(): List<TrashAttemptEntity>

    @Query(
        """
        SELECT COUNT(*) FROM trash_attempts
        WHERE consentDisposition IN (
            'HANDED_OFF_EXECUTION_POSSIBLE',
            'RESULT_UNKNOWN_EXECUTION_STILL_POSSIBLE',
            'CALLBACK_OBSERVED_APPROVED'
        )
        """,
    )
    suspend fun countPotentiallyExecutableTrashAttempts(): Int
}
