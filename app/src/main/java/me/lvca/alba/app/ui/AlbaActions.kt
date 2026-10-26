package me.lvca.alba.app.ui

import me.lvca.alba.app.messages.UndoAction
import me.lvca.alba.engine.companion.BatchAction
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.GalleryQuery
import me.lvca.alba.engine.model.LocalMediaCategory
import me.lvca.alba.engine.model.SHARED_POST_PREVIEW_PHOTOS
import me.lvca.alba.engine.model.SharedActivityPage
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedAlbumManagement
import me.lvca.alba.engine.model.SharedCommand
import me.lvca.alba.engine.model.SharedDiscussion
import me.lvca.alba.engine.model.SharedInvitation
import me.lvca.alba.engine.model.SharedPostPreview
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.source.MediaRead

/** Joining or declining shared albums. */
interface InvitationActions {
    suspend fun pendingSharedInvitations(): List<SharedInvitation>
    suspend fun resolveSharedInvitation(url: String): SharedInvitation
    suspend fun respondSharedInvitation(invitationId: String, accept: Boolean)

    /** The person has looked at their pending invitations, which clears the badge. */
    fun markInvitationsRead() {}
}

/** Reading and changing a shared album. */
interface SharedActions {
    suspend fun sharedActivity(albumId: String, rank: Int): SharedActivityPage
    suspend fun sharedPostDiscussion(postId: String): SharedDiscussion
    suspend fun sharedPostPhotos(postId: String): List<String>

    /** Previews of a page of posts in one read. */
    suspend fun sharedPostPreviews(postIds: List<String>): Map<String, SharedPostPreview> =
        postIds.associateWith { id -> sharedPostPhotos(id).let { SharedPostPreview(it.take(SHARED_POST_PREVIEW_PHOTOS), it.size) } }
    suspend fun sharedManagement(albumId: String): SharedAlbumManagement
    suspend fun sharedDiscussion(id: String): SharedDiscussion
    suspend fun changeSharedAlbum(albumId: String, command: SharedCommand)

    /** Turns the Shared Album activity notifications on or off. */
    fun setSharedActivityNotifications(enabled: Boolean) {}
}

/** Alba's own storage on this phone. */
interface StorageActions {
    fun checkPhoneStorage()
    fun clearLocalMedia(category: LocalMediaCategory)
}

/** Browsing the library: the query, the selection, previews and opening photos. */
interface BrowseActions {
    fun changeQuery(query: GalleryQuery)
    fun toggleSelection(mediaId: String)
    fun selectItems(mediaIds: Set<String>, selected: Boolean)
    fun selectAllShown()
    fun clearSelection()
    fun openMedia(mediaId: String)
    fun requestPreview(mediaId: String)
    fun prefetchPreviews(mediaIds: List<String>)
    fun refresh()
    fun requestDevicePhotos()
    fun openAccount()

    /** Switches an unconnected library to the sample library. */
    fun startDemo()
}

/** Changes to the user's library. Removals ask the user to confirm first. */
interface LibraryEditActions {
    fun setFavorite(mediaId: String, favorite: Boolean)
    fun setFavorites(mediaIds: List<String>, favorite: Boolean)
    fun createAlbum(name: String)
    fun createAlbumWithPhotos(name: String, mediaIds: List<String>)
    fun renameAlbum(albumId: String, name: String)
    fun deleteAlbum(albumId: String)
    fun addToAlbum(albumId: String, mediaIds: List<String>)
    fun confirmRemoveFromAlbum(albumId: String, mediaIds: List<String>)
    fun confirmCloudTrash(mediaIds: List<String>, trashed: Boolean)

    /** Asks Android to move phone copies to its trash, or to restore them from it. */
    fun trashOnPhone(items: List<GalleryMedia>, restore: Boolean)

    /** Posts [mediaIds] to a shared album, with [caption] where the album supports captions. */
    fun contributeToSharedAlbum(albumId: String, mediaIds: List<String>, caption: String = "")
    fun createSharedAlbum(name: String, generation: SharedAlbumGeneration)
}

/** Getting media in and out: batches, uploads and sharing. */
interface TransferActions {
    fun runBatch(action: BatchAction)
    fun retryBatch(batchId: String)
    fun cancelDownloadBatch(batchId: String)
    fun pickUpload()
    fun requestUpload(mediaId: String)
    fun continuePastUnresolved(transferId: TransferId)
    fun share(items: List<GalleryMedia>)
    fun cancelShare()
}

/** What the photo viewer does with the open photo. */
interface ViewerActions {
    fun closeMedia()
    suspend fun loadViewer(mediaId: String)
    suspend fun openPlayback(mediaId: String, position: Long, length: Long): MediaRead
    fun edit(media: GalleryMedia)
    fun saveToGallery(media: GalleryMedia)
    fun exportOriginal(media: GalleryMedia)
    fun exportLivePhoto(media: GalleryMedia)
    fun keepOriginalOffline(mediaId: String)
    fun removeCachedOriginal(mediaId: String)
    fun cancelOfflineDownload()
    fun moveVerifiedPhotoToTrash(mediaId: String)
}

/** The account screen: sign-in, connection, backup and diagnostics. */
interface AccountActions {
    fun closeAccount()
    fun signIn(appleId: String, password: String)
    fun submitTwoFactor(code: String)
    fun resendTwoFactor()
    fun signOut()
    fun checkConnection()
    fun retryRestore()
    fun enableAutomaticBackup()
    fun disableAutomaticBackup()

    /** Drives the demo backup walkthrough: interrupt, pause for Wi-Fi, clean up or retry a sample row. */
    fun demoBackupAction(transferId: TransferId)
    fun setDiagnosticsEnabled(enabled: Boolean)
}

/** Everything the gallery, its sheets and its dialogs can do. */
interface GalleryScreenActions : BrowseActions, LibraryEditActions, TransferActions, SharedActions, InvitationActions

/** Everything the photo viewer can do. */
interface MediaScreenActions : BrowseActions, LibraryEditActions, TransferActions, ViewerActions, SharedActions

/** Everything the account screen and its pages can do. */
interface AccountScreenActions : AccountActions, StorageActions

/** Every action Alba's screens can take. */
interface AlbaActions : GalleryScreenActions, MediaScreenActions, AccountScreenActions {
    fun dismissMessage()

    /** Reverses what an undoable message reported, from its Undo action. */
    fun undo(action: UndoAction)
}
