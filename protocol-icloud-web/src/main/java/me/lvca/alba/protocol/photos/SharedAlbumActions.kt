package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.lvca.alba.engine.model.SharedAction
import me.lvca.alba.engine.model.SharedAlbumRole
import me.lvca.alba.engine.model.SharedCommand
import me.lvca.alba.protocol.photos.SharedAlbumJournal.Companion.data
import me.lvca.alba.protocol.text
import java.util.UUID

/**
 * Applies a [SharedCommand] once. Each change is journaled before it is sent, and a change whose
 * response was lost is confirmed by reading Apple's state, never by sending it again.
 */
internal class SharedAlbumActions(
    private val ids: SharedAlbumIds,
    private val journal: SharedAlbumJournal,
    private val account: () -> String,
    private val authorized: () -> Boolean,
    private val discover: suspend () -> List<SharedAlbumRecord>,
    private val backend: (SharedAlbumRecord) -> SharedAlbumBackend,
    private val modern: ModernSharedAlbums,
    private val discussions: SharedAlbumDiscussions,
    private val transfers: SharedAlbumTransfers,
) {
    suspend fun change(id: String, command: SharedCommand, operationId: String) {
        UUID.fromString(operationId)
        require(id.startsWith("shared:${account()}:"))
        check(authorized()) { "Sign in before changing shared albums." }
        val key = SharedAlbumJournal.key("shared-action",
            "${account()}|$id|${command.action}|${command.subject}|${command.value}|${command.enabled}")
        val previous = journal.load(key)
        if (previous?.phase == DONE && previous.data().text("operationId") == operationId) return
        val found = discover().firstOrNull { it.collection.id == id }
        if (found == null && previous?.phase == SENDING && command.action in REMOVES_ALBUM) {
            journal.save(key, DONE, previous.data())
            return
        }
        val album = requireNotNull(found) { "Shared album is no longer available." }
        val info = requireNotNull(album.collection.shared)
        // Stable record names reconcile modern comments after a lost response. Other uncertain writes
        // require a fresh read and explicit confirmation; never blindly repeat an invitation or delete.
        if (previous?.phase == SENDING) {
            val pending = previous.data()
            if (reconciled(album, command, pending)) {
                journal.save(key, DONE, pending)
                return
            }
            error("The last change is unconfirmed. Refresh and check iCloud before trying again.")
        }
        val pending = buildJsonObject {
            put("operationId", operationId); put("recordName", UUID.randomUUID().toString()); put("timestamp", System.currentTimeMillis())
        }
        val backend = backend(album)
        when (command.action) {
            in COMMENT_ACTIONS -> {
                val post = command.action in POST_ACTIONS
                val asset = if (post) postRecordName(album, command.subject).also { modern.requirePost(album, it) }
                    else ids.recordName(album, command.subject)
                val thread = discussions.discussion(album, asset, post)
                val deleting = command.action in setOf(SharedAction.DELETE_COMMENT, SharedAction.POST_DELETE_COMMENT)
                val reaction = command.action in setOf(SharedAction.REACTION, SharedAction.POST_REACTION)
                require(thread.canComment || deleting)
                if (!deleting && !reaction) require(command.value.isNotBlank() && command.value.length <= 1000)
                if (deleting) {
                    require(thread.comments.any { it.id == command.value && it.canDelete }) { "You cannot delete this comment." }
                    journal.save(key, SENDING, pending)
                    backend.deleteComment(album, asset, command.value)
                } else {
                    backend.writeComment(album, asset, post, reaction, command.value, thread, pending) { journal.save(key, SENDING, it) }
                }
            }
            SharedAction.REMOVE_MEDIA -> {
                val asset = ids.recordName(album, command.subject)
                require(backend.readAlbum(album).any { it.assetRecordName == asset })
                require(discussions.canRemove(album, asset)) { "You can only remove your own contributions." }
                journal.save(key, SENDING, pending)
                backend.removeMedia(album, asset)
            }
            SharedAction.SAVE_TO_LIBRARY -> {
                require(!album.isLegacy)
                transfers.copyToLibrary(album, ids.recordName(album, command.subject), key, pending)
                return
            }
            else -> {
                if (command.action == SharedAction.LEAVE) require(info.role != SharedAlbumRole.OWNER)
                else require(info.canManage) { "Only an album manager can change sharing." }
                if (command.action == SharedAction.DELETE_ALBUM) require(info.role == SharedAlbumRole.OWNER)
                if (command.action == SharedAction.RENAME) require(command.value.isNotBlank() && command.value.length <= 255)
                if (command.action == SharedAction.INVITE) require(command.value.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) { "Enter an email address." }
                backend.changeSharing(album, command, pending) { journal.save(key, SENDING, pending) }
            }
        }
        if (command.action == SharedAction.CREATE_INVITE_LINK) {
            check(reconciled(album, command, pending)) { "Apple has not returned the invitation link yet. Reload to check it." }
        }
        journal.save(key, DONE, pending)
    }

    /** Whether Apple's current state shows that [command], journaled as [pending], took effect. */
    private suspend fun reconciled(album: SharedAlbumRecord, command: SharedCommand, pending: JsonObject): Boolean {
        val backend = backend(album)
        suspend fun management() = backend.management(album)
        suspend fun ownReactionMatches(asset: String, post: Boolean) =
            discussions.discussion(album, asset, post).comments.filter { it.reaction && it.isMine }.let { own ->
                if (command.value.isEmpty()) own.isEmpty() else own.any { it.text == command.value }
            }
        return when (command.action) {
            SharedAction.RENAME -> album.collection.name == command.value
            SharedAction.PUBLIC_ACCESS -> management().publicAccess == command.enabled
            SharedAction.CONTRIBUTIONS -> management().allowContributions == command.enabled
            SharedAction.ACCESS_REQUESTS -> management().allowAccessRequests == command.enabled
            SharedAction.TEMPORARY -> management().temporary == command.enabled
            SharedAction.REVOKE_INVITE_LINKS -> management().invitationLinks.isEmpty()
            SharedAction.CREATE_INVITE_LINK -> modern.invitationLinkIssued(album, pending)
            SharedAction.APPROVE_REQUEST, SharedAction.INVITE -> management().participants.any { it.id == pending.text("recordName") }
            SharedAction.DENY_REQUEST -> management().let { state ->
                state.requests.none { it.id == command.subject } && state.blocked.any { it.id == command.subject }
            }
            SharedAction.UNBLOCK -> management().blocked.none { it.id == command.subject }
            // One share update removes the person and blocks them, so their absence confirms both.
            SharedAction.BLOCK -> management().participants.none { it.id == command.subject }
            SharedAction.REMOVE_PARTICIPANT -> management().participants.none { it.id == command.subject }
            SharedAction.ROLE -> management().participants.any {
                it.id in setOf(command.subject, pending.text("recordName")) && it.role == when (command.value) {
                    "manager" -> SharedAlbumRole.MANAGER
                    "commenter" -> SharedAlbumRole.COMMENTER
                    else -> SharedAlbumRole.CONTRIBUTOR
                }
            }
            SharedAction.REMOVE_MEDIA -> backend.readAlbum(album).none { it.assetRecordName == ids.recordName(album, command.subject) }
            SharedAction.POST_DELETE_COMMENT ->
                discussions.discussion(album, postRecordName(album, command.subject), true).comments.none { it.id == command.value }
            SharedAction.DELETE_COMMENT ->
                discussions.discussion(album, ids.recordName(album, command.subject)).comments.none { it.id == command.value }
            SharedAction.POST_REACTION -> ownReactionMatches(postRecordName(album, command.subject), post = true)
            SharedAction.REACTION -> ownReactionMatches(ids.recordName(album, command.subject), post = false)
            SharedAction.POST_COMMENT ->
                backend.commentConfirmed(album, postRecordName(album, command.subject), post = true, command.value, pending)
            SharedAction.COMMENT ->
                backend.commentConfirmed(album, ids.recordName(album, command.subject), post = false, command.value, pending)
            SharedAction.COVER -> modern.coverAsset(album) == ids.recordName(album, command.subject)
            else -> false
        }
    }

    private fun postRecordName(album: SharedAlbumRecord, postId: String) = ids.postRecordName(album, postId)

    private companion object {
        const val SENDING = "SENDING"
        const val DONE = "DONE"
        val REMOVES_ALBUM = setOf(SharedAction.DELETE_ALBUM, SharedAction.LEAVE)
        val POST_ACTIONS = setOf(SharedAction.POST_COMMENT, SharedAction.POST_REACTION, SharedAction.POST_DELETE_COMMENT)
        val COMMENT_ACTIONS = POST_ACTIONS + setOf(SharedAction.COMMENT, SharedAction.REACTION, SharedAction.DELETE_COMMENT)
    }
}
