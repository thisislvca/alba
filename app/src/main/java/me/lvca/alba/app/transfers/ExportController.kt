package me.lvca.alba.app.transfers

import android.net.Uri
import me.lvca.alba.app.AlbaEffect
import me.lvca.alba.app.EffectQueue
import me.lvca.alba.app.R
import me.lvca.alba.app.actions.MediaAction
import me.lvca.alba.app.actions.MediaActionKey
import me.lvca.alba.app.actions.MediaActionRunner
import me.lvca.alba.app.messages.UserMessages
import me.lvca.alba.app.platform.GalleryExporter
import me.lvca.alba.app.ui.ExternalUse
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
    private val effects: EffectQueue,
) {
    fun share(items: List<GalleryMedia>) {
        runner.start(MediaActionKey(MediaAction.SHARE), R.string.message_could_not_prepare_sharing) {
            effects.send(AlbaEffect.Share(exporter.prepareShare(items)))
        }
    }

    /** Shares a Live Photo's motion clip as a video, like Google Photos' "Share as video". */
    fun shareLiveVideo(media: GalleryMedia) {
        runner.start(MediaActionKey(MediaAction.SHARE), R.string.message_could_not_prepare_sharing) {
            effects.send(AlbaEffect.Share(exporter.prepareShare(listOf(media), motion = true)))
        }
    }

    /** Saves a Live Photo's motion clip to the phone gallery as a video. */
    fun saveLiveVideo(media: GalleryMedia) {
        runner.start(MediaActionKey(MediaAction.SAVE_TO_GALLERY, media.id), R.string.message_could_not_save_to_the_gallery) {
            exporter.saveToGallery(media, motionOnly = true)
            messages.show(R.string.message_saved_the_motion_clip_as_a_video)
        }
    }

    fun cancelShare() {
        runner.cancel(MediaActionKey(MediaAction.SHARE))
        runner.cancel(MediaActionKey(MediaAction.OPEN_ELSEWHERE))
    }

    /**
     * Prepares a readable copy of the original, downloading it from iCloud when needed, and asks the UI to hand it to
     * another app or to printing. Only photos print.
     */
    fun useElsewhere(media: GalleryMedia, use: ExternalUse) {
        if (use == ExternalUse.PRINT && media.kind == MediaKind.VIDEO) return
        runner.start(MediaActionKey(MediaAction.OPEN_ELSEWHERE), R.string.message_could_not_prepare_the_original) {
            val uri = exporter.prepareCopy(media)
            if (use == ExternalUse.PRINT && !exporter.canDecodeImage(uri)) {
                messages.show(R.string.message_cannot_print_this_format)
                return@start
            }
            effects.send(AlbaEffect.UseElsewhere(use, uri, media.mimeType, media.fileName))
        }
    }

    fun useElsewhereFailed(use: ExternalUse) =
        messages.show(if (use == ExternalUse.PRINT) R.string.message_could_not_print else R.string.message_no_app_can_open_this)

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
