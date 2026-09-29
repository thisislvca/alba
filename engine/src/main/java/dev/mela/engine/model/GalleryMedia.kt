package dev.mela.engine.model

enum class MediaOrigin {
    ICLOUD,
    DEVICE,
}

enum class MediaKind { PHOTO, VIDEO, LIVE_PHOTO }
enum class GallerySort { NEWEST, OLDEST }
enum class GalleryDate { CAPTURED, ADDED }
fun GalleryMedia.dateMillis(date: GalleryDate): Long =
    if (date == GalleryDate.ADDED) addedAtEpochMillis ?: capturedAtEpochMillis else capturedAtEpochMillis

data class GalleryCollection(val id: String, val name: String, val parentId: String? = null,
    val isFolder: Boolean = false, val position: Long = 0, val shared: SharedAlbumInfo? = null)

data class GalleryQuery(
    val origin: MediaOrigin? = null,
    val collectionId: String? = null,
    val filename: String = "",
    val fromDate: java.time.LocalDate? = null,
    val throughDate: java.time.LocalDate? = null,
    val sort: GallerySort = GallerySort.NEWEST,
    val offlineOnly: Boolean = false,
    val date: GalleryDate = GalleryDate.CAPTURED,
    val trashOnly: Boolean = false,
) {
    fun apply(items: List<GalleryMedia>, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<GalleryMedia> {
        val comparator = compareBy<GalleryMedia> { it.dateMillis(date) }.thenBy { it.id }
        return items.filter { item ->
            val date = java.time.Instant.ofEpochMilli(item.dateMillis(this.date)).atZone(zone).toLocalDate()
            item.isTrashed == trashOnly &&
                (!item.isShared || collectionId?.startsWith(SharedMediaIdentity.PREFIX) == true || offlineOnly) &&
                (origin == null || item.origin == origin) &&
                (!offlineOnly || item.availability == MediaAvailability.ORIGINAL_CACHED) &&
                (collectionId == null || if (collectionId == FAVORITES) item.isFavorite else collectionId in item.collectionIds) &&
                item.fileName.contains(filename.trim(), ignoreCase = true) &&
                (fromDate == null || date >= fromDate) && (throughDate == null || date <= throughDate)
        }.sortedWith(if (sort == GallerySort.NEWEST) comparator.reversed() else comparator)
    }
    companion object { const val FAVORITES = "smart:favorites" }
}

enum class MediaAvailability {
    CLOUD_ONLY,
    PREVIEW_CACHED,
    ORIGINAL_CACHED,
    DEVICE_ORIGINAL,
}

data class GalleryMedia(
    val id: String,
    val fileName: String,
    val capturedAtEpochMillis: Long,
    val width: Int,
    val height: Int,
    val origin: MediaOrigin,
    val availability: MediaAvailability,
    val previewReference: String?,
    val originalReference: String?,
    val accentStartArgb: Long,
    val accentEndArgb: Long,
    val kind: MediaKind = MediaKind.PHOTO,
    val mimeType: String = "image/jpeg",
    val durationMillis: Long? = null,
    val isFavorite: Boolean = false,
    val collectionIds: Set<String> = emptySet(),
    val sourceRevision: String = "",
    val motionReference: String? = null,
    val motionMimeType: String = "video/quicktime",
    val addedAtEpochMillis: Long? = null,
    val viewerReference: String? = null,
    val byteCount: Long? = null,
    val deviceFolderId: String? = null,
    val deviceFolderName: String? = null,
    val isTrashed: Boolean = false,
    val expiresAtEpochMillis: Long? = null,
    val linkedDeviceId: String? = null,
    val linkedDeviceReference: String? = null,

)

object AvailabilityResolver {
    fun resolve(
        origin: MediaOrigin,
        previewIsReadable: Boolean,
        originalIsReadable: Boolean,
    ): MediaAvailability = when {
        origin == MediaOrigin.DEVICE -> MediaAvailability.DEVICE_ORIGINAL
        originalIsReadable -> MediaAvailability.ORIGINAL_CACHED
        previewIsReadable -> MediaAvailability.PREVIEW_CACHED
        else -> MediaAvailability.CLOUD_ONLY
    }
}

/** Personal-library indexes, with membership supplied by iCloud (not filename guesses). */
enum class SmartCollection(val id: String, val title: String, val appleFilter: String?) {
    VIDEOS("smart:videos", "Videos", "VIDEO"),
    LIVE_PHOTOS("smart:live", "Live Photos", "LIVE"),
    SCREENSHOTS("smart:screenshots", "Screenshots", "SCREENSHOT"),
    PANORAMAS("smart:panoramas", "Panoramas", "PANORAMA"),
    BURSTS("smart:bursts", "Bursts", null),
    SLO_MO("smart:slomo", "Slo-mo", "SLOMO"),
    TIME_LAPSE("smart:timelapse", "Time-lapse", "TIMELAPSE");
}

data class CloudStorageUsage(val usedBytes: Long, val totalBytes: Long,
    val categories: List<StorageCategory>, val checkedAtEpochMillis: Long,
    val isDemo: Boolean = false) {
    val fraction: Float get() = (usedBytes.toDouble() / totalBytes.coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat()
}
data class StorageCategory(val name: String, val bytes: Long)
