package me.lvca.alba.app.ui

import me.lvca.alba.app.account.SignInDraft
import me.lvca.alba.app.messages.UndoAction
import me.lvca.alba.engine.companion.BatchAction
import me.lvca.alba.engine.model.BackupSettings
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.GalleryQuery
import me.lvca.alba.engine.model.LocalMediaCategory
import me.lvca.alba.engine.model.SharedActivityPage
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedAlbumManagement
import me.lvca.alba.engine.model.SharedCommand
import me.lvca.alba.engine.model.SharedDiscussion
import me.lvca.alba.engine.model.SharedInvitation
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.source.MediaRead

/** Joining or declining shared albums. */
interface InvitationActions {
    suspend fun pendingSharedInvitations(): List<SharedInvitation>
    suspend fun resolveSharedInvitation(url: String): SharedInvitation
    suspend fun respondSharedInvitation(invitationId: String, accept: Boolean)
}

/** Reading and changing a shared album. */
interface SharedActions {
    suspend fun sharedActivity(albumId: String, rank: Int): SharedActivityPage
    suspend fun sharedPostDiscussion(postId: String): SharedDiscussion
    suspend fun sharedPostPhotos(postId: String): List<String>
    suspend fun sharedManagement(albumId: String): SharedAlbumManagement
    suspend fun sharedDiscussion(id: String): SharedDiscussion
    suspend fun changeSharedAlbum(albumId: String, command: SharedCommand)
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

    /** Asks the user to unlock with their fingerprint, face or screen lock, then opens the Hidden album. */
    fun openHidden()

    /** Shows every photo of a burst in place of its key photo, or stacks it again. */
    fun expandBurst(burstId: String, expanded: Boolean)

    /** Opens the Backup page: status, the queue, failed items with Retry, and settings. */
    fun openBackup()

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

    /** Moves iCloud photos into or out of the Hidden album, with Undo. */
    fun setHidden(mediaIds: List<String>, hidden: Boolean)

    /** One confirmation for a selection of phone and iCloud items: each goes to its own trash. */
    fun confirmMixedTrash(items: List<GalleryMedia>)

    /** Asks Android to move phone copies to its trash, or to restore them from it. */
    fun trashOnPhone(items: List<GalleryMedia>, restore: Boolean)
    fun contributeToSharedAlbum(albumId: String, mediaIds: List<String>)
    fun createSharedAlbum(name: String, generation: SharedAlbumGeneration)
}

/** The upload queue and "Free up space", shared by the gallery, the viewer and the Backup page. */
interface QueueActions {
    fun continuePastUnresolved(transferId: TransferId)

    /** Stages again an item that sent nothing to Apple. */
    fun retryTransfer(transferId: TransferId)
    fun retryAllTransfers()

    /** Stops the user's uploads. */
    fun cancelUploads()

    /** Re-checks backed-up phone items as a batch, then asks Android to move them to its trash. */
    fun freeUpSpace(mediaIds: List<String>)
}

/** Getting media in and out: batches, uploads and sharing. */
interface TransferActions : QueueActions {
    fun runBatch(action: BatchAction)
    fun retryBatch(batchId: String)
    fun cancelDownloadBatch(batchId: String)
    fun pickUpload()
    fun requestUpload(mediaId: String)
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

    /** Shares a Live Photo's motion clip as a video. */
    fun shareLiveVideo(media: GalleryMedia)

    /** Saves a Live Photo's motion clip to the phone gallery as a video. */
    fun saveLiveVideo(media: GalleryMedia)
    fun keepOriginalOffline(mediaId: String)
    fun removeCachedOriginal(mediaId: String)
    fun cancelOfflineDownload()
    fun moveVerifiedPhotoToTrash(mediaId: String)

    /** Hands a copy of the original to another app (wallpaper, contact photo, viewer, editor) or to printing. */
    fun useElsewhere(media: GalleryMedia, use: ExternalUse)
}

/** What another app or the system does with a copy of a photo or video from the viewer. */
enum class ExternalUse {
    /** ACTION_ATTACH_DATA: wallpaper, contact photo and similar. */
    USE_AS,

    /** ACTION_VIEW in a chooser. */
    OPEN_WITH,

    /** ACTION_EDIT in a chooser. */
    EDIT_IN,

    /** Android's print framework, for photos. */
    PRINT,
}

/** The account screen: sign-in, connection, backup and diagnostics. */
interface AccountActions {
    /** The password being typed, kept in memory across rotation but never in saved state. */
    val signInDraft: SignInDraft

    fun closeAccount()
    fun signIn(appleId: String, password: String)
    fun submitTwoFactor(code: String)
    fun resendTwoFactor()

    /** Asks Apple to text the verification code to the trusted phone number instead. */
    fun sendCodeBySms()

    /** The user edited the sign-in or code form, so its error no longer applies. */
    fun clearSignInProblem()
    fun signOut()
    fun checkConnection()
    fun retryRestore()
    fun enableAutomaticBackup()
    fun disableAutomaticBackup()

    /** The account screen showed a page something outside it asked for. */
    fun accountPageShown()
    fun setBackupSettings(settings: BackupSettings)
    fun loadDeviceFolders()
    fun refreshBackupStatus()
    fun loadFreeUpSpace()

    /** The user says iCloud has space again. */
    fun retryAfterStorageFull()

    /** Drives the demo backup walkthrough: interrupt, pause for Wi-Fi, clean up or retry a sample row. */
    fun demoBackupAction(transferId: TransferId)
    fun setDiagnosticsEnabled(enabled: Boolean)
}

/** Everything the gallery, its sheets and its dialogs can do. */
interface GalleryScreenActions : BrowseActions, LibraryEditActions, TransferActions, SharedActions, InvitationActions

/** Everything the photo viewer can do. */
interface MediaScreenActions : BrowseActions, LibraryEditActions, TransferActions, ViewerActions, SharedActions

/** Everything the account screen and its pages can do. */
interface AccountScreenActions : AccountActions, StorageActions, QueueActions

/** Every action Alba's screens can take. */
interface AlbaActions : GalleryScreenActions, MediaScreenActions, AccountScreenActions {
    fun dismissMessage()

    /** Reverses what an undoable message reported, from its Undo action. */
    fun undo(action: UndoAction)
}
