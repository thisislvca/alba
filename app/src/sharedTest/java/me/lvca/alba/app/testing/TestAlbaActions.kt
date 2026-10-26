package me.lvca.alba.app.testing

import me.lvca.alba.app.AlbaDependencies
import me.lvca.alba.app.di.AlbaGraph
import me.lvca.alba.app.messages.UndoAction
import me.lvca.alba.app.transfers.TransferScheduler
import me.lvca.alba.app.ui.AlbaActions
import me.lvca.alba.engine.companion.BatchAction
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.GalleryQuery
import me.lvca.alba.engine.model.LocalMediaCategory
import me.lvca.alba.engine.model.SharedActivityPage
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedAlbumManagement
import me.lvca.alba.engine.model.SharedCommand
import me.lvca.alba.engine.model.SharedDiscussion
import me.lvca.alba.engine.model.SharedInvitation
import me.lvca.alba.engine.model.SharedPostPreview
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.source.MediaRead

/**
 * The app's real collaborators for a ViewModel under test, minus the platform side effects a test must not trigger:
 * no network callback, and no transfer job scheduled with Android.
 */
internal fun testDependencies(graph: AlbaGraph): AlbaDependencies =
    AlbaDependencies.from(graph).copy(network = kotlinx.coroutines.flow.flowOf(null), transferScheduler = TransferScheduler {},
        sharedActivity = graph.sharedActivity.withoutScheduling())

/** Test fixtures explicitly opt into the callbacks they observe; every other action does nothing. Never used in production. */
internal fun testAlbaActions(
    sharedActivity: suspend (String, Int) -> SharedActivityPage = { _, _ -> error("Unavailable") },
    sharedPostDiscussion: suspend (String) -> SharedDiscussion = { error("Unavailable") },
    sharedPostPhotos: suspend (String) -> List<String> = { emptyList() },
    sharedPostPreviews: (suspend (List<String>) -> Map<String, SharedPostPreview>)? = null,
    setSharedActivityNotifications: (Boolean) -> Unit = {},
    pendingSharedInvitations: suspend () -> List<SharedInvitation> = { emptyList() },
    resolveSharedInvitation: suspend (String) -> SharedInvitation = { error("Unavailable") },
    respondSharedInvitation: suspend (String, Boolean) -> Unit = { _, _ -> error("Unavailable") },
    sharedManagement: suspend (String) -> SharedAlbumManagement = { error("Unavailable") },
    sharedDiscussion: suspend (String) -> SharedDiscussion = { error("Unavailable") },
    changeSharedAlbum: suspend (String, SharedCommand) -> Unit = { _, _ -> error("Unavailable") },
    clearLocalMedia: (LocalMediaCategory) -> Unit = {},
    checkConnection: () -> Unit = {},
    submitTwoFactor: (String) -> Unit = {},
    requestDevicePhotos: () -> Unit = {},
    pickUpload: () -> Unit = {},
    continuePastUnresolved: (TransferId) -> Unit = {},
    moveVerifiedPhotoToTrash: (String) -> Unit = {},
    openAccount: () -> Unit = {},
    startDemo: () -> Unit = {},
): AlbaActions {
    // Renamed so the overrides below cannot resolve to themselves.
    val onSharedActivity = sharedActivity; val onSharedPostDiscussion = sharedPostDiscussion; val onSharedPostPhotos = sharedPostPhotos
    val onSharedPostPreviews = sharedPostPreviews; val onSetNotifications = setSharedActivityNotifications
    val onPendingInvitations = pendingSharedInvitations; val onResolveInvitation = resolveSharedInvitation; val onRespondInvitation = respondSharedInvitation
    val onSharedManagement = sharedManagement; val onSharedDiscussion = sharedDiscussion; val onChangeSharedAlbum = changeSharedAlbum
    val onClearLocalMedia = clearLocalMedia; val onCheckConnection = checkConnection; val onSubmitTwoFactor = submitTwoFactor
    val onRequestDevicePhotos = requestDevicePhotos; val onPickUpload = pickUpload; val onContinue = continuePastUnresolved
    val onMoveVerifiedPhotoToTrash = moveVerifiedPhotoToTrash; val onOpenAccount = openAccount; val onStartDemo = startDemo
    return object : AlbaActions {
        override suspend fun sharedActivity(albumId: String, rank: Int) = onSharedActivity(albumId, rank)
        override suspend fun sharedPostDiscussion(postId: String) = onSharedPostDiscussion(postId)
        override suspend fun sharedPostPhotos(postId: String) = onSharedPostPhotos(postId)
        override suspend fun sharedPostPreviews(postIds: List<String>): Map<String, SharedPostPreview> =
            onSharedPostPreviews?.invoke(postIds) ?: super.sharedPostPreviews(postIds)
        override fun setSharedActivityNotifications(enabled: Boolean) = onSetNotifications(enabled)
        override suspend fun pendingSharedInvitations() = onPendingInvitations()
        override suspend fun resolveSharedInvitation(url: String) = onResolveInvitation(url)
        override suspend fun respondSharedInvitation(invitationId: String, accept: Boolean) = onRespondInvitation(invitationId, accept)
        override suspend fun sharedManagement(albumId: String) = onSharedManagement(albumId)
        override suspend fun sharedDiscussion(id: String) = onSharedDiscussion(id)
        override suspend fun changeSharedAlbum(albumId: String, command: SharedCommand) = onChangeSharedAlbum(albumId, command)
        override fun clearLocalMedia(category: LocalMediaCategory) = onClearLocalMedia(category)
        override fun checkConnection() = onCheckConnection()
        override fun submitTwoFactor(code: String) = onSubmitTwoFactor(code)
        override fun requestDevicePhotos() = onRequestDevicePhotos()
        override fun pickUpload() = onPickUpload()
        override fun continuePastUnresolved(transferId: TransferId) = onContinue(transferId)
        override fun demoBackupAction(transferId: TransferId) = onContinue(transferId)
        override fun moveVerifiedPhotoToTrash(mediaId: String) = onMoveVerifiedPhotoToTrash(mediaId)

        override fun dismissMessage() = Unit
        override fun undo(action: UndoAction) = Unit
        override fun changeQuery(query: GalleryQuery) = Unit
        override fun toggleSelection(mediaId: String) = Unit
        override fun selectItems(mediaIds: Set<String>, selected: Boolean) = Unit
        override fun selectAllShown() = Unit
        override fun clearSelection() = Unit
        override fun openMedia(mediaId: String) = Unit
        override fun requestPreview(mediaId: String) = Unit
        override fun prefetchPreviews(mediaIds: List<String>) = Unit
        override fun refresh() = Unit
        override fun openAccount() = onOpenAccount()
        override fun startDemo() = onStartDemo()
        override fun setFavorite(mediaId: String, favorite: Boolean) = Unit
        override fun setFavorites(mediaIds: List<String>, favorite: Boolean) = Unit
        override fun createAlbum(name: String) = Unit
        override fun createAlbumWithPhotos(name: String, mediaIds: List<String>) = Unit
        override fun renameAlbum(albumId: String, name: String) = Unit
        override fun deleteAlbum(albumId: String) = Unit
        override fun addToAlbum(albumId: String, mediaIds: List<String>) = Unit
        override fun confirmRemoveFromAlbum(albumId: String, mediaIds: List<String>) = Unit
        override fun confirmCloudTrash(mediaIds: List<String>, trashed: Boolean) = Unit
        override fun trashOnPhone(items: List<GalleryMedia>, restore: Boolean) = Unit
        override fun contributeToSharedAlbum(albumId: String, mediaIds: List<String>, caption: String) = Unit
        override fun createSharedAlbum(name: String, generation: SharedAlbumGeneration) = Unit
        override fun runBatch(action: BatchAction) = Unit
        override fun retryBatch(batchId: String) = Unit
        override fun cancelDownloadBatch(batchId: String) = Unit
        override fun requestUpload(mediaId: String) = Unit
        override fun share(items: List<GalleryMedia>) = Unit
        override fun cancelShare() = Unit
        override fun closeMedia() = Unit
        override suspend fun loadViewer(mediaId: String) = Unit
        override suspend fun openPlayback(mediaId: String, position: Long, length: Long): MediaRead = error("Unavailable")
        override fun edit(media: GalleryMedia) = Unit
        override fun saveToGallery(media: GalleryMedia) = Unit
        override fun exportOriginal(media: GalleryMedia) = Unit
        override fun exportLivePhoto(media: GalleryMedia) = Unit
        override fun keepOriginalOffline(mediaId: String) = Unit
        override fun removeCachedOriginal(mediaId: String) = Unit
        override fun cancelOfflineDownload() = Unit
        override fun closeAccount() = Unit
        override fun signIn(appleId: String, password: String) = Unit
        override fun resendTwoFactor() = Unit
        override fun signOut() = Unit
        override fun retryRestore() = Unit
        override fun enableAutomaticBackup() = Unit
        override fun disableAutomaticBackup() = Unit
        override fun setDiagnosticsEnabled(enabled: Boolean) = Unit
        override fun checkPhoneStorage() = Unit
    }
}
