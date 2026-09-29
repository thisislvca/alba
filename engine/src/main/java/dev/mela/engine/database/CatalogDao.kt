package dev.mela.engine.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class CatalogCacheState(
    val mediaId: String,
    val previewCachePath: String?,
    val originalCachePath: String?,
)

data class DisplayLink(val cloudId: String, val deviceId: String, val accountId: String)

private const val CATALOG_WITH_CACHE = """
SELECT c.mediaId,
    c.fileName,
    c.capturedAtEpochMillis,
    c.width,
    c.height,
    c.origin,
    c.sourceRevision,
    c.localUri,
    cache.previewCachePath,
    cache.originalCachePath,
    c.accentStartArgb,
    c.accentEndArgb,
    c.lastSeenScanId,
    c.kind,
    c.mimeType,
    c.durationMillis,
    cache.motionCachePath,
    c.motionMimeType,
    c.masterRecordName,
    c.assetRecordName,
    c.resourceFingerprint,
    c.addedAtEpochMillis,
    cache.viewerCachePath,
    c.byteCount,
    c.deviceFolderId,
    c.deviceFolderName,
    c.localFavorite,
    c.isTrashed,
    c.expiresAtEpochMillis
FROM catalog_items c
LEFT JOIN media_cache cache ON cache.mediaId = c.mediaId AND cache.resourceFingerprint = c.resourceFingerprint
"""

@Dao
interface CatalogDao {
    @Query("SELECT * FROM catalog_items WHERE origin = 'DEVICE' AND localFavorite = 1")
    suspend fun localFavorites(): List<CatalogItemEntity>

    @Query("""
        SELECT DISTINCT c.mediaId AS cloudId, d.mediaId AS deviceId, p.accountId AS accountId
        FROM verification_proofs p JOIN asset_links l ON l.linkId = p.linkId
        JOIN remote_asset_pairs r ON r.remotePairId = p.remotePairId
        JOIN accounts a ON a.accountId = p.accountId AND a.active = 1
        JOIN catalog_items d ON d.mediaId = p.mediaId AND d.origin = 'DEVICE'
        JOIN catalog_items c ON c.masterRecordName = r.masterRecordName AND c.assetRecordName = r.assetRecordName AND c.origin = 'ICLOUD'
        WHERE p.invalidatedAtEpochMillis IS NULL AND l.status = 'CURRENT' AND l.accountId = p.accountId
        AND r.accountId = p.accountId AND d.sourceRevision = p.sourceRevision AND d.isTrashed = 0
        AND c.mediaId NOT LIKE 'shared:%' AND r.databaseScope = 'private' AND r.zoneName = 'PrimarySync'
        AND c.isTrashed = 0 AND c.sourceRevision = p.masterChangeTag || ':' || p.assetChangeTag
        AND r.masterDeleted = 0 AND r.assetDeleted = 0 AND r.relationIsCurrent = 1
    """)
    fun observeDisplayLinks(): Flow<List<DisplayLink>>

    @Query("UPDATE catalog_items SET localFavorite = :favorite WHERE mediaId = :id AND origin = 'DEVICE'")
    suspend fun setLocalFavorite(id: String, favorite: Boolean)
    @Query("UPDATE media_cache SET viewerCachePath = :path WHERE mediaId = :id")
    suspend fun setViewerPath(id: String, path: String)
    @Query("UPDATE media_cache SET viewerCachePath = NULL WHERE viewerCachePath IN (:paths)")
    suspend fun clearViewerPaths(paths: List<String>)
    @Query("""UPDATE media_cache SET
        originalCachePath = CASE WHEN originalCachePath IN (:paths) THEN NULL ELSE originalCachePath END,
        motionCachePath = CASE WHEN motionCachePath IN (:paths) THEN NULL ELSE motionCachePath END""")
    suspend fun clearOriginalPaths(paths: List<String>)

    @Query(CATALOG_WITH_CACHE + " WHERE c.origin = 'ICLOUD'") suspend fun cloudItems(): List<CatalogItemEntity>
    @Query("SELECT * FROM catalog_checkpoints WHERE scope = :scope") suspend fun checkpoint(scope: String): CatalogCheckpointEntity?
    @Query(
        """
        SELECT * FROM catalog_items
        ORDER BY capturedAtEpochMillis DESC, mediaId DESC
        """,
    )
    fun observeAll(): Flow<List<CatalogItemEntity>>

    @Query(CATALOG_WITH_CACHE + " WHERE c.mediaId IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<CatalogItemEntity>

    @Query(CATALOG_WITH_CACHE + " WHERE c.mediaId = :mediaId")
    suspend fun findById(mediaId: String): CatalogItemEntity?

    @Query(
        """
        SELECT c.mediaId, cache.previewCachePath, cache.originalCachePath
        FROM catalog_items c LEFT JOIN media_cache cache ON cache.mediaId = c.mediaId
        WHERE c.origin = :origin
        """,
    )
    suspend fun findCacheStateByOrigin(origin: String): List<CatalogCacheState>

    @Query("SELECT * FROM media_cache")
    fun observeCache(): Flow<List<MediaCacheEntity>>

    @Upsert
    suspend fun upsertCache(items: List<MediaCacheEntity>)

    @Upsert
    suspend fun upsertMetadata(items: List<CatalogItemEntity>)

    @Transaction
    suspend fun upsertItems(items: List<CatalogItemEntity>) {
        // Keep the legacy nullable columns empty. Paths now live only in media_cache.
        upsertMetadata(items.map { it.copy(previewCachePath = null, viewerCachePath = null,
            originalCachePath = null, motionCachePath = null) })
        upsertCache(items.map { MediaCacheEntity(it.mediaId, it.previewCachePath, it.viewerCachePath,
            it.originalCachePath, it.motionCachePath, it.resourceFingerprint) })
    }

    @Query("DELETE FROM catalog_items WHERE origin = :origin AND lastSeenScanId != :scanId")
    suspend fun deleteMissing(origin: String, scanId: String)

    @Query("DELETE FROM catalog_items WHERE origin = :origin")
    suspend fun deleteByOrigin(origin: String)

    @Query("DELETE FROM catalog_checkpoints WHERE scope LIKE :scopePattern")
    suspend fun deleteCheckpoints(scopePattern: String)

    @Query("UPDATE media_cache SET previewCachePath = :path WHERE mediaId = :mediaId")
    suspend fun setPreviewPath(mediaId: String, path: String)

    @Query("UPDATE media_cache SET previewCachePath = NULL WHERE previewCachePath IN (:paths)")
    suspend fun clearPreviewPaths(paths: List<String>): Int

    @Query("UPDATE media_cache SET originalCachePath = :path WHERE mediaId = :mediaId")
    suspend fun setOriginalPath(mediaId: String, path: String?)

    @Query("UPDATE media_cache SET motionCachePath = :path WHERE mediaId = :mediaId")
    suspend fun setMotionPath(mediaId: String, path: String?)

    @Upsert
    suspend fun upsertCheckpoint(checkpoint: CatalogCheckpointEntity)
}
