package me.lvca.alba.app.sharing

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lvca.alba.app.R
import me.lvca.alba.app.actions.MediaAction
import me.lvca.alba.app.actions.MediaActionKey
import me.lvca.alba.app.actions.MediaActionRunner
import me.lvca.alba.app.diagnostics.AlbaDiagnostics
import me.lvca.alba.app.diagnostics.DiagnosticOperation
import me.lvca.alba.app.diagnostics.measure
import me.lvca.alba.app.gallery.GalleryBrowser
import me.lvca.alba.app.messages.UiMessage
import me.lvca.alba.app.messages.UserMessages
import me.lvca.alba.engine.model.GalleryQuery
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.SharedAction
import me.lvca.alba.engine.model.SharedActivityPage
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedAlbumManagement
import me.lvca.alba.engine.model.SharedCommand
import me.lvca.alba.engine.model.SharedDiscussion
import me.lvca.alba.engine.model.SharedInvitation
import me.lvca.alba.protocol.account.ICloudAccountManager
import me.lvca.alba.protocol.account.ICloudAccountState
import java.util.UUID

/**
 * iCloud shared albums: reading activity and discussions, invitations, and changes. Remote writes reuse an operation
 * id kept in saved state until they succeed, so a retry after process death cannot apply the same change twice.
 */
internal class SharedAlbumsController(
    private val savedState: SavedStateHandle,
    private val repository: GalleryRepository,
    private val accountManager: ICloudAccountManager,
    private val diagnostics: AlbaDiagnostics,
    private val runner: MediaActionRunner,
    private val messages: UserMessages,
    private val gallery: GalleryBrowser,
) {
    suspend fun sharedActivity(albumId: String, rank: Int): SharedActivityPage = io { repository.sharedActivity(albumId, rank) }
    suspend fun sharedPostDiscussion(postId: String): SharedDiscussion = io { repository.sharedPostDiscussion(postId) }
    suspend fun sharedPostPhotos(postId: String): List<String> = io { repository.sharedPostPhotos(postId) }
    suspend fun sharedManagement(albumId: String): SharedAlbumManagement = io { repository.sharedManagement(albumId) }
    suspend fun sharedDiscussion(id: String): SharedDiscussion = io { repository.sharedDiscussion(id) }
    suspend fun pendingSharedInvitations(): List<SharedInvitation> = io { repository.pendingSharedInvitations() }
    suspend fun resolveSharedInvitation(url: String): SharedInvitation = io { repository.resolveSharedInvitation(url) }

    suspend fun respondSharedInvitation(id: String, accept: Boolean) {
        diagnostics.measure(DiagnosticOperation.SHARED_ALBUM) {
            io {
                repository.respondSharedInvitation(id, accept, UUID.randomUUID().toString())
                repository.refresh(includeDeviceMedia = false)
            }
        }
    }

    suspend fun changeSharedAlbum(albumId: String, command: SharedCommand) {
        val key = "shared-action:" + albumId + ":" + command.hashCode()
        val operation = operationId(key)
        diagnostics.measure(DiagnosticOperation.SHARED_ALBUM) {
            io {
                repository.changeSharedAlbum(albumId, command, operation)
                repository.refresh(includeDeviceMedia = false)
            }
        }
        savedState.remove<String>(key)
        if (command.action in setOf(SharedAction.REMOVE_MEDIA, SharedAction.DELETE_ALBUM, SharedAction.LEAVE)) gallery.closeMedia()
        if (command.action in setOf(SharedAction.DELETE_ALBUM, SharedAction.LEAVE)) gallery.setQuery(GalleryQuery())
    }

    fun contributeToSharedAlbum(albumId: String, mediaIds: List<String>) {
        runner.start(MediaActionKey(MediaAction.SHARED_ALBUM), R.string.shared_contribute_failed) {
            val key = "shared-contribute:" + albumId + ":" + mediaIds.sorted().hashCode()
            repository.contributeToSharedAlbum(albumId, mediaIds, operationId(key))
            savedState.remove<String>(key)
            repository.refresh(includeDeviceMedia = false)
            gallery.clearSelection()
            messages.show(R.string.shared_contributed)
        }
    }

    fun createSharedAlbum(name: String, generation: SharedAlbumGeneration) {
        runner.start(MediaActionKey(MediaAction.SHARED_ALBUM), R.string.shared_create_failed) {
            val owner = (accountManager.state.value as? ICloudAccountState.SignedIn)?.appleId.orEmpty()
            val key = "shared-create:" + generation.name + ":" + owner + ":" + name.trim()
            val album = repository.createSharedAlbum(name, operationId(key), generation)
            savedState.remove<String>(key)
            gallery.setQuery(gallery.currentQuery().copy(collectionId = album.id))
            messages.show(UiMessage(R.string.album_created, listOf(album.name)))
        }
    }

    private fun operationId(key: String): String =
        savedState.get<String>(key) ?: UUID.randomUUID().toString().also { savedState[key] = it }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
