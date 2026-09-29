package dev.mela.protocol.photos

import dev.mela.engine.source.CloudCatalogPage
import dev.mela.engine.source.CloudCatalogSource
import dev.mela.protocol.account.AppleSessionSnapshot
import java.io.OutputStream

class AccountAwareICloudCatalogSource(
    private val demoSource: CloudCatalogSource,
    private val liveSource: CloudCatalogSource,
    private val sessionProvider: () -> AppleSessionSnapshot?,
) : CloudCatalogSource {
    override val writeAccountId: String? get() = currentSource().writeAccountId
    override val accountLabel: String
        get() = currentSource().accountLabel
    override suspend fun saveLegacySharedPhoto(mediaId: String, fileName: String, source: dev.mela.engine.model.OneShotUploadSource) = currentSource().saveLegacySharedPhoto(mediaId, fileName, source)
    override suspend fun sharedActivity(id: String, rank: Int) = currentSource().sharedActivity(id, rank)
    override suspend fun sharedPostDiscussion(id: String) = currentSource().sharedPostDiscussion(id)
    override suspend fun sharedPostPhotos(id: String) = currentSource().sharedPostPhotos(id)
    override suspend fun pendingSharedInvitations() = currentSource().pendingSharedInvitations()
    override suspend fun resolveSharedInvitation(url: String) = currentSource().resolveSharedInvitation(url)
    override suspend fun respondSharedInvitation(id: String, accept: Boolean, operationId: String) = currentSource().respondSharedInvitation(id, accept, operationId)
    override suspend fun sharedManagement(id: String) = currentSource().sharedManagement(id)
    override suspend fun sharedDiscussion(mediaId: String) = currentSource().sharedDiscussion(mediaId)
    override suspend fun changeSharedAlbum(id: String, command: dev.mela.engine.model.SharedCommand, operationId: String) = currentSource().changeSharedAlbum(id, command, operationId)
    override suspend fun contributeToSharedAlbum(id: String, mediaIds: List<String>, operationId: String) = currentSource().contributeToSharedAlbum(id, mediaIds, operationId)
    override suspend fun uploadSharedPhoto(albumId: String, fileName: String, source: dev.mela.engine.model.OneShotUploadSource, operationId: String) = currentSource().uploadSharedPhoto(albumId, fileName, source, operationId)
    override suspend fun sharedAlbums() = currentSource().sharedAlbums()
    override suspend fun createSharedAlbum(name: String, operationId: String, generation: dev.mela.engine.model.SharedAlbumGeneration) = currentSource().createSharedAlbum(name, operationId, generation)
    override suspend fun recentlyDeleted() = currentSource().recentlyDeleted()
    override suspend fun removeFromAlbum(id: String, mediaIds: List<String>) = currentSource().removeFromAlbum(id, mediaIds)
    override suspend fun setCloudTrashed(mediaIds: List<String>, trashed: Boolean) = currentSource().setCloudTrashed(mediaIds, trashed)
    override suspend fun setFavorite(mediaId: String, favorite: Boolean) = currentSource().setFavorite(mediaId, favorite)
    override suspend fun createAlbum(name: String) = currentSource().createAlbum(name)
    override suspend fun deleteAlbum(id: String) = currentSource().deleteAlbum(id)
    override suspend fun renameAlbum(id: String, name: String) = currentSource().renameAlbum(id, name)
    override suspend fun addToAlbum(id: String, mediaIds: List<String>) = currentSource().addToAlbum(id, mediaIds)
    override suspend fun accountProfile() = currentSource().accountProfile()
    override suspend fun storageUsage() = currentSource().storageUsage()
    override suspend fun writeMotion(mediaId: String, output: OutputStream) = sourceForMedia(mediaId).writeMotion(mediaId, output)
    override suspend fun captureSyncToken() = currentSource().captureSyncToken()
    override suspend fun changes(token: String, known: List<dev.mela.engine.source.RemoteMediaRecord>) = currentSource().changes(token, known)
    override suspend fun collections() = currentSource().collections()
    override suspend fun openPlayback(mediaId: String, position: Long, length: Long) = sourceForMedia(mediaId).openPlayback(mediaId, position, length)

    override suspend fun fetchPage(cursor: String?, limit: Int): CloudCatalogPage =
        currentSource().fetchPage(cursor, limit)

    override suspend fun writeDisplay(mediaId: String, output: OutputStream) = sourceForMedia(mediaId).writeDisplay(mediaId, output)

    override suspend fun writePreview(mediaId: String, output: OutputStream) {
        sourceForMedia(mediaId).writePreview(mediaId, output)
    }

    override suspend fun writeOriginal(mediaId: String, output: OutputStream) {
        sourceForMedia(mediaId).writeOriginal(mediaId, output)
    }

    private fun currentSource(): CloudCatalogSource = if (sessionProvider() == null) {
        demoSource
    } else {
        liveSource
    }

    private fun sourceForMedia(mediaId: String): CloudCatalogSource = if (mediaId.startsWith("fixture:") || mediaId.startsWith("shared:demo-library:")) {
        demoSource
    } else {
        liveSource
    }
}
