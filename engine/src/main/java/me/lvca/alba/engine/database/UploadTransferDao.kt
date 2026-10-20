package me.lvca.alba.engine.database

import androidx.room.Dao
import androidx.room.Query

/** Reads the retired upload_transfers table, kept only so older databases still migrate. */
@Dao
interface UploadTransferDao {
    @Query(
        """
        SELECT * FROM upload_transfers
        WHERE mediaId = :mediaId AND accountLabel = :accountLabel
        ORDER BY createdAtEpochMillis DESC, transferId DESC
        LIMIT 1
        """,
    )
    suspend fun findLatestForMedia(mediaId: String, accountLabel: String): UploadTransferEntity?
}
