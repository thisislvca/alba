package me.lvca.alba.app.ui

import me.lvca.alba.engine.model.GalleryMedia

interface InvitationActions {
    val pendingSharedInvitations: suspend () -> List<me.lvca.alba.engine.model.SharedInvitation>
    val resolveSharedInvitation: suspend (String) -> me.lvca.alba.engine.model.SharedInvitation
    val respondSharedInvitation: suspend (String, Boolean) -> Unit
}

interface SharedActions {
    val sharedActivity: suspend (String, Int) -> me.lvca.alba.engine.model.SharedActivityPage
    val sharedPostDiscussion: suspend (String) -> me.lvca.alba.engine.model.SharedDiscussion
    val sharedPostPhotos: suspend (String) -> List<String>
    val sharedManagement: suspend (String) -> me.lvca.alba.engine.model.SharedAlbumManagement
    val sharedDiscussion: suspend (String) -> me.lvca.alba.engine.model.SharedDiscussion
    val changeSharedAlbum: suspend (String, me.lvca.alba.engine.model.SharedCommand) -> Unit
}

interface StorageActions {
    val checkPhoneStorage: () -> Unit
    val clearLocalMedia: (me.lvca.alba.engine.model.LocalMediaCategory) -> Unit
}

data class InvitationCallbacks(
    override val pendingSharedInvitations: suspend () -> List<me.lvca.alba.engine.model.SharedInvitation>,
    override val resolveSharedInvitation: suspend (String) -> me.lvca.alba.engine.model.SharedInvitation,
    override val respondSharedInvitation: suspend (String, Boolean) -> Unit,
) : InvitationActions

/** Production callers must wire every action explicitly. */
data class LibraryActions(
    override val sharedActivity: suspend (String, Int) -> me.lvca.alba.engine.model.SharedActivityPage,
    override val sharedPostDiscussion: suspend (String) -> me.lvca.alba.engine.model.SharedDiscussion,
    override val sharedPostPhotos: suspend (String) -> List<String>,
    override val pendingSharedInvitations: suspend () -> List<me.lvca.alba.engine.model.SharedInvitation>,
    override val resolveSharedInvitation: suspend (String) -> me.lvca.alba.engine.model.SharedInvitation,
    override val respondSharedInvitation: suspend (String, Boolean) -> Unit,
    override val sharedManagement: suspend (String) -> me.lvca.alba.engine.model.SharedAlbumManagement,
    override val sharedDiscussion: suspend (String) -> me.lvca.alba.engine.model.SharedDiscussion,
    override val changeSharedAlbum: suspend (String, me.lvca.alba.engine.model.SharedCommand) -> Unit,
    val prefetchPreviews: (List<String>) -> Unit,
    val loadViewer: suspend (String) -> Unit,
    val selectItems: (Set<String>, Boolean) -> Unit,
    val trash: (List<GalleryMedia>, Boolean) -> Unit,
    val edit: (GalleryMedia) -> Unit,
    val favorites: (List<String>, Boolean) -> Unit,
    val removeFromAlbum: (String, List<String>) -> Unit,
    val cloudTrash: (List<String>, Boolean) -> Unit,
    val favorite: (String, Boolean) -> Unit,
    val contributeToSharedAlbum: (String, List<String>) -> Unit,
    val createSharedAlbum: (String, me.lvca.alba.engine.model.SharedAlbumGeneration) -> Unit,
    val createAlbum: (String) -> Unit,
    val createAlbumWithPhotos: (String, List<String>) -> Unit,
    val deleteAlbum: (String) -> Unit,
    val cancelShare: () -> Unit,
    val share: (List<GalleryMedia>) -> Unit,
    val renameAlbum: (String, String) -> Unit,
    val addToAlbum: (String, List<String>) -> Unit,
    val saveToGallery: (GalleryMedia) -> Unit,
    val exportOriginal: (GalleryMedia) -> Unit,
    val exportLivePhoto: (GalleryMedia) -> Unit,
    val checkConnection: () -> Unit,
    val cancelDownloadBatch: (String) -> Unit,
    val cancelOfflineDownload: () -> Unit,
    override val checkPhoneStorage: () -> Unit,
    override val clearLocalMedia: (me.lvca.alba.engine.model.LocalMediaCategory) -> Unit,
) : InvitationActions, SharedActions, StorageActions
