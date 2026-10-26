package me.lvca.alba.engine.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.lvca.alba.engine.model.StoredMessage

data class TransferProjection(
    val transferId: String,
    val mediaId: String?,
    val displayName: String,
    val state: TransferState,
    val byteCount: Long,
    val updatedAtEpochMillis: Long,
    val verifiedAtEpochMillis: Long?,
    val message: String?,
    val authorityType: String = "MANUAL",
    val activeAttemptId: String? = null,
)

data class ReleasableStageProjection(
    val stagedSourceId: String,
    val ownedFileToken: String,
)

/** The upload state machine: staged sources, transfers, attempts, the per-account write slot and suppressions. */
@Dao
interface TransferDao {
    @Query(
        """
        SELECT t.transferId, t.mediaId, t.displayName, t.state,
               s.byteCount, t.updatedAtEpochMillis, t.verifiedAtEpochMillis, t.message,
               t.authorityType, t.activeAttemptId
        FROM transfers t
        JOIN staged_sources s ON s.stagedSourceId = t.stagedSourceId
        JOIN accounts a ON a.accountId = t.accountId AND a.active = 1
        ORDER BY t.updatedAtEpochMillis DESC, t.transferId DESC
        """,
    )
    fun observeTransfers(): Flow<List<TransferProjection>>

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
            updatedAtEpochMillis = :now, message = :message
        WHERE accountId = :accountId AND authorityType = 'MANUAL' AND state = 'READY'
    """)
    suspend fun cancelReadyManualTransfers(
        accountId: String,
        now: Long,
        message: String = StoredMessage.UPLOAD_CANCELED_SELECT_THE_FILE_AGAIN_TO_UPLOAD_IT.code,
    ): Int

    @Query("SELECT COUNT(*) FROM transfers WHERE accountId = :accountId AND authorityType = 'MANUAL' AND state IN ('READY', 'SENDING')")
    suspend fun countPendingManualTransfers(accountId: String): Int

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
        SELECT t.* FROM transfers t
        JOIN staged_sources s ON s.stagedSourceId = t.stagedSourceId
        WHERE t.accountId = :accountId AND t.state = 'READY'
          AND (t.authorityType != 'AUTOMATIC' OR
               (s.mimeType LIKE 'video/%' AND :allowAutomaticVideos) OR
               (s.mimeType NOT LIKE 'video/%' AND :allowAutomaticPhotos))
          AND (:allowManual OR t.authorityType != 'MANUAL')
          AND (t.leaseExpiresAtEpochMillis IS NULL OR t.leaseExpiresAtEpochMillis < :now)
        ORDER BY CASE t.authorityType WHEN 'MANUAL' THEN 0 ELSE 1 END,
                 t.createdAtEpochMillis, t.transferId
        LIMIT 1
        """,
    )
    suspend fun findNextReady(
        accountId: String,
        now: Long,
        allowAutomaticPhotos: Boolean = true,
        allowAutomaticVideos: Boolean = allowAutomaticPhotos,
        allowManual: Boolean = true,
    ): TransferEntity?

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
            message = :message
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
        message: String = StoredMessage.UPLOADING_TO_ICLOUD.code,
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
            updatedAtEpochMillis = :now, message = :message
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
        message: String = StoredMessage.CHECKING_THE_EXACT_ORIGINAL_IN_ICLOUD.code,
    ): Int

    @Query(
        """
        UPDATE transfers
        SET state = 'OUTCOME_UNKNOWN', stateVersion = stateVersion + 1,
            reconciliationDueAtEpochMillis = :now,
            reconciliationDeadlineEpochMillis = COALESCE(reconciliationDeadlineEpochMillis, :deadline),
            leaseToken = NULL, leaseExpiresAtEpochMillis = NULL,
            updatedAtEpochMillis = :now,
            message = :message
        WHERE transferId = :transferId AND activeAttemptId = :attemptId
          AND state = 'SENDING'
        """,
    )
    suspend fun markOutcomeUnknown(
        transferId: String,
        attemptId: String,
        now: Long,
        deadline: Long,
        message: String = StoredMessage.UPLOAD_RESULT_WAS_LOST_ALBA_WILL_CHECK_ICLOUD_WITHOUT_UPLOADING_AGAIN.code,
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
        state: TransferState,
        now: Long,
        message: String,
    ): Int

    @Query(
        """
        UPDATE transfers
        SET state = 'ABANDONED', stateVersion = stateVersion + 1,
            updatedAtEpochMillis = :now,
            message = :message
        WHERE transferId = :transferId AND activeAttemptId = :attemptId
          AND state IN ('AMBIGUOUS', 'NOT_OBSERVED')
        """,
    )
    suspend fun abandonUnresolved(transferId: String, attemptId: String, now: Long, message: String = StoredMessage.THIS_UNCERTAIN_ITEM_REMAINS_BLOCKED_FROM_UPLOAD_OTHER_PHOTOS_MAY_CONTINUE.code): Int

    @Query(
        """
        UPDATE transfers
        SET state = 'VERIFIED', stateVersion = stateVersion + 1,
            reconciliationDueAtEpochMillis = NULL,
            verifiedAtEpochMillis = :verifiedAt, updatedAtEpochMillis = :verifiedAt,
            message = :message
        WHERE transferId = :transferId AND activeAttemptId = :attemptId
          AND state IN ('OUTCOME_UNKNOWN', 'RECONCILING', 'READ_FAILURE')
        """,
    )
    suspend fun markVerified(
        transferId: String,
        attemptId: String,
        verifiedAt: Long,
        message: String = StoredMessage.VERIFIED_IN_ICLOUD.code,
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

    @Query("""
        SELECT MIN(COALESCE(reconciliationDueAtEpochMillis, :now)) FROM transfers
        WHERE accountId = :accountId AND (:allowAutomatic OR authorityType != 'AUTOMATIC') AND state IN ('OUTCOME_UNKNOWN', 'RECONCILING', 'READ_FAILURE')
    """)
    suspend fun earliestReconciliationDue(accountId: String, now: Long, allowAutomatic: Boolean = true): Long?

    @Query("""
        SELECT COUNT(*) FROM transfers t
        JOIN staged_sources s ON s.stagedSourceId = t.stagedSourceId
        WHERE t.accountId = :accountId AND t.state = 'READY'
          AND (t.authorityType != 'AUTOMATIC' OR
               (s.mimeType LIKE 'video/%' AND :allowAutomaticVideos) OR
               (s.mimeType NOT LIKE 'video/%' AND :allowAutomaticPhotos))
          AND (:allowManual OR t.authorityType != 'MANUAL')
    """)
    suspend fun countRunnableReady(
        accountId: String,
        allowAutomaticPhotos: Boolean,
        allowAutomaticVideos: Boolean = allowAutomaticPhotos,
        allowManual: Boolean = true,
    ): Int

    @Query("SELECT * FROM transfers WHERE accountId = :accountId AND mediaId = :mediaId AND state NOT IN ('NEEDS_ATTENTION', 'UNSUPPORTED', 'ABANDONED') LIMIT 1")
    suspend fun findTransferForMedia(accountId: String, mediaId: String): TransferEntity?

    /** Any journal row for the phone item, including skipped and failed ones, so discovery never reports it twice. */
    @Query("SELECT COUNT(*) FROM transfers WHERE accountId = :accountId AND mediaId = :mediaId")
    suspend fun countTransfersForMedia(accountId: String, mediaId: String): Int

    /** Items that stopped before any attempt was armed: nothing reached Apple, so staging them again is safe. */
    @Query(
        """
        SELECT * FROM transfers
        WHERE accountId = :accountId AND state IN ('NEEDS_ATTENTION', 'UNSUPPORTED') AND activeAttemptId IS NULL
        ORDER BY createdAtEpochMillis, transferId
        """,
    )
    suspend fun findRetryable(accountId: String): List<TransferEntity>

    /** Removes a row [findRetryable] returned, once its retry is queued. */
    @Query(
        """
        DELETE FROM transfers
        WHERE transferId = :transferId AND state IN ('NEEDS_ATTENTION', 'UNSUPPORTED') AND activeAttemptId IS NULL
        """,
    )
    suspend fun deleteRetried(transferId: String): Int

    @Query("DELETE FROM staged_sources WHERE stagedSourceId = :sourceId AND NOT EXISTS (SELECT 1 FROM upload_attempts WHERE stagedSourceId = :sourceId)")
    suspend fun deleteUnattemptedSource(sourceId: String): Int

    @Query("SELECT mediaId FROM transfers WHERE accountId = :accountId AND state = 'VERIFIED' AND mediaId IS NOT NULL")
    suspend fun findVerifiedMediaIds(accountId: String): List<String>

    /** Manual uploads finished since [since], and those still to run, for the transfer notification. */
    @Query(
        """
        SELECT COUNT(*) FROM transfers
        WHERE accountId = :accountId AND authorityType = 'MANUAL'
          AND (state IN ('READY', 'SENDING') OR createdAtEpochMillis >= :since)
        """,
    )
    suspend fun countManualInRun(accountId: String, since: Long): Int

    @Query("UPDATE transfers SET message = :message, updatedAtEpochMillis = :now WHERE transferId = :transferId AND state = 'SENDING'")
    suspend fun noteSendingMessage(transferId: String, message: String, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun pauseUploads(pause: UploadPauseEntity)

    @Query("SELECT * FROM upload_pauses WHERE accountId = :accountId")
    suspend fun findUploadPause(accountId: String): UploadPauseEntity?

    @Query(
        """
        SELECT p.* FROM upload_pauses p JOIN accounts a ON a.accountId = p.accountId AND a.active = 1 LIMIT 1
        """,
    )
    fun observeActiveUploadPause(): Flow<UploadPauseEntity?>

    @Query("DELETE FROM upload_pauses WHERE accountId = :accountId")
    suspend fun clearUploadPause(accountId: String): Int
}
