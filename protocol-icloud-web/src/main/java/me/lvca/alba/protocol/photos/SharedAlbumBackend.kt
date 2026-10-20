package me.lvca.alba.protocol.photos

import me.lvca.alba.engine.model.SharedAlbumManagement
import me.lvca.alba.engine.model.SharedComment
import me.lvca.alba.engine.model.SharedCommand
import me.lvca.alba.engine.model.SharedDiscussion
import kotlinx.serialization.json.JsonObject

/**
 * One generation of shared albums: legacy shared streams or modern CloudKit shares. Callers check
 * permissions and journal the operation; a backend only reads and writes Apple's representation.
 */
internal interface SharedAlbumBackend {
    /** The albums of this generation that the signed-in account can see. */
    suspend fun discover(): List<SharedAlbumRecord>

    suspend fun readAlbum(album: SharedAlbumRecord): List<CloudPhotoAsset>

    suspend fun management(album: SharedAlbumRecord): SharedAlbumManagement

    /** Comments and reactions on [asset], or on the post it names when [post] is set. */
    suspend fun comments(album: SharedAlbumRecord, asset: String, post: Boolean, canManage: Boolean): List<SharedComment>

    /** Whether the signed-in person contributed [asset], read fresh from Apple. */
    suspend fun isOwnContribution(album: SharedAlbumRecord, asset: String): Boolean

    /** A fresh name for a new album's zone or stream GUID. */
    fun newZoneName(): String

    /**
     * Creates the album [zoneName] unless an earlier attempt already did, calling [markPhase] before
     * each write. Fails when Apple does not list the album yet.
     */
    suspend fun create(
        name: String,
        zoneName: String,
        markPhase: suspend (String) -> Unit,
        discover: suspend () -> List<SharedAlbumRecord>,
    ): SharedAlbumRecord

    suspend fun deleteComment(album: SharedAlbumRecord, asset: String, commentId: String)

    /**
     * Adds a comment or, with [reaction], sets the person's reaction, removing it when [value] is empty.
     * [markSending] journals the operation immediately before the write; it is not called when there
     * is nothing to change.
     */
    suspend fun writeComment(
        album: SharedAlbumRecord,
        asset: String,
        post: Boolean,
        reaction: Boolean,
        value: String,
        thread: SharedDiscussion,
        pending: JsonObject,
        markSending: suspend (JsonObject) -> Unit,
    )

    suspend fun removeMedia(album: SharedAlbumRecord, asset: String)

    /** Applies a sharing or album setting [command], calling [markSending] right before the write. */
    suspend fun changeSharing(
        album: SharedAlbumRecord,
        command: SharedCommand,
        pending: JsonObject,
        markSending: suspend () -> Unit,
    )

    /** Whether the comment journaled in [pending] reached Apple. */
    suspend fun commentConfirmed(album: SharedAlbumRecord, asset: String, post: Boolean, text: String, pending: JsonObject): Boolean
}
