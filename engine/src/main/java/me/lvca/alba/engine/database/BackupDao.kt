package me.lvca.alba.engine.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class BackupProjection(
    val enabled: Boolean,
    val destinationLabel: String,
    val scope: String,
    val disclosureRevision: String = "",
)

/** Automatic backup disclosure drafts and the account's enrollment. */
@Dao
interface BackupDao {
    @Query(
        """
        SELECT e.enabled, a.destinationLabel, e.scope, e.disclosureRevision
        FROM backup_enrollments e
        JOIN accounts a ON a.accountId = e.accountId
        WHERE a.active = 1
        LIMIT 1
        """,
    )
    fun observeBackup(): Flow<BackupProjection?>

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
}
