package me.lvca.alba.app.platform

import me.lvca.alba.engine.model.GalleryMedia

/** What the system document picker proposes when exporting an original: its type and suggested file name. */
internal data class ExportDocument(val mimeType: String, val title: String) {
    companion object {
        const val LIVE_PHOTO_ARCHIVE_TYPE = "application/zip"

        /** A Live Photo pair exports as one archive holding the still image and the motion clip. */
        fun forMedia(media: GalleryMedia, livePair: Boolean): ExportDocument = if (livePair) {
            ExportDocument(LIVE_PHOTO_ARCHIVE_TYPE, exportName(media).substringBeforeLast('.') + "-LivePhoto.zip")
        } else {
            ExportDocument(media.mimeType, exportName(media))
        }
    }
}

/** An export the user started; the document picker's result completes it. */
internal data class PendingExport(val mediaId: String, val livePair: Boolean)
