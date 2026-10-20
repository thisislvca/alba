package me.lvca.alba.app.transfers

import android.net.Uri
import kotlinx.coroutines.channels.SendChannel
import me.lvca.alba.app.AlbaEffect
import me.lvca.alba.app.R
import me.lvca.alba.app.actions.MediaAction
import me.lvca.alba.app.actions.MediaActionKey
import me.lvca.alba.app.actions.MediaActionRunner
import me.lvca.alba.app.messages.UserMessages
import me.lvca.alba.app.platform.GalleryExporter
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.MediaKind
import me.lvca.alba.engine.model.StoredMessage

/** Gets copies out of Alba: the share sheet, the phone gallery and exported documents. Never touches the source. */
internal class ExportController(
    private val repository: GalleryRepository,
    private val exporter: GalleryExporter,
    private val runner: MediaActionRunner,
    private val messages: UserMessages,
    private val effects: SendChannel<AlbaEffect>,
) {
    fun share(items: List<GalleryMedia>) {
        runner.start(MediaActionKey(MediaAction.SHARE), R.string.message_could_not_prepare_sharing) {
            effects.send(AlbaEffect.Share(exporter.prepareShare(items)))
        }
    }

    fun cancelShare() = runner.cancel(MediaActionKey(MediaAction.SHARE))

    fun shareFailed() = messages.show(R.string.message_no_app_could_open_the_share_sheet)

    fun saveToGallery(media: GalleryMedia) {
        runner.start(MediaActionKey(MediaAction.SAVE_TO_GALLERY, media.id), R.string.message_could_not_save_to_the_gallery) {
            exporter.saveToGallery(media)
            messages.show(if (media.kind == MediaKind.LIVE_PHOTO) R.string.message_saved_the_photo_and_motion_clip_as_two_gallery_items
                else R.string.message_saved_to_your_phone_gallery)
        }
    }

    fun exportDocument(mediaId: String, destination: Uri, livePair: Boolean) {
        runner.start(MediaActionKey(MediaAction.EXPORT, mediaId), R.string.message_could_not_export_this_photo) {
            val media = repository.findMedia(mediaId) ?: error(StoredMessage.THE_PHOTO_IS_NO_LONGER_AVAILABLE.code)
            exporter.exportDocument(media, destination, livePair)
            messages.show(if (livePair) R.string.message_exported_the_original_photo_and_motion_clip else R.string.message_exported_the_original)
        }
    }

    /** Deletes prepared share copies, for example when the account changes. */
    suspend fun clearShares() = exporter.clearShares()
}
