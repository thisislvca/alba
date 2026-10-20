package me.lvca.alba.engine.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Reclaim proposals, Android trash consent attempts and their per-photo obligations. */
@Dao
interface TrashDao {
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
