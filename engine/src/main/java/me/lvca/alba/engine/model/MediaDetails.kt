package me.lvca.alba.engine.model

/**
 * What iCloud records about a photo beyond its files: the title and caption written in Photos, where it was taken,
 * the time zone it was taken in and the burst it belongs to. Every field is optional; a phone photo has none of them.
 */
data class MediaDetails(
    /** Photos' title for the item (CPLAsset `captionEnc`). */
    val caption: String? = null,
    /** Photos' longer caption or description (CPLAsset `extendedDescEnc`). */
    val description: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Metres above sea level. */
    val altitude: Double? = null,
    /** The capture time zone's offset from UTC, in seconds (CPLAsset `timeZoneOffset`). */
    val timeZoneOffsetSeconds: Int? = null,
    /** Shared by every frame of one burst (CPLAsset `burstId`). */
    val burstId: String? = null,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null

    /** Text a search can match: the title and the caption. */
    val searchableText: String get() = listOfNotNull(caption, description).joinToString(" ")

    companion object {
        /** Coordinates on Earth, refusing the null island an unset location sometimes decodes to. */
        fun validLocation(latitude: Double?, longitude: Double?): Boolean =
            latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
                latitude in -MAX_LATITUDE..MAX_LATITUDE && longitude in -MAX_LONGITUDE..MAX_LONGITUDE &&
                !(latitude == 0.0 && longitude == 0.0)

        private const val MAX_LATITUDE = 90.0
        private const val MAX_LONGITUDE = 180.0
    }
}

/** A short, readable name for an original's file format: "HEIC", "JPEG", or "DNG (RAW)" for a RAW original. */
fun mediaFormatLabel(mimeType: String): String {
    val subtype = mimeType.substringAfter('/').lowercase()
    rawFormats[mimeType.lowercase()]?.let { return "$it (RAW)" }
    return when (subtype) {
        "jpeg", "jpg" -> "JPEG"
        "heic", "heif" -> "HEIC"
        "quicktime" -> "MOV"
        "x-raw" -> "RAW"
        else -> subtype.removePrefix("x-").uppercase()
    }
}

/** Whether [mimeType] names a camera RAW original, including Apple ProRAW (Adobe DNG). */
fun isRawFormat(mimeType: String): Boolean = mimeType.lowercase() in rawFormats || mimeType.equals("image/x-raw", true)

private val rawFormats = mapOf(
    "image/x-adobe-dng" to "DNG",
    "image/x-canon-cr2" to "CR2",
    "image/x-canon-cr3" to "CR3",
    "image/x-canon-crw" to "CRW",
    "image/x-fuji-raf" to "RAF",
    "image/x-nikon-nef" to "NEF",
    "image/x-nikon-nrw" to "NRW",
    "image/x-olympus-orf" to "ORF",
    "image/x-panasonic-rw2" to "RW2",
    "image/x-pentax-pef" to "PEF",
    "image/x-sony-arw" to "ARW",
)
