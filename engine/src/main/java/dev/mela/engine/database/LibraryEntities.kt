package dev.mela.engine.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "collections", primaryKeys = ["account", "collectionId"])
data class CollectionEntity(val account: String, val collectionId: String, val name: String,
    val parentId: String?, val isFolder: Boolean, val position: Long,
    val sharedGeneration: String? = null, val sharedRole: String? = null,
    val sharedOwnerName: String? = null, val sharedParticipantCount: Int? = null, val sharedWebUrl: String? = null)
@Entity(tableName = "collection_members", primaryKeys = ["account", "collectionId", "mediaId"])
data class CollectionMemberEntity(val account: String, val collectionId: String, val mediaId: String)
@Entity(tableName = "batch_items", primaryKeys = ["batchId", "mediaId"])
data class BatchItemEntity(val batchId: String, val mediaId: String, val account: String,
    val action: String, val state: String, val message: String?, val createdAt: Long)
@Entity(tableName = "upload_checkpoints")
data class UploadCheckpointEntity(@PrimaryKey val attemptId: String, val phase: String,
    val sealedPayload: ByteArray, val updatedAt: Long)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM collections WHERE account = :account AND collectionId = :id")
    suspend fun collection(account: String, id: String): CollectionEntity?
    @Query("DELETE FROM collection_members WHERE account = :account AND collectionId = :collection AND mediaId = :media")
    suspend fun removeMember(account: String, collection: String, media: String)

    @Query("DELETE FROM collections WHERE account = :account AND collectionId = :id")
    suspend fun deleteCollection(account: String, id: String)
    @Query("DELETE FROM collection_members WHERE account = :account AND collectionId = :id")
    suspend fun deleteCollectionMembers(account: String, id: String)
    @Query("SELECT * FROM collections ORDER BY position, collectionId") fun observeCollections(): Flow<List<CollectionEntity>>
    @Query("SELECT * FROM collection_members") fun observeMembers(): Flow<List<CollectionMemberEntity>>
    @Query("DELETE FROM collections") suspend fun clearCollections()
    @Query("DELETE FROM collection_members WHERE account NOT LIKE 'device:%'") suspend fun clearMembers()
    @Upsert suspend fun putCollections(items: List<CollectionEntity>)
    @Upsert suspend fun putMembers(items: List<CollectionMemberEntity>)
    @Query("SELECT * FROM batch_items ORDER BY createdAt, batchId, mediaId") fun observeBatches(): Flow<List<BatchItemEntity>>
    @Query("SELECT * FROM batch_items WHERE state IN ('WAITING', 'RUNNING') AND (:localOnly = 0 OR action = 'REMOVE_CACHE') ORDER BY createdAt, mediaId LIMIT :limit")
    suspend fun pendingBatches(limit: Int = Int.MAX_VALUE, localOnly: Boolean = false): List<BatchItemEntity>
    @Query("SELECT * FROM batch_items WHERE batchId = :id AND state = 'FAILED'") suspend fun failedBatch(id: String): List<BatchItemEntity>
    @Upsert suspend fun putBatches(items: List<BatchItemEntity>)
    @Query("UPDATE batch_items SET state = 'RUNNING', message = NULL WHERE batchId = :id AND mediaId = :media AND state IN ('WAITING', 'RUNNING')")
    suspend fun startBatchItem(id: String, media: String): Int
    @Query("UPDATE batch_items SET state = :state, message = :message WHERE batchId = :id AND mediaId = :media AND state IN ('WAITING', 'RUNNING')")
    suspend fun finishPendingBatch(id: String, media: String, state: String, message: String?)
    @Query("UPDATE batch_items SET state = 'CANCELED', message = NULL WHERE batchId = :id AND account = :account AND action IN ('KEEP_OFFLINE', 'SAVE_TO_PHONE') AND state IN ('WAITING', 'RUNNING')")
    suspend fun cancelDownloadBatch(id: String, account: String): Int
    @Query("UPDATE batch_items SET state = 'STOPPED', message = 'Transfer canceled' WHERE state IN ('WAITING', 'RUNNING')")
    suspend fun stopPendingBatches(): Int
    @Query("UPDATE batch_items SET state = 'FAILED', message = :message WHERE batchId = :id AND state = 'WAITING'")
    suspend fun failWaitingBatch(id: String, message: String)
    @Query("UPDATE batch_items SET state = :state, message = :message WHERE batchId = :batchId AND mediaId = :mediaId")
    suspend fun finishBatch(batchId: String, mediaId: String, state: String, message: String?)
    @Query("SELECT * FROM upload_checkpoints WHERE attemptId = :id") suspend fun checkpoint(id: String): UploadCheckpointEntity?
    @Upsert suspend fun putCheckpoint(value: UploadCheckpointEntity)
    @Query("DELETE FROM upload_checkpoints WHERE attemptId = :id") suspend fun deleteCheckpoint(id: String)
}

internal fun CollectionEntity.toModel() = dev.mela.engine.model.GalleryCollection(collectionId, name, parentId, isFolder, position,
    sharedGeneration?.let { dev.mela.engine.model.SharedAlbumInfo(
        dev.mela.engine.model.SharedAlbumGeneration.valueOf(it),
        sharedRole?.let(dev.mela.engine.model.SharedAlbumRole::valueOf) ?: dev.mela.engine.model.SharedAlbumRole.VIEWER,
        sharedOwnerName, sharedParticipantCount, sharedWebUrl) })
internal fun dev.mela.engine.model.GalleryCollection.toEntity(account: String) = CollectionEntity(account, id, name, parentId, isFolder, position,
    shared?.generation?.name, shared?.role?.name, shared?.ownerName, shared?.participantCount, shared?.webUrl)
