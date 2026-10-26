package me.lvca.alba.engine.source

import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.MediaKind
import java.io.InputStream
import java.io.OutputStream

data class RemoteMediaRecord(
    val id: String,
    val fileName: String,
    val capturedAtEpochMillis: Long,
    val width: Int,
    val height: Int,
    val sourceRevision: String,
    val accentStartArgb: Long,
    val accentEndArgb: Long,
    val kind: MediaKind = MediaKind.PHOTO,
    val mimeType: String = "image/jpeg",
    val durationMillis: Long? = null,
    val masterRecordName: String? = null,
    val assetRecordName: String? = null,
    val resourceFingerprint: String = sourceRevision,
    val motionMimeType: String = "video/quicktime",
    val addedAtEpochMillis: Long? = null,
    val isTrashed: Boolean = false,
    val byteCount: Long? = null,
    val isHidden: Boolean = false,
    val details: me.lvca.alba.engine.model.MediaDetails = me.lvca.alba.engine.model.MediaDetails(),
)

data class CloudChanges(val upserts: List<RemoteMediaRecord>, val deletedRecordNames: Set<String>,
    val nextToken: String, val moreComing: Boolean)
data class CollectionSnapshot(val collections: List<GalleryCollection>, val members: Map<String, Set<String>>)
data class SharedCatalogSnapshot(val records: List<RemoteMediaRecord>, val collections: CollectionSnapshot)
class CatalogResetRequired : Exception("The library needs a fresh snapshot")
interface MediaRead : java.io.Closeable {
    val input: InputStream
    val length: Long
}

data class CloudCatalogPage(
    val records: List<RemoteMediaRecord>,
    val nextCursor: String?,
    val changeToken: String,
)

/** iCloud's Hidden album, which the main library feed leaves out. */
interface HiddenAlbumSource {
    /** The Hidden album's assets, each with [RemoteMediaRecord.isHidden] set. */
    suspend fun hiddenMedia(): List<RemoteMediaRecord> = emptyList()
    suspend fun setHidden(mediaIds: List<String>, hidden: Boolean): Unit = error("Hiding photos is unavailable")
}

interface CloudCatalogSource : me.lvca.alba.engine.model.SharedAlbumOperations, HiddenAlbumSource {
    suspend fun saveLegacySharedPhoto(mediaId: String, fileName: String, source: me.lvca.alba.engine.model.OneShotUploadSource): Unit = error("Shared save is unavailable")
    suspend fun uploadSharedPhoto(albumId: String, fileName: String, source: me.lvca.alba.engine.model.OneShotUploadSource,
        operationId: String): Unit = error("Shared upload is unavailable")
    suspend fun sharedAlbums(): SharedCatalogSnapshot = SharedCatalogSnapshot(emptyList(), CollectionSnapshot(emptyList(), emptyMap()))
    suspend fun contributeToSharedAlbum(id: String, mediaIds: List<String>, operationId: String): Unit = error("Shared contribution is unavailable")
    suspend fun createSharedAlbum(name: String, operationId: String, generation: me.lvca.alba.engine.model.SharedAlbumGeneration = me.lvca.alba.engine.model.SharedAlbumGeneration.MODERN): GalleryCollection = error("Shared album creation is unavailable")
    suspend fun removeFromAlbum(id: String, mediaIds: List<String>): Unit = error("Album editing is unavailable")
    suspend fun setCloudTrashed(mediaIds: List<String>, trashed: Boolean): Unit = error("iCloud trash is unavailable")
    suspend fun setFavorite(mediaId: String, favorite: Boolean): Unit = error("Favorites are unavailable")
    suspend fun createAlbum(name: String): me.lvca.alba.engine.model.GalleryCollection = error("Album editing is unavailable")
    suspend fun deleteAlbum(id: String): Unit = error("Album editing is unavailable")
    suspend fun renameAlbum(id: String, name: String): Unit = error("Album editing is unavailable")
    suspend fun addToAlbum(id: String, mediaIds: List<String>): Unit = error("Album editing is unavailable")
    suspend fun recentlyDeleted(): List<RemoteMediaRecord> = emptyList()
    suspend fun accountProfile(): me.lvca.alba.engine.model.CloudAccountProfile? = null
    suspend fun storageUsage(): me.lvca.alba.engine.model.CloudStorageUsage = error("Storage information is unavailable")
    suspend fun writeMotion(mediaId: String, output: OutputStream): Unit = error("Live Photo motion is unavailable")
    val writeAccountId: String? get() = null
    val accountLabel: String

    suspend fun fetchPage(cursor: String?, limit: Int): CloudCatalogPage

    suspend fun captureSyncToken(): String? = null
    suspend fun changes(token: String, known: List<RemoteMediaRecord>): CloudChanges = throw CatalogResetRequired()
    suspend fun collections(): CollectionSnapshot = CollectionSnapshot(emptyList(), emptyMap())
    suspend fun openPlayback(mediaId: String, position: Long, length: Long): MediaRead =
        error("Playback is unavailable for this source")

    suspend fun writeDisplay(mediaId: String, output: OutputStream) = writeOriginal(mediaId, output)

    suspend fun writePreview(mediaId: String, output: OutputStream)

    suspend fun writeOriginal(mediaId: String, output: OutputStream)
}
