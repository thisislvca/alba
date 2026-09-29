package dev.mela.engine.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

data class RefreshSummary(
    val cloudItemCount: Int,
    val deviceItemCount: Int,
    val refreshedAtEpochMillis: Long,
    val sharedAlbumsUnavailable: Boolean = false,
)

interface GalleryRepository : SharedAlbumOperations {
    suspend fun contributeToSharedAlbum(id: String, mediaIds: List<String>, operationId: String): Unit = error("Shared contribution is unavailable")
    suspend fun createSharedAlbum(name: String, operationId: String, generation: dev.mela.engine.model.SharedAlbumGeneration = dev.mela.engine.model.SharedAlbumGeneration.MODERN): GalleryCollection = error("Shared album creation is unavailable")
    suspend fun localStorageUsage(): LocalMediaStorage = error("Phone storage information is unavailable")
    suspend fun clearLocalMedia(category: LocalMediaCategory): Unit = error("Phone storage cleanup is unavailable")
    suspend fun removeFromAlbum(id: String, mediaIds: List<String>): Unit = error("Album editing is unavailable")
    suspend fun setCloudTrashed(mediaIds: List<String>, trashed: Boolean): Unit = error("iCloud trash is unavailable")
    suspend fun setFavorite(mediaId: String, favorite: Boolean): Unit = error("Favorites are unavailable")
    suspend fun createAlbum(name: String): GalleryCollection = error("Album editing is unavailable")
    suspend fun deleteAlbum(id: String): Unit = error("Album editing is unavailable")
    suspend fun renameAlbum(id: String, name: String): Unit = error("Album editing is unavailable")
    suspend fun addToAlbum(id: String, mediaIds: List<String>): Unit = error("Album editing is unavailable")
    suspend fun accountProfile(): dev.mela.engine.model.CloudAccountProfile? = null
    suspend fun storageUsage(): CloudStorageUsage = error("Storage information is unavailable")
    suspend fun exportOriginal(mediaId: String, output: java.io.OutputStream, motion: Boolean = false): Unit = error("Export is unavailable")
    suspend fun findMedia(id: String): GalleryMedia? = observeGallery().first().firstOrNull { it.id == id }
    fun observeGallery(): Flow<List<GalleryMedia>>
    fun observeGallery(query: GalleryQuery): Flow<List<GalleryMedia>> =
        observeGallery().map { query.apply(it) }
    fun observeCollections(): Flow<List<GalleryCollection>> = kotlinx.coroutines.flow.flowOf(emptyList())
    suspend fun openPlayback(mediaId: String, position: Long, length: Long): dev.mela.engine.source.MediaRead =
        error("Playback is unavailable")

    suspend fun refreshDevice() { refresh(true) }

    suspend fun refresh(includeDeviceMedia: Boolean): RefreshSummary

    suspend fun ensureViewer(mediaId: String) { ensurePreview(mediaId) }

    suspend fun ensurePreview(mediaId: String)

    suspend fun keepOriginalOffline(mediaId: String)

    suspend fun removeCachedOriginal(mediaId: String)

    suspend fun clearCloudCatalog()
}
