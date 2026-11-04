package me.lvca.alba.engine.source

import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.isRawFormat

/**
 * Formats whose original bytes Alba uploads to the iCloud Photos library, unchanged.
 *
 * The set is the one iCloud.com's own library uploader accepts: `iCPLUploadV2Requirements.acceptedFileTypes` in the
 * iCloud.com Photos web app (applications/photos3, build 2640Build32, read 2026-11-04) lists JPEG, PNG, HEIF, HEIC,
 * QuickTime, MPEG-4 and WebP. Its Shared Album uploader adds GIF, TIFF and M4V, but the library uploader does not, so
 * those are skipped with a reason rather than converted: Alba never changes an original.
 */
enum class UploadMediaFormat(val mimeType: String, val extension: String, val extensions: Set<String>) {
    JPEG("image/jpeg", "jpg", setOf("jpg", "jpeg")),
    PNG("image/png", "png", setOf("png")),
    HEIC("image/heic", "heic", setOf("heic", "heif", "hif")),
    WEBP("image/webp", "webp", setOf("webp")),
    MP4("video/mp4", "mp4", setOf("mp4")),
    MOV("video/quicktime", "mov", setOf("mov"));

    fun fileName(value: String): String {
        val cleaned = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(150).ifBlank { "media" }
        return if (cleaned.substringAfterLast('.', "").lowercase() in extensions) cleaned
        else "${cleaned.substringBeforeLast('.', cleaned)}.$extension"
    }

    companion object {
        fun isCandidate(mimeType: String?): Boolean = mimeType in entries.map { it.mimeType } ||
            mimeType == "image/jpg" || mimeType == "image/heif"

        /**
         * Why automatic backup passes over an item whose MediaStore [mimeType] is not a [isCandidate], as a
         * [StoredMessage] code. The bytes are never read for these: the MIME type alone already rules them out.
         */
        fun unsupportedReason(mimeType: String): String {
            val type = mimeType.lowercase()
            return when {
                type == "image/gif" -> UnsupportedUploadException.GIF
                isRawFormat(type) || type == "image/dng" -> UnsupportedUploadException.RAW
                type == "image/tiff" -> UnsupportedUploadException.TIFF
                type == "image/avif" -> UnsupportedUploadException.AVIF
                type in setOf("image/heic-sequence", "image/heif-sequence") -> UnsupportedUploadException.IMAGE_SEQUENCE
                type.startsWith("video/") -> UnsupportedUploadException.VIDEO_CONTAINER
                else -> UnsupportedUploadException.NOT_ACCEPTED
            }
        }

        /**
         * The format of an original from its first bytes, or [UnsupportedUploadException] with the reason it is not
         * uploaded. Only the content decides; a file name or MIME type can be wrong.
         */
        fun detect(prefix: ByteArray): UploadMediaFormat {
            fun text(at: Int, size: Int) = if (at + size <= prefix.size)
                String(prefix, at, size, Charsets.ISO_8859_1) else ""
            fun refuse(reason: String): Nothing = throw UnsupportedUploadException(reason)
            if (prefix.size >= 3 && prefix[0] == 0xff.toByte() && prefix[1] == 0xd8.toByte() && prefix[2] == 0xff.toByte()) return JPEG
            if (prefix.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte)) return PNG
            if (text(0, 4) == "RIFF" && text(8, 4) == "WEBP") return WEBP
            MediaSignatures.refusal(prefix)?.let(::refuse)
            if (text(4, 4) == "ftyp" && prefix.size >= 16) {
                val length = prefix.take(4).fold(0L) { n, b -> (n shl 8) or (b.toLong() and 255) }
                if (length in 16..prefix.size.toLong() && length % 4 == 0L) {
                    val major = text(8, 4)
                    val brands = listOf(major) + (16 until length.toInt() step 4).map { text(it, 4) }
                    MediaSignatures.brandRefusal(major, brands)?.let(::refuse)
                    if (brands.any { it in setOf("heic", "heix") }) return HEIC
                    if ("mif1" in brands) {
                        // A HEIF image that names only the generic brand: its item types say how it is coded.
                        val items = HeifItems.types(prefix, length.toInt())
                        if ("hvc1" in items) return HEIC
                        if ("av01" in items) refuse(UnsupportedUploadException.AVIF)
                        refuse(UnsupportedUploadException.MESSAGE)
                    }
                    if ("qt  " in brands) return MOV
                    if (brands.any { it in MediaSignatures.MP4_BRANDS }) return MP4
                }
            }
            refuse(UnsupportedUploadException.MESSAGE)
        }
    }
}

class UnsupportedUploadException(message: String = MESSAGE) : IllegalArgumentException(message) {
    companion object {
        val MESSAGE = StoredMessage.UPLOAD_UNSUPPORTED_FORMAT.code
        val ANIMATED = StoredMessage.UPLOAD_ANIMATED_UNSUPPORTED.code
        val VIDEO_CODEC = StoredMessage.UPLOAD_UNSUPPORTED_CODEC.code
        val INVALID = StoredMessage.UPLOAD_INVALID_MEDIA.code
        val AVIF = StoredMessage.UPLOAD_AVIF_UNSUPPORTED.code
        val IMAGE_SEQUENCE = StoredMessage.UPLOAD_IMAGE_SEQUENCE_UNSUPPORTED.code
        val GIF = StoredMessage.UPLOAD_GIF_NOT_ACCEPTED.code
        val RAW = StoredMessage.UPLOAD_RAW_NOT_ACCEPTED.code
        val RAW_WITH_JPEG = StoredMessage.UPLOAD_RAW_NOT_ACCEPTED_JPEG_BACKS_UP.code
        val TIFF = StoredMessage.UPLOAD_TIFF_NOT_ACCEPTED.code
        val VIDEO_CONTAINER = StoredMessage.UPLOAD_VIDEO_FORMAT_NOT_ACCEPTED.code
        val NOT_ACCEPTED = StoredMessage.UPLOAD_FORMAT_NOT_ACCEPTED.code
    }
}
