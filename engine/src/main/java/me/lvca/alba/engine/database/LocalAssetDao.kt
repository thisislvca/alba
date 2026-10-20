package me.lvca.alba.engine.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** MediaStore rows discovered for automatic backup and the discovery checkpoint. */
@Dao
interface LocalAssetDao {
    @Upsert
    suspend fun upsertLocalAssets(assets: List<LocalAssetEntity>)

    @Query("SELECT * FROM local_assets WHERE localAssetId = :localAssetId")
    suspend fun findLocalAsset(localAssetId: String): LocalAssetEntity?

    @Query("SELECT * FROM media_store_checkpoints WHERE checkpointId = :checkpointId")
    suspend fun findMediaStoreCheckpoint(checkpointId: String): MediaStoreCheckpointEntity?

    @Upsert
    suspend fun upsertMediaStoreCheckpoint(checkpoint: MediaStoreCheckpointEntity)
}
