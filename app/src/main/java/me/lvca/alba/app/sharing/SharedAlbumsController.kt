package me.lvca.alba.app.sharing

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
import me.lvca.alba.engine.model.SharedPostPreview
import me.lvca.alba.engine.model.SharedUploadFile
import me.lvca.alba.protocol.account.ICloudAccountManager
import me.lvca.alba.protocol.account.ICloudAccountState
import java.util.UUID

/** What the shared-album screens show about new activity: unread albums, unseen invitations and the notification switch. */
data class SharedActivityState(
    val unreadAlbums: Set<String> = emptySet(),
    val unreadInvitations: Int = 0,
    val notificationsEnabled: Boolean = true,
)

/**
 * iCloud shared albums: reading activity and discussions, invitations, and changes. Remote writes reuse an operation
 * id kept in saved state until they succeed, so a retry after process death cannot apply the same change twice.
 * Also tracks what is new: an album open in the gallery is read, and invitations are rechecked after each refresh.
 */
internal class SharedAlbumsController(
    private val scope: CoroutineScope,
    private val savedState: SavedStateHandle,
    private val repository: GalleryRepository,
    private val accountManager: ICloudAccountManager,
    private val diagnostics: AlbaDiagnostics,
    private val runner: MediaActionRunner,
    private val messages: UserMessages,
    private val gallery: GalleryBrowser,
    private val ledger: SharedActivityStore,
) {
    // Recounted off the main thread, and only when the catalog itself changes, not on selection or query changes.
    private val marks: StateFlow<Map<String, SharedAlbumMark>> = gallery.state
        .map { it.collections to it.catalogItems }
        .distinctUntilChanged { old, new -> old.first === new.first && old.second === new.second }
        .map { (collections, items) -> SharedAlbumMark.of(collections, items) }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val activity: StateFlow<SharedActivityState> = combine(marks, ledger.state) { current, record ->
        SharedActivityState(record.unread(current), record.unreadInvitations.size, record.notificationsEnabled)
    }.stateIn(scope, SharingStarted.Eagerly, SharedActivityState())

    init {
        ledger.applySchedule()
        marks.onEach { current -> ledger.update { it.observe(current) } }.launchIn(scope)
        // An open shared album is read, including photos that arrive while it is open.
        combine(gallery.state.map { it.query.collectionId }.distinctUntilChanged(), marks) { open, current ->
            open?.let { id -> current[id]?.let { id to it } }
        }.filterNotNull()
            .onEach { (id, mark) -> ledger.update { it.markRead(id, mark) } }
            .launchIn(scope)
    }

    /** Rereads pending invitations for the badge, after a refresh. */
    fun checkInvitations() {
        if (accountManager.state.value !is ICloudAccountState.SignedIn && accountManager.state.value != ICloudAccountState.Demo) return
        // The badge keeps its last known count on failure; the invitations sheet shows errors.
        scope.launch { runCatching { pendingSharedInvitations() } }
    }

    fun markInvitationsRead() { ledger.update { it.markInvitationsRead() } }

    fun setNotifications(enabled: Boolean) = ledger.setNotifications(enabled)

    suspend fun sharedActivity(albumId: String, rank: Int): SharedActivityPage = io { repository.sharedActivity(albumId, rank) }
    suspend fun sharedPostDiscussion(postId: String): SharedDiscussion = io { repository.sharedPostDiscussion(postId) }
    suspend fun sharedPostPhotos(postId: String): List<String> = io { repository.sharedPostPhotos(postId) }
    suspend fun sharedPostPreviews(postIds: List<String>): Map<String, SharedPostPreview> = io { repository.sharedPostPreviews(postIds) }
    suspend fun sharedManagement(albumId: String): SharedAlbumManagement = io { repository.sharedManagement(albumId) }
    suspend fun sharedDiscussion(id: String): SharedDiscussion = io { repository.sharedDiscussion(id) }
    suspend fun pendingSharedInvitations(): List<SharedInvitation> = io { repository.pendingSharedInvitations() }
        .also { pending -> ledger.update { it.observeInvitations(pending.map(SharedInvitation::id).toSet()) } }
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

    fun contributeToSharedAlbum(albumId: String, mediaIds: List<String>, caption: String = "") {
        runner.start(MediaActionKey(MediaAction.SHARED_ALBUM), R.string.shared_contribute_failed) {
            val key = "shared-contribute:" + albumId + ":" + mediaIds.sorted().hashCode()
            repository.contributeToSharedAlbum(albumId, mediaIds, operationId(key), caption)
            savedState.remove<String>(key)
            refreshAfterOwnPost(albumId)
            gallery.clearSelection()
            messages.show(R.string.shared_contributed)
        }
    }

    /** Adds files another app shared with Alba to a shared album, reporting where they went. */
    fun contributeFilesToSharedAlbum(albumId: String, files: List<SharedUploadFile>, operationKey: String, caption: String, done: (Boolean) -> Unit) {
        runner.start(MediaActionKey(MediaAction.SHARED_ALBUM), R.string.shared_contribute_failed) {
            val key = "shared-files:$albumId:$operationKey"
            runCatching { repository.contributeFilesToSharedAlbum(albumId, files, operationId(key), caption) }
                .onFailure { done(false) }.getOrThrow()
            savedState.remove<String>(key)
            refreshAfterOwnPost(albumId)
            val name = gallery.current.collections.firstOrNull { it.id == albumId }?.name.orEmpty()
            messages.show(UiMessage(R.string.shared_added_to_album, listOf(name)))
            done(true)
        }
    }

    /** One's own photos are not news: the album stays read after the refresh that shows them. */
    private suspend fun refreshAfterOwnPost(albumId: String) {
        repository.refresh(includeDeviceMedia = false)
        val current = SharedAlbumMark.of(repository.observeCollections().first(), repository.observeGallery().first())
        current[albumId]?.let { mark -> ledger.update { it.markRead(albumId, mark) } }
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
