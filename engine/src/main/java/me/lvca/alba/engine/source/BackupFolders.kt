package me.lvca.alba.engine.source

import android.provider.MediaStore
import me.lvca.alba.engine.model.BackupScope

/**
 * Which MediaStore rows automatic backup reads: the camera folder on every volume, including SD cards, plus the
 * folders the user added. A folder is a MediaStore bucket, so it is matched by its bucket and volume rather than by
 * one exact path string.
 */
internal object BackupFolders {
    /**
     * Relative paths, lower case, that phone makers use for the camera. RELATIVE_PATH is relative to its volume, so
     * the same entry also matches the camera folder on an SD card.
     */
    val CAMERA_PATHS = listOf("dcim/camera/", "dcim/100andro/", "dcim/100media/", "dcim/opencamera/")

    fun isCamera(relativePath: String?): Boolean = relativePath?.lowercase() in CAMERA_PATHS

    /** The id a folder keeps across scans: its volume and MediaStore bucket. */
    fun folderId(volume: String, bucketId: String): String = "device-folder:$volume:$bucketId"

    /** The volume and bucket of [folderId], or null when it is not one. */
    fun parse(folderId: String): Pair<String, String>? {
        val parts = folderId.split(':')
        if (parts.size != 3 || parts[0] != "device-folder" || parts[1].isBlank() || parts[2].isBlank()) return null
        return parts[1] to parts[2]
    }

    /** MIME types [scope] covers, or null for every image and video. Non-JPEG items of older consents stay out. */
    fun mimeTypes(scope: BackupScope): List<String>? = when (scope) {
        BackupScope.CAMERA_JPEGS -> listOf("image/jpeg", "image/jpg")
        BackupScope.CAMERA_MEDIA -> null
    }

    /** Folders [scope] may add beyond the camera. An older JPEG-only consent covered the camera alone. */
    fun extraFolders(scope: BackupScope, chosen: Set<String>): Set<String> = when (scope) {
        BackupScope.CAMERA_JPEGS -> emptySet()
        BackupScope.CAMERA_MEDIA -> chosen
    }

    /** A SQL predicate and its arguments selecting the camera folder and the chosen [folders]. */
    fun selection(folders: Set<String>): Pair<String, List<String>> {
        val camera = "LOWER(${MediaStore.MediaColumns.RELATIVE_PATH}) IN (${CAMERA_PATHS.joinToString { "?" }})"
        val chosen = folders.mapNotNull(::parse)
        val args = CAMERA_PATHS + chosen.flatMap { (volume, bucket) -> listOf(volume, bucket) }
        val extra = chosen.joinToString(separator = "") {
            " OR (${MediaStore.MediaColumns.VOLUME_NAME} = ? AND ${MediaStore.MediaColumns.BUCKET_ID} = ?)"
        }
        return "($camera$extra)" to args
    }
}
