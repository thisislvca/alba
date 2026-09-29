package dev.mela.engine.model

/** Shared copies have their own identity; they are never proof of a personal-library backup. */
enum class SharedAlbumGeneration { LEGACY, MODERN }
enum class SharedAlbumRole { OWNER, CONTRIBUTOR, VIEWER, COMMENTER, MANAGER }
data class SharedAlbumInfo(
    val generation: SharedAlbumGeneration,
    val role: SharedAlbumRole,
    val ownerName: String? = null,
    val participantCount: Int? = null,
    val webUrl: String? = null,
) {
    val canContribute: Boolean get() = role in setOf(SharedAlbumRole.OWNER, SharedAlbumRole.MANAGER, SharedAlbumRole.CONTRIBUTOR)
    val canManage: Boolean get() = role in setOf(SharedAlbumRole.OWNER, SharedAlbumRole.MANAGER)
    val canComment: Boolean get() = role != SharedAlbumRole.VIEWER || generation == SharedAlbumGeneration.LEGACY
}

data class SharedParticipant(val id: String, val name: String, val role: SharedAlbumRole,
    val pending: Boolean = false, val isCurrentUser: Boolean = false)
data class SharedAccessRequest(val id: String, val name: String)
data class SharedAlbumManagement(val album: GalleryCollection, val participants: List<SharedParticipant>,
    val publicAccess: Boolean = false, val publicUrl: String? = null, val allowContributions: Boolean = false,
    val allowAccessRequests: Boolean = false, val temporary: Boolean = false, val requests: List<SharedAccessRequest> = emptyList(), val invitationLinks: List<String> = emptyList(), val blocked: List<SharedAccessRequest> = emptyList())
data class SharedComment(val id: String, val author: String, val text: String,
    val timestamp: Long, val isMine: Boolean, val canDelete: Boolean, val reaction: Boolean = false)
data class SharedDiscussion(val comments: List<SharedComment>, val canComment: Boolean, val generation: SharedAlbumGeneration, val canRemovePhoto: Boolean = false)
data class SharedPost(val id: String, val author: String, val isMine: Boolean, val timestamp: Long, val caption: String)
data class SharedActivityPage(val posts: List<SharedPost>, val nextRank: Int?)

enum class SharedAction { POST_COMMENT, POST_REACTION, POST_DELETE_COMMENT, CREATE_INVITE_LINK, REVOKE_INVITE_LINKS, APPROVE_REQUEST, DENY_REQUEST, UNBLOCK, RENAME, INVITE, REMOVE_PARTICIPANT, ROLE, PUBLIC_ACCESS, CONTRIBUTIONS,
    ACCESS_REQUESTS, TEMPORARY, DELETE_ALBUM, LEAVE, REMOVE_MEDIA, COMMENT, DELETE_COMMENT, REACTION, SAVE_TO_LIBRARY, COVER }
/** IDs are scoped by the album; the protocol rechecks membership and permissions before every write. */
data class SharedCommand(val action: SharedAction, val subject: String = "", val value: String = "", val enabled: Boolean = false)

data class SharedInvitation(val id: String, val name: String, val generation: SharedAlbumGeneration, val canDecline: Boolean = false)

interface SharedAlbumOperations {
    suspend fun sharedActivity(id: String, rank: Int): SharedActivityPage = error("Album activity is unavailable")
    suspend fun sharedPostDiscussion(id: String): SharedDiscussion = error("Post discussion is unavailable")
    suspend fun sharedPostPhotos(id: String): List<String> = emptyList()
    suspend fun pendingSharedInvitations(): List<SharedInvitation> = emptyList()
    suspend fun resolveSharedInvitation(url: String): SharedInvitation = error("Invitation link unavailable")
    suspend fun respondSharedInvitation(id: String, accept: Boolean, operationId: String): Unit = error("Invitation unavailable")
    suspend fun sharedManagement(id: String): SharedAlbumManagement = error("Shared album management is unavailable")
    suspend fun sharedDiscussion(mediaId: String): SharedDiscussion = error("Shared comments are unavailable")
    suspend fun changeSharedAlbum(id: String, command: SharedCommand, operationId: String): Unit = error("Shared album editing is unavailable")
}

object SharedMediaIdentity {
    const val PREFIX = "shared:"
    fun isShared(id: String): Boolean = id.startsWith(PREFIX)
}
val GalleryMedia.isShared: Boolean get() = SharedMediaIdentity.isShared(id)

/** Legacy web uploads accept single files; never silently discard a Live Photo's motion pair. */
fun supportsLegacySharedUpload(kind: MediaKind, mimeType: String): Boolean =
    kind != MediaKind.LIVE_PHOTO && mimeType in setOf("image/jpeg", "image/png", "image/heic", "image/heif", "video/mp4", "video/quicktime")
