package me.lvca.alba.app.gallery

import kotlinx.coroutines.CancellationException
import me.lvca.alba.app.R
import me.lvca.alba.app.actions.MediaAction
import me.lvca.alba.app.actions.MediaActionKey
import me.lvca.alba.app.actions.MediaActionRunner
import me.lvca.alba.app.messages.UiMessage
import me.lvca.alba.app.messages.UserMessages
import me.lvca.alba.engine.model.GalleryRepository

/** The most photos one favorite or album edit may change, so a failure is easy to retry and report. */
internal const val MAX_EDIT_ITEMS = 50

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
        runner.start(MediaActionKey(MediaAction.CLOUD_TRASH), R.string.cloud_trash_failed) {
            repository.setCloudTrashed(mediaIds, trashed)
            gallery.clearSelection(); gallery.closeMedia()
            messages.show(if (trashed) R.string.cloud_trashed_success else R.string.cloud_restored_success)
        }
    }

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
