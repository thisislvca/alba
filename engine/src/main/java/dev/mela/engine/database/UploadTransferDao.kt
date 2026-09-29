package dev.mela.engine.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadTransferDao {
    @Query(
        """
        SELECT * FROM upload_transfers
        ORDER BY updatedAtEpochMillis DESC, transferId DESC
        """,
    )
    fun observeAll(): Flow<List<UploadTransferEntity>>

    @Query(
        """
        SELECT * FROM upload_transfers
        WHERE mediaId = :mediaId AND accountLabel = :accountLabel
        ORDER BY createdAtEpochMillis DESC, transferId DESC
        LIMIT 1
        """,
    )
    suspend fun findLatestForMedia(mediaId: String, accountLabel: String): UploadTransferEntity?

    @Upsert
    suspend fun upsert(transfer: UploadTransferEntity)

    @Query(
        """
        UPDATE upload_transfers
        SET state = 'NEEDS_ATTENTION',
            message = :message,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE state IN ('READY_TO_UPLOAD', 'NEEDS_SIGN_IN')
        """,
    )
    suspend fun markOpenTransfersNeedAttention(updatedAtEpochMillis: Long, message: String)
}
