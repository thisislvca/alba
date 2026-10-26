package me.lvca.alba.app.gallery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.lvca.alba.app.R
import me.lvca.alba.app.actions.MediaAction
import me.lvca.alba.app.actions.MediaActionKey
import me.lvca.alba.app.actions.MediaActionRunner
import me.lvca.alba.app.messages.UiMessage
import me.lvca.alba.app.messages.UndoAction
import me.lvca.alba.app.messages.UserMessages
import me.lvca.alba.engine.model.GalleryRepository

/** The most photos one favorite or album edit may change, so a failure is easy to retry and report. */
internal const val MAX_EDIT_ITEMS = 50

/** How long a trashed item stays hidden while the catalog catches up with the move. */
private const val CATALOG_CATCH_UP_MILLIS = 2_000L

/** Edits to the user's iCloud library: favorites, albums and iCloud's Recently Deleted. */
internal class LibraryEditsController(
    private val repository: GalleryRepository,
    private val runner: MediaActionRunner,
    private val messages: UserMessages,
    private val gallery: GalleryBrowser,
) {
    fun setFavorite(mediaId: String, favorite: Boolean) {
        runner.start(MediaActionKey(MediaAction.FAVORITE, mediaId),
            R.string.message_could_not_confirm_the_favorite_change_refresh_before_retrying) {
            repository.setFavorite(mediaId, favorite)
        }
    }

    fun setFavorites(mediaIds: List<String>, favorite: Boolean) {
        runner.start(MediaActionKey(MediaAction.FAVORITE), R.string.favorites_failed) {
            val ids = mediaIds.distinct()
            require(ids.size in 1..MAX_EDIT_ITEMS)
            val completed = mutableSetOf<String>()
            try {
                for (id in ids) { repository.setFavorite(id, favorite); completed += id }
                gallery.selectItems(completed, selected = false)
                messages.show(UiMessage.quantity(R.plurals.favorites_updated, completed.size))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                gallery.selectItems(completed, selected = false)
                messages.show(UiMessage(R.string.favorites_partial, listOf(completed.size, ids.size)))
            }
        }
    }

    fun removeFromAlbum(albumId: String, mediaIds: List<String>) {
        runner.start(MediaActionKey(MediaAction.ALBUM), R.string.album_removal_failed) {
            repository.removeFromAlbum(albumId, mediaIds)
            gallery.clearSelection(); gallery.closeMedia()
            messages.show(R.string.album_photos_removed)
        }
    }

    fun setCloudTrashed(mediaIds: List<String>, trashed: Boolean) {
        if (!trashed) return restoreFromCloudTrash(mediaIds, runner::start, closeViewer = true)
        runner.start(MediaActionKey(MediaAction.CLOUD_TRASH), R.string.cloud_trash_failed) {
            val ids = mediaIds.toSet()
            // Like Google Photos, the items leave at once (an open viewer moves on) and "Moved to trash" offers Undo
            // right away; a failure brings them back and replaces the message.
            gallery.clearSelection()
            gallery.beginRemoval(ids)
            messages.show(UiMessage.Undoable(UiMessage.quantity(R.plurals.cloud_trash_moved, mediaIds.size), UndoAction.CloudTrash(mediaIds)))
            try {
                repository.setCloudTrashed(mediaIds, true)
                // Show them again only once the catalog has them in the trash, so they never flash back.
                withTimeoutOrNull(CATALOG_CATCH_UP_MILLIS) {
                    repository.observeGallery().first { items -> items.none { it.id in ids && !it.isTrashed } }
                }
            } finally { gallery.endRemoval(ids) }
        }
    }

    /**
     * Undo for a move to the trash: it waits for that move to finish rather than being refused while it runs, and shows
     * the photo again in a viewer that moved on.
     */
    fun undoCloudTrash(mediaIds: List<String>) = restoreFromCloudTrash(mediaIds, runner::startAfter, closeViewer = false)

    private fun restoreFromCloudTrash(mediaIds: List<String>, start: (MediaActionKey, Int, suspend () -> Unit) -> Any,
        closeViewer: Boolean) {
        start(MediaActionKey(MediaAction.CLOUD_TRASH), R.string.cloud_trash_failed) {
            repository.setCloudTrashed(mediaIds, false)
            gallery.clearSelection()
            // Undo in a viewer that moved on brings the photo back into view; a restore from Recently Deleted closes it.
            if (closeViewer) gallery.closeMedia()
            else if (gallery.selectedMediaId.value != null) gallery.openMedia(mediaIds.first())
            messages.show(UiMessage(R.string.cloud_restored_success))
        }
    }

    /**
     * Moves photos into or out of Hidden. Like a move to the trash, they leave the current view at once and the message
     * offers Undo; a failure brings them back.
     */
    fun setHidden(mediaIds: List<String>, hidden: Boolean, undo: Boolean = false) {
        val ids = mediaIds.distinct()
        if (ids.isEmpty()) return
        val start: (MediaActionKey, Int, suspend () -> Unit) -> Any = if (undo) runner::startAfter else runner::start
        start(MediaActionKey(MediaAction.HIDE), R.string.hidden_change_failed) {
            require(ids.size in 1..MAX_EDIT_ITEMS)
            val moved = ids.toSet()
            gallery.clearSelection()
            if (!undo) {
                gallery.beginRemoval(moved)
                messages.show(UiMessage.Undoable(UiMessage.quantity(if (hidden) R.plurals.hidden_moved else R.plurals.unhidden_moved, ids.size),
                    UndoAction.Hidden(ids, hidden)))
            }
            try {
                repository.setHidden(ids, hidden)
                withTimeoutOrNull(CATALOG_CATCH_UP_MILLIS) {
                    repository.observeGallery().first { items -> items.none { it.id in moved && it.isHidden != hidden } }
                }
            } finally { if (!undo) gallery.endRemoval(moved) }
        }
    }

    /** Undo for [setHidden]: waits for that move to finish, then moves the photos back. */
    fun undoHidden(mediaIds: List<String>, hidden: Boolean) = setHidden(mediaIds, !hidden, undo = true)

    fun createAlbum(name: String) {
        runner.start(MediaActionKey(MediaAction.ALBUM), R.string.message_could_not_confirm_the_new_album_refresh_before_retrying) {
            val album = repository.createAlbum(name)
            gallery.setQuery(gallery.currentQuery().copy(collectionId = album.id))
            messages.show(UiMessage(R.string.album_created, listOf(album.name)))
        }
    }

    fun createAlbumWithPhotos(name: String, mediaIds: List<String>) {
        runner.start(MediaActionKey(MediaAction.ALBUM), R.string.message_could_not_confirm_the_new_album_refresh_before_retrying) {
            val ids = mediaIds.distinct()
            require(ids.size in 1..MAX_EDIT_ITEMS)
            val album = repository.createAlbum(name)
            try {
                repository.addToAlbum(album.id, ids)
                gallery.clearSelection()
                messages.show(UiMessage.Quantity(R.plurals.album_created_with_photos, ids.size, listOf(album.name, ids.size)))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // The album creation succeeded. Never blindly repeat either remote write.
                messages.show(UiMessage(R.string.album_created_membership_uncertain, listOf(album.name)))
            }
        }
    }

    fun deleteAlbum(albumId: String) {
        runner.start(MediaActionKey(MediaAction.ALBUM), R.string.message_could_not_confirm_album_deletion_refresh_before_retrying) {
            repository.deleteAlbum(albumId)
            if (gallery.currentQuery().collectionId == albumId) gallery.setQuery(gallery.currentQuery().copy(collectionId = null))
            messages.show(R.string.message_album_deleted_its_photos_remain_in_your_library)
        }
    }

    fun renameAlbum(albumId: String, name: String) {
        runner.start(MediaActionKey(MediaAction.ALBUM), R.string.message_could_not_confirm_the_album_name_refresh_before_retrying) {
            repository.renameAlbum(albumId, name)
        }
    }

    fun addToAlbum(albumId: String, mediaIds: List<String>) {
        runner.start(MediaActionKey(MediaAction.ALBUM), R.string.message_could_not_confirm_album_membership_refresh_before_retrying) {
            repository.addToAlbum(albumId, mediaIds)
            gallery.clearSelection()
            messages.show(UiMessage.quantity(R.plurals.added_to_album_count, mediaIds.size))
        }
    }
}
