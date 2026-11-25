package me.lvca.alba.engine.database

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The photos the offline thumbnail set is drawn from: the library as the grid shows it, without Hidden or shared albums. */
private const val LIBRARY_ROWS = """c.origin = 'ICLOUD' AND c.isTrashed = 0 AND c.isHidden = 0 AND c.mediaId NOT LIKE 'shared:%'"""
private const val NEWEST_FIRST = "ORDER BY c.capturedAtEpochMillis DESC, c.mediaId DESC"

/** The newest :setSize library photos, with their saved thumbnail. */
private const val OFFLINE_SET = """SELECT c.mediaId, c.capturedAtEpochMillis, cache.previewCachePath FROM catalog_items c
    LEFT JOIN media_cache cache ON cache.mediaId = c.mediaId AND cache.resourceFingerprint = c.resourceFingerprint
    WHERE $LIBRARY_ROWS $NEWEST_FIRST LIMIT :setSize"""

data class OfflineThumbnailCounts(val libraryCount: Int, val setSize: Int, val savedCount: Int)

/** The offline thumbnail set: the library's newest photos, as many as the thumbnail budget holds. */
@Dao
interface OfflineThumbnailDao {
    /** The library's newest [limit] photos by capture date: the offline set. */
    @Query("SELECT c.mediaId FROM catalog_items c WHERE $LIBRARY_ROWS $NEWEST_FIRST LIMIT :limit")
    suspend fun newestLibraryIds(limit: Int): List<String>

    /** Up to [limit] photos among the newest [setSize] without a saved thumbnail, newest first, leaving out [skip]. */
    @Query("""SELECT s.mediaId FROM ($OFFLINE_SET) s WHERE s.previewCachePath IS NULL AND s.mediaId NOT IN (:skip)
        ORDER BY s.capturedAtEpochMillis DESC, s.mediaId DESC LIMIT :limit""")
    suspend fun missingOfflineThumbnails(setSize: Int, skip: List<String>, limit: Int): List<String>

    @Query("""SELECT (SELECT COUNT(*) FROM catalog_items c WHERE $LIBRARY_ROWS) AS libraryCount,
        COUNT(*) AS setSize, COUNT(s.previewCachePath) AS savedCount FROM ($OFFLINE_SET) s""")
    fun observeOfflineThumbnails(setSize: Int): Flow<OfflineThumbnailCounts>
}
