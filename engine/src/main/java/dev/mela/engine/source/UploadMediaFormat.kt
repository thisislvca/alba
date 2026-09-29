package dev.mela.engine.source

/** Formats whose original bytes have been checked against Apple's Photos uploader. */
enum class UploadMediaFormat(val mimeType: String, val extension: String, val extensions: Set<String>) {
    JPEG("image/jpeg", "jpg", setOf("jpg", "jpeg")),
    PNG("image/png", "png", setOf("png")),
    HEIC("image/heic", "heic", setOf("heic", "heif")),
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

        fun detect(prefix: ByteArray): UploadMediaFormat {
            fun text(at: Int, size: Int) = if (at + size <= prefix.size)
                String(prefix, at, size, Charsets.ISO_8859_1) else ""
            if (prefix.size >= 3 && prefix[0] == 0xff.toByte() && prefix[1] == 0xd8.toByte() && prefix[2] == 0xff.toByte()) return JPEG
            if (prefix.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte)) return PNG
            if (text(0, 4) == "RIFF" && text(8, 4) == "WEBP") return WEBP
            if (text(4, 4) == "ftyp" && prefix.size >= 16) {
                val length = prefix.take(4).fold(0L) { n, b -> (n shl 8) or (b.toLong() and 255) }
                if (length in 16..prefix.size.toLong() && length % 4 == 0L) {
                    val brands = listOf(text(8, 4)) + (16 until length.toInt() step 4).map { text(it, 4) }
                    if (brands.any { it in setOf("avif", "avis") }) throw UnsupportedUploadException()
                    if (brands.any { it in setOf("msf1", "hevc", "hevx") }) throw UnsupportedUploadException()
                    if (brands.any { it in setOf("heic", "heix") }) return HEIC
                    if ("mif1" in brands) throw UnsupportedUploadException()
                    if ("qt  " in brands) return MOV
                    if (brands.any { it in setOf("isom", "iso2", "mp41", "mp42", "avc1") }) return MP4
                }
            }
            throw UnsupportedUploadException()
        }
    }
}

class UnsupportedUploadException(message: String = MESSAGE) : IllegalArgumentException(message) {
    companion object {
        const val MESSAGE = "Choose a JPEG, PNG, HEIC, still WebP, or H.264/HEVC MP4 or MOV video."
        const val ANIMATED = "Animated images are not supported for upload yet."
        const val VIDEO_CODEC = "This video codec is not supported for upload. Use H.264 or HEVC with AAC audio."
        const val INVALID = "This media file could not be validated. The original has not been uploaded."
    }
}
