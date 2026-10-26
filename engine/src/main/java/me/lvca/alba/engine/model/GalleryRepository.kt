package me.lvca.alba.engine.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class RefreshSummary(
    val cloudItemCount: Int,
    val deviceItemCount: Int,
    val refreshedAtEpochMillis: Long,
    val sharedAlbumsUnavailable: Boolean = false,
)

/** Reading the catalog does not imply permission to edit or export originals. */
interface GalleryReader {
    suspend fun findMedia(id: String): GalleryMedia?
    fun observeGallery(): Flow<List<GalleryMedia>>
    fun observeGallery(query: GalleryQuery): Flow<List<GalleryMedia>> = observeGallery().map { query.apply(it) }
    fun observeCollections(): Flow<List<GalleryCollection>>
    suspend fun refreshDevice()
    suspend fun refresh(includeDeviceMedia: Boolean): RefreshSummary
    suspend fun clearCloudCatalog()
}

interface OriginalExporter {
    suspend fun exportOriginal(mediaId: String, output: java.io.OutputStream, motion: Boolean = false)
}

interface OfflineMediaStore {
    suspend fun keepOriginalOffline(mediaId: String)
    suspend fun removeCachedOriginal(mediaId: String)
}

interface GalleryMediaAccess : OriginalExporter, OfflineMediaStore {
    suspend fun localStorageUsage(): LocalMediaStorage
    suspend fun clearLocalMedia(category: LocalMediaCategory)
    suspend fun openPlayback(mediaId: String, position: Long, length: Long): me.lvca.alba.engine.source.MediaRead
    suspend fun ensureViewer(mediaId: String)
    suspend fun ensurePreview(mediaId: String)
}

interface GalleryEdits {
    suspend fun removeFromAlbum(id: String, mediaIds: List<String>)
    suspend fun setCloudTrashed(mediaIds: List<String>, trashed: Boolean)
    suspend fun setFavorite(mediaId: String, favorite: Boolean)

    /** Moves iCloud photos into or out of the Hidden album. */
    suspend fun setHidden(mediaIds: List<String>, hidden: Boolean)
    suspend fun createAlbum(name: String): GalleryCollection
    suspend fun deleteAlbum(id: String)
    suspend fun renameAlbum(id: String, name: String)
    suspend fun addToAlbum(id: String, mediaIds: List<String>)
}

/** All gallery capabilities are required here; partial consumers use the smaller interfaces above. */
interface GalleryRepository : GalleryReader, GalleryMediaAccess, GalleryEdits, SharedAlbumOperations {
    suspend fun contributeToSharedAlbum(id: String, mediaIds: List<String>, operationId: String)
    suspend fun createSharedAlbum(name: String, operationId: String, generation: SharedAlbumGeneration = SharedAlbumGeneration.MODERN): GalleryCollection
    suspend fun accountProfile(): CloudAccountProfile?
    suspend fun storageUsage(): CloudStorageUsage
    override suspend fun sharedActivity(id: String, rank: Int): SharedActivityPage
    override suspend fun sharedPostDiscussion(id: String): SharedDiscussion
    override suspend fun sharedPostPhotos(id: String): List<String>
    override suspend fun pendingSharedInvitations(): List<SharedInvitation>
    override suspend fun resolveSharedInvitation(url: String): SharedInvitation
    override suspend fun respondSharedInvitation(id: String, accept: Boolean, operationId: String)
    override suspend fun sharedManagement(id: String): SharedAlbumManagement
    override suspend fun sharedDiscussion(mediaId: String): SharedDiscussion
    override suspend fun changeSharedAlbum(id: String, command: SharedCommand, operationId: String)
}
