package dev.mela.engine.source

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import dev.mela.engine.model.BackupScope
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidMediaStoreSource(
    context: Context,
) : DeviceCatalogSource, ExactOriginalSource {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override suspend fun scanImages(): List<DeviceMediaRecord> = withContext(Dispatchers.IO) {
        queryGalleryImages() + queryGalleryImages(video = true)
    }

    override suspend fun writeOriginal(
        contentUri: String,
        output: OutputStream,
    ): DeviceOriginalEvidence {
        val evidence = copyMediaStoreItem(contentUri, expectedRevision = "", output = output)
        return DeviceOriginalEvidence(evidence.byteCount, evidence.sha256Hex)
    }

    override suspend fun copySelected(
        contentUri: String,
        displayNameHint: String?,
        output: OutputStream,
    ): ExactOriginalEvidence = withContext(Dispatchers.IO) {
        copyExact(
            uri = contentUri.toUri(),
            displayNameHint = displayNameHint,
            expectedRevision = null,
            acquisitionMethod = "USER_SELECTION",
            requireMediaStoreOriginal = false,
            output = output,
        )
    }

    override suspend fun copyMediaStoreItem(
        contentUri: String,
        expectedRevision: String,
        output: OutputStream,
    ): ExactOriginalEvidence = withContext(Dispatchers.IO) {
        copyExact(
            uri = contentUri.toUri(),
            displayNameHint = null,
            expectedRevision = expectedRevision.takeIf(String::isNotBlank),
            acquisitionMethod = "MEDIA_STORE_EXACT_ORIGINAL",
            requireMediaStoreOriginal = true,
            output = output,
        )
    }

    override suspend fun hashMediaStoreItem(
        contentUri: String,
        expectedRevision: String,
    ): ExactOriginalEvidence = copyMediaStoreItem(
        contentUri = contentUri,
        expectedRevision = expectedRevision,
        output = DISCARDING_OUTPUT,
    )

    override fun permissionFingerprint(): String {
        val imageAccess = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && has(Manifest.permission.READ_MEDIA_IMAGES) -> "full"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "partial"
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                has(Manifest.permission.READ_EXTERNAL_STORAGE) -> "full"
            else -> "none"
        }
        val location = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            has(Manifest.permission.ACCESS_MEDIA_LOCATION)
        ) {
            "unredacted"
        } else {
            "redacted"
        }
        return "$imageAccess:$location:api${Build.VERSION.SDK_INT}"
    }

    override fun hasAutomaticBackupAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && permissionFingerprint().startsWith("full:unredacted")

    override suspend fun discoverJpegs(
        checkpoint: LocalDiscoveryCheckpoint?,
        limit: Int,
    ): LocalDiscoveryPage = discoverEligibleJpegs(checkpoint, limit, BackupScope.CAMERA_JPEGS, 0L)

    override suspend fun discoverEligibleJpegs(
        checkpoint: LocalDiscoveryCheckpoint?,
        limit: Int,
        scope: BackupScope,
        addedAfterEpochMillis: Long,
        enrollmentBaseline: LocalDiscoveryCheckpoint?,
    ): LocalDiscoveryPage = withContext(Dispatchers.IO) {
        require(scope == BackupScope.CAMERA_JPEGS)
        require(limit in 1..200) { "MediaStore discovery limit must be between 1 and 200" }
        check(hasAutomaticBackupAccess()) {
            "Automatic backup needs full photo access and precise media location access."
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            error("Automatic backup requires Android 11 or later.")
        }

        val volume = MediaStore.VOLUME_EXTERNAL
        val version = MediaStore.getVersion(appContext, volume)
        val highWater = MediaStore.getGeneration(appContext, volume)
        val compatibleCheckpoint = checkpoint?.takeIf {
            it.volumeName == volume && it.volumeVersion == version
        }
        val afterGeneration = compatibleCheckpoint?.generationModified ?: -1L
        val afterId = compatibleCheckpoint?.mediaStoreId ?: -1L
        val collection = MediaStore.Images.Media.getContentUri(volume)
        val enrollmentGeneration = enrollmentBaseline?.takeIf { it.volumeVersion == version }?.generationModified
        val enrollmentPredicate = if (enrollmentGeneration != null) {
            "${MediaStore.Images.Media.GENERATION_ADDED} > ?"
        } else {
            "${MediaStore.Images.Media.DATE_ADDED} > ?"
        }
        val selection = """
            (${MediaStore.Images.Media.GENERATION_MODIFIED} > ? OR
             (${MediaStore.Images.Media.GENERATION_MODIFIED} = ? AND ${MediaStore.Images.Media._ID} > ?))
            AND ${MediaStore.Images.Media.GENERATION_MODIFIED} <= ?
            AND ${MediaStore.Images.Media.MIME_TYPE} IN (?, ?)
            AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?
            AND $enrollmentPredicate
        """.trimIndent()
        val args = arrayOf(
            afterGeneration.toString(), afterGeneration.toString(), afterId.toString(),
            highWater.toString(), "image/jpeg", "image/jpg", "DCIM/Camera/", (enrollmentGeneration ?: (addedAfterEpochMillis / 1_000L)).toString(),
        )
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Images.Media.GENERATION_MODIFIED, MediaStore.Images.Media._ID),
            )
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_ASCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit + 1)
        }
        val discovered = resolver.query(collection, galleryProjection(), queryArgs, null)?.use { cursor ->
            readRecords(cursor, collection, version)
        }.orEmpty()
        check(MediaStore.getVersion(appContext, volume) == version) {
            "MediaStore changed while automatic backup was scanning."
        }

        val pageItems = discovered.take(limit)
        val last = pageItems.lastOrNull()
        LocalDiscoveryPage(
            items = pageItems,
            checkpoint = LocalDiscoveryCheckpoint(
                volumeName = volume,
                volumeVersion = version,
                generationModified = last?.generationModified ?: highWater,
                mediaStoreId = last?.mediaStoreId ?: afterId,
            ),
            moreComing = discovered.size > limit,
        )
    }

    override suspend fun captureEnrollmentBaseline(): LocalDiscoveryCheckpoint = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            error("Automatic backup requires Android 11 or later.")
        }
        check(hasAutomaticBackupAccess())
        val volume = MediaStore.VOLUME_EXTERNAL
        val version = MediaStore.getVersion(appContext, volume)
        val generation = MediaStore.getGeneration(appContext, volume)
        check(MediaStore.getVersion(appContext, volume) == version) { "MediaStore changed during backup setup" }
        LocalDiscoveryCheckpoint(volume, version, generation, Long.MAX_VALUE)
    }

    override fun bootSessionId(): String? = runCatching {
        Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT).toString()
    }.getOrNull()

    override fun createTrashRequest(contentUris: List<String>) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        require(contentUris.isNotEmpty()) { "Select at least one photo to move to trash" }
        MediaStore.createTrashRequest(resolver, contentUris.map { it.toUri() }, true).intentSender
    } else {
        error("Android trash requires Android 11 or later")
    }

    override suspend fun readTrashState(contentUri: String): LocalTrashState? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext null
        resolver.query(
            contentUri.toUri(),
            arrayOf(MediaStore.MediaColumns.IS_TRASHED, MediaStore.MediaColumns.DATE_EXPIRES),
            Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) },
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val trashed = cursor.longOrNull(MediaStore.MediaColumns.IS_TRASHED) == 1L
            val expiresSeconds = cursor.longOrNull(MediaStore.MediaColumns.DATE_EXPIRES)
            LocalTrashState(
                isTrashed = trashed,
                expiresAtEpochMillis = expiresSeconds?.takeIf { it > 0L }?.times(1_000L),
            )
        }
    }

    private fun queryGalleryImages(video: Boolean = false): List<DeviceMediaRecord> {
        if (Build.VERSION.SDK_INT >= 33 && !has(if (video) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES)
            && !(Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))) return emptyList()
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else galleryCollection()
        val version = if (Build.VERSION.SDK_INT >= 29) MediaStore.getVersion(appContext, MediaStore.VOLUME_EXTERNAL) else "legacy"
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "datetaken DESC, _id DESC")
            if (Build.VERSION.SDK_INT >= 30) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        return resolver.query(collection, galleryProjection() + if (video) arrayOf("duration") else emptyArray(), args, null)
            ?.use { readRecords(it, collection, version, video) }.orEmpty()
    }

    private fun readRecords(
        cursor: android.database.Cursor,
        collection: Uri,
        volumeVersion: String,
        video: Boolean = false,
    ): List<DeviceMediaRecord> = buildList {
        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val takenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
        val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
        val heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
        val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
        val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
        val generationIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cursor.getColumnIndexOrThrow(MediaStore.Images.Media.GENERATION_MODIFIED)
        } else -1
        val volumeIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndexOrThrow(MediaStore.Images.Media.VOLUME_NAME)
        } else -1

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idIndex)
            val uri = ContentUris.withAppendedId(collection, id)
            val capturedAt = cursor.getLong(takenIndex).takeIf { it > 0L }
                ?: cursor.getLong(addedIndex) * 1_000L
            val generation = if (generationIndex >= 0) cursor.getLong(generationIndex) else -1L
            val volume = if (volumeIndex >= 0) cursor.getString(volumeIndex) ?: "external" else "external"
            add(
                DeviceMediaRecord(
                    id = if (video) "device:video:$volume:$id" else "device:$volume:$id",
                    fileName = cursor.getString(nameIndex) ?: "Photo $id.jpg",
                    capturedAtEpochMillis = capturedAt,
                    addedAtEpochMillis = cursor.getLong(addedIndex).takeIf { it > 0 }?.times(1_000L),
                    width = cursor.getInt(widthIndex),
                    height = cursor.getInt(heightIndex),
                    contentUri = uri.toString(),
                    sourceRevision = mediaStoreRevision(volumeVersion, volume, id, generation, cursor.getLong(sizeIndex)),
                    volumeName = volume,
                    volumeVersion = volumeVersion,
                    mediaStoreId = id,
                    generationModified = generation,
                    mimeType = cursor.getString(mimeIndex) ?: "application/octet-stream",
                    byteCount = cursor.getLong(sizeIndex).coerceAtLeast(0L),
                    durationMillis = cursor.longOrNull("duration"),
                    deviceFolderId = cursor.getColumnIndex("bucket_id").takeIf { it >= 0 }?.let { "device-folder:$volume:${cursor.getString(it)}" },
                    deviceFolderName = cursor.getColumnIndex("bucket_display_name").takeIf { it >= 0 }?.let(cursor::getString),
                    isTrashed = cursor.longOrNull("is_trashed") == 1L,
                    expiresAtEpochMillis = cursor.longOrNull("date_expires")?.times(1000L),
                ),
            )
        }
    }

    private fun galleryProjection(): Array<String> = buildList {
        add("bucket_id")
        add("bucket_display_name")
        if (Build.VERSION.SDK_INT >= 30) { add("is_trashed"); add("date_expires") }
        add(MediaStore.Images.Media._ID)
        add(MediaStore.Images.Media.DISPLAY_NAME)
        add(MediaStore.Images.Media.DATE_TAKEN)
        add(MediaStore.Images.Media.DATE_ADDED)
        add(MediaStore.Images.Media.WIDTH)
        add(MediaStore.Images.Media.HEIGHT)
        add(MediaStore.Images.Media.MIME_TYPE)
        add(MediaStore.Images.Media.SIZE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.Images.Media.GENERATION_MODIFIED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Images.Media.VOLUME_NAME)
    }.toTypedArray()

    private fun galleryCollection(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    private fun copyExact(
        uri: Uri,
        displayNameHint: String?,
        expectedRevision: String?,
        acquisitionMethod: String,
        requireMediaStoreOriginal: Boolean,
        output: OutputStream,
    ): ExactOriginalEvidence {
        val before = readSourceMetadata(uri, displayNameHint)
        if (expectedRevision != null) {
            require(before.revision == expectedRevision) { "This photo changed before it could be staged." }
        }
        val isMediaStore = uri.authority == MediaStore.AUTHORITY
        val requireOriginalUri = if (requireMediaStoreOriginal && isMediaStore &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            MediaStore.setRequireOriginal(uri)
        } else uri
        val originalFormatRequested = isMediaStore &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        val prefix = ByteArray(4096)
        var prefixCount = 0

        try {
            openExactStream(requireOriginalUri, originalFormatRequested).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (prefixCount < prefix.size) {
                        val copy = minOf(prefix.size - prefixCount, read)
                        buffer.copyInto(prefix, prefixCount, 0, copy)
                        prefixCount += copy
                    }
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    byteCount += read
                }
            }
        } catch (error: SecurityException) {
            throw IllegalStateException("Android did not grant access to the exact original.", error)
        }

        val format = UploadMediaFormat.detect(prefix.copyOf(prefixCount))
        val after = readSourceMetadata(uri, displayNameHint)
        require(before.revision == after.revision && before.lastModifiedAtEpochMillis == after.lastModifiedAtEpochMillis) { "This photo changed while it was being staged." }
        require(byteCount > 0L) { "The selected photo was empty." }

        return ExactOriginalEvidence(
            contentUri = uri.toString(),
            displayName = format.fileName(after.displayName),
            mimeType = format.mimeType,
            lineageKey = after.lineageKey,
            sourceRevision = after.revision,
            acquisitionMethod = acquisitionMethod,
            originalFormatRequested = originalFormatRequested,
            unredacted = !isMediaStore || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                has(Manifest.permission.ACCESS_MEDIA_LOCATION),
            formatValidated = true,
            lastModifiedAtEpochMillis = after.lastModifiedAtEpochMillis,
            byteCount = byteCount,
            sha256Hex = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) },
        )
    }

    private fun openExactStream(uri: Uri, originalFormatRequested: Boolean): java.io.InputStream {
        if (!originalFormatRequested || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return resolver.openInputStream(uri) ?: error("Android could not open this photo.")
        }
        val descriptor = resolver.openTypedAssetFileDescriptor(
            uri,
            "*/*",
            Bundle().apply { putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true) },
        ) ?: error("Android could not open this photo.")
        return descriptor.createInputStream()
    }

    private fun readSourceMetadata(uri: Uri, displayNameHint: String?): SourceMetadata {
        val values = runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val displayName = cursor.stringOrNull(MediaStore.MediaColumns.DISPLAY_NAME)
                val size = cursor.longOrNull(MediaStore.MediaColumns.SIZE) ?: -1L
                val generation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cursor.longOrNull(MediaStore.MediaColumns.GENERATION_MODIFIED) ?: -1L
                } else -1L
                val volume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.stringOrNull(MediaStore.MediaColumns.VOLUME_NAME) ?: uri.authority.orEmpty()
                } else "external"
                val rowId = cursor.longOrNull(MediaStore.MediaColumns._ID)
                    ?: uri.lastPathSegment?.toLongOrNull() ?: -1L
                SourceMetadata(
                    displayName = displayNameHint ?: displayName ?: "selected-media",
                    lastModifiedAtEpochMillis = cursor.longOrNull(MediaStore.MediaColumns.DATE_MODIFIED)?.takeIf { it > 0L }?.let { it * 1000L }
                        ?: cursor.longOrNull(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.takeIf { it > 0L },
                    revision = if (uri.authority == MediaStore.AUTHORITY) {
                        val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            MediaStore.getVersion(appContext, MediaStore.VOLUME_EXTERNAL)
                        } else "legacy"
                        mediaStoreRevision(version, volume, rowId, generation, size)
                    } else "$volume:$rowId:$generation:$size",
                    lineageKey = "$volume:$rowId",
                )
            }
        }.getOrNull()
        return values ?: SourceMetadata(
            displayName = displayNameHint ?: uri.lastPathSegment ?: "selected-media",
            revision = "${uri}:unknown",
            lineageKey = "selected:${sha256(uri.toString())}",
        )
    }

    private fun android.database.Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun android.database.Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private fun mediaStoreRevision(version: String, volume: String, id: Long, generation: Long, size: Long): String =
        "$version:$volume:$id:$generation:$size"

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class SourceMetadata(
        val displayName: String,
        val revision: String,
        val lineageKey: String,
        val lastModifiedAtEpochMillis: Long? = null,
    )

    private companion object {
        val DISCARDING_OUTPUT = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }
    }
}
