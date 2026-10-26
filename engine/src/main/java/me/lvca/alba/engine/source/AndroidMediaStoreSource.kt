package me.lvca.alba.engine.source

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.DeviceFolder
import me.lvca.alba.engine.model.StoredMessage
import java.io.OutputStream
import java.security.MessageDigest

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
        val location = if (has(Manifest.permission.ACCESS_MEDIA_LOCATION)) "unredacted" else "redacted"
        return "$imageAccess:$location:api${Build.VERSION.SDK_INT}"
    }

    override fun hasAutomaticBackupAccess(): Boolean = permissionFingerprint().startsWith("full:unredacted")

    override fun hasVideoAccess(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        has(Manifest.permission.READ_MEDIA_VIDEO)

    override suspend fun discoverEligibleMedia(
        checkpoint: LocalDiscoveryCheckpoint?,
        limit: Int,
        scope: BackupScope,
        folders: Set<String>,
        addedAfterEpochMillis: Long,
        enrollmentBaseline: LocalDiscoveryCheckpoint?,
    ): LocalDiscoveryPage = withContext(Dispatchers.IO) {
        require(limit in 1..200) { "MediaStore discovery limit must be between 1 and 200" }
        check(hasAutomaticBackupAccess()) {
            StoredMessage.AUTOMATIC_BACKUP_NEEDS_FULL_PHOTO_ACCESS_AND_PRECISE_MEDIA_LOCATION_ACCESS.code
        }

        val volume = MediaStore.VOLUME_EXTERNAL
        val version = MediaStore.getVersion(appContext, volume)
        val highWater = MediaStore.getGeneration(appContext, volume)
        val compatibleCheckpoint = checkpoint?.takeIf {
            it.volumeName == volume && it.volumeVersion == version
        }
        val afterGeneration = compatibleCheckpoint?.generationModified ?: -1L
        val afterId = compatibleCheckpoint?.mediaStoreId ?: -1L
        val (scopeSelection, scopeArgs) = eligibleSelection(scope, folders, addedAfterEpochMillis, enrollmentBaseline, version)
        val selection = """
            (${MediaStore.MediaColumns.GENERATION_MODIFIED} > ? OR
             (${MediaStore.MediaColumns.GENERATION_MODIFIED} = ? AND ${MediaStore.MediaColumns._ID} > ?))
            AND ${MediaStore.MediaColumns.GENERATION_MODIFIED} <= ?
            AND $scopeSelection
        """.trimIndent()
        val args = listOf(afterGeneration.toString(), afterGeneration.toString(), afterId.toString(), highWater.toString()) +
            scopeArgs
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns._ID),
            )
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_ASCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit + 1)
        }
        val files = MediaStore.Files.getContentUri(volume)
        val discovered = resolver.query(files, discoveryProjection(), queryArgs, null)?.use { cursor ->
            readRecords(cursor, files, version)
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

    override suspend fun eligibleMediaIds(
        scope: BackupScope,
        folders: Set<String>,
        addedAfterEpochMillis: Long,
        enrollmentBaseline: LocalDiscoveryCheckpoint?,
    ): Set<String> = withContext(Dispatchers.IO) {
        if (!hasAutomaticBackupAccess()) return@withContext emptySet()
        val volume = MediaStore.VOLUME_EXTERNAL
        val version = MediaStore.getVersion(appContext, volume)
        val (selection, args) = eligibleSelection(scope, folders, addedAfterEpochMillis, enrollmentBaseline, version)
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
        }
        resolver.query(
            MediaStore.Files.getContentUri(volume),
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.VOLUME_NAME),
            queryArgs,
            null,
        )?.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    val video = cursor.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    add(deviceMediaId(video, cursor.getString(2) ?: "external", cursor.getLong(0)))
                }
            }
        }.orEmpty()
    }

    override suspend fun deviceFolders(): List<DeviceFolder> = withContext(Dispatchers.IO) {
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, MEDIA_TYPES)
        }
        val counts = linkedMapOf<String, DeviceFolder>()
        resolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(
                MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.VOLUME_NAME,
            ),
            queryArgs,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val bucket = cursor.getString(0) ?: continue
                val volume = cursor.getString(3) ?: "external"
                val id = BackupFolders.folderId(volume, bucket)
                val path = cursor.getString(2).orEmpty()
                val current = counts[id]
                counts[id] = current?.copy(itemCount = current.itemCount + 1) ?: DeviceFolder(
                    id = id,
                    name = cursor.getString(1) ?: path.trimEnd('/').substringAfterLast('/'),
                    relativePath = path,
                    itemCount = 1,
                    isCamera = BackupFolders.isCamera(path),
                )
            }
        }
        counts.values.sortedWith(compareByDescending<DeviceFolder> { it.isCamera }.thenBy { it.name.lowercase() })
    }

    /** Rows [scope] covers in the camera folder and [folders], added after enrollment. Pending rows are never returned. */
    private fun eligibleSelection(
        scope: BackupScope,
        folders: Set<String>,
        addedAfterEpochMillis: Long,
        enrollmentBaseline: LocalDiscoveryCheckpoint?,
        version: String,
    ): Pair<String, List<String>> {
        val enrollmentGeneration = enrollmentBaseline?.takeIf { it.volumeVersion == version }?.generationModified
        val enrollmentPredicate = if (enrollmentGeneration != null) {
            "${MediaStore.MediaColumns.GENERATION_ADDED} > ?"
        } else {
            "${MediaStore.MediaColumns.DATE_ADDED} > ?"
        }
        val (folderSelection, folderArgs) = BackupFolders.selection(BackupFolders.extraFolders(scope, folders))
        val mimeTypes = BackupFolders.mimeTypes(scope)
        val mimeSelection = mimeTypes?.let { types -> "AND ${MediaStore.MediaColumns.MIME_TYPE} IN (${types.joinToString { "?" }})" }.orEmpty()
        val mediaTypes = if (mimeTypes == null && hasVideoAccess()) MEDIA_TYPES.toList() else listOf(MEDIA_TYPES.first())
        val selection = """
            ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${mediaTypes.joinToString { "?" }})
            $mimeSelection
            AND $folderSelection
            AND $enrollmentPredicate
        """.trimIndent()
        return selection to (mediaTypes + mimeTypes.orEmpty() + folderArgs +
            (enrollmentGeneration ?: (addedAfterEpochMillis / 1_000L)).toString())
    }

    override suspend fun captureEnrollmentBaseline(): LocalDiscoveryCheckpoint = withContext(Dispatchers.IO) {
        check(hasAutomaticBackupAccess())
        val volume = MediaStore.VOLUME_EXTERNAL
        val version = MediaStore.getVersion(appContext, volume)
        val generation = MediaStore.getGeneration(appContext, volume)
        check(MediaStore.getVersion(appContext, volume) == version) { StoredMessage.MEDIASTORE_CHANGED_DURING_BACKUP_SETUP.code }
        LocalDiscoveryCheckpoint(volume, version, generation, Long.MAX_VALUE)
    }

    override fun bootSessionId(): String? = runCatching {
        Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT).toString()
    }.getOrNull()

    override fun createTrashRequest(contentUris: List<String>): IntentSender {
        require(contentUris.isNotEmpty()) { StoredMessage.SELECT_AT_LEAST_ONE_PHOTO_TO_MOVE_TO_TRASH.code }
        return MediaStore.createTrashRequest(resolver, contentUris.map { it.toUri() }, true).intentSender
    }

    override suspend fun readTrashState(contentUri: String): LocalTrashState? = withContext(Dispatchers.IO) {
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
        if (Build.VERSION.SDK_INT >= 33 && !has(if (video) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES) &&
            !(Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))) return emptyList()
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else galleryCollection()
        val version = MediaStore.getVersion(appContext, MediaStore.VOLUME_EXTERNAL)
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "datetaken DESC, _id DESC")
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
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
        val generationIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.GENERATION_MODIFIED)
        val volumeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.VOLUME_NAME)

        val mediaTypeIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idIndex)
            // A MediaStore Files row names its own media type; open it through its typed collection.
            val rowVideo = if (mediaTypeIndex >= 0) cursor.getInt(mediaTypeIndex) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO else video
            val rowCollection = when {
                mediaTypeIndex < 0 -> collection
                rowVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            }
            val uri = ContentUris.withAppendedId(rowCollection, id)
            val capturedAt = cursor.getLong(takenIndex).takeIf { it > 0L }
                ?: cursor.getLong(addedIndex) * 1_000L
            val generation = cursor.getLong(generationIndex)
            val volume = cursor.getString(volumeIndex) ?: "external"
            add(
                DeviceMediaRecord(
                    id = deviceMediaId(rowVideo, volume, id),
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
                    durationMillis = cursor.longOrNull("duration")?.takeIf { rowVideo },
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
        add("is_trashed")
        add("date_expires")
        add(MediaStore.Images.Media._ID)
        add(MediaStore.Images.Media.DISPLAY_NAME)
        add(MediaStore.Images.Media.DATE_TAKEN)
        add(MediaStore.Images.Media.DATE_ADDED)
        add(MediaStore.Images.Media.WIDTH)
        add(MediaStore.Images.Media.HEIGHT)
        add(MediaStore.Images.Media.MIME_TYPE)
        add(MediaStore.Images.Media.SIZE)
        add(MediaStore.Images.Media.GENERATION_MODIFIED)
        add(MediaStore.Images.Media.VOLUME_NAME)
    }.toTypedArray()

    private fun discoveryProjection(): Array<String> = galleryProjection() +
        arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DURATION)

    private fun galleryCollection(): Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

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
            require(before.revision == expectedRevision) { StoredMessage.THIS_PHOTO_CHANGED_BEFORE_IT_COULD_BE_STAGED.code }
        }
        val isMediaStore = uri.authority == MediaStore.AUTHORITY
        val requireOriginalUri = if (requireMediaStoreOriginal && isMediaStore) MediaStore.setRequireOriginal(uri) else uri
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
            throw IllegalStateException(StoredMessage.ANDROID_DID_NOT_GRANT_ACCESS_TO_THE_EXACT_ORIGINAL.code, error)
        }

        val format = UploadMediaFormat.detect(prefix.copyOf(prefixCount))
        val after = readSourceMetadata(uri, displayNameHint)
        require(before.revision == after.revision && before.lastModifiedAtEpochMillis == after.lastModifiedAtEpochMillis) { StoredMessage.THIS_PHOTO_CHANGED_WHILE_IT_WAS_BEING_STAGED.code }
        require(byteCount > 0L) { StoredMessage.THE_SELECTED_PHOTO_WAS_EMPTY.code }

        return ExactOriginalEvidence(
            contentUri = uri.toString(),
            displayName = format.fileName(after.displayName),
            mimeType = format.mimeType,
            lineageKey = after.lineageKey,
            sourceRevision = after.revision,
            acquisitionMethod = acquisitionMethod,
            originalFormatRequested = originalFormatRequested,
            unredacted = !isMediaStore || has(Manifest.permission.ACCESS_MEDIA_LOCATION),
            formatValidated = true,
            lastModifiedAtEpochMillis = after.lastModifiedAtEpochMillis,
            byteCount = byteCount,
            sha256Hex = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) },
        )
    }

    private fun openExactStream(uri: Uri, originalFormatRequested: Boolean): java.io.InputStream {
        if (!originalFormatRequested || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return resolver.openInputStream(uri) ?: error(StoredMessage.ANDROID_COULD_NOT_OPEN_THIS_PHOTO.code)
        }
        val descriptor = resolver.openTypedAssetFileDescriptor(
            uri,
            "*/*",
            Bundle().apply { putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true) },
        ) ?: error(StoredMessage.ANDROID_COULD_NOT_OPEN_THIS_PHOTO.code)
        return descriptor.createInputStream()
    }

    private fun readSourceMetadata(uri: Uri, displayNameHint: String?): SourceMetadata {
        val values = runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val displayName = cursor.stringOrNull(MediaStore.MediaColumns.DISPLAY_NAME)
                val size = cursor.longOrNull(MediaStore.MediaColumns.SIZE) ?: -1L
                val generation = cursor.longOrNull(MediaStore.MediaColumns.GENERATION_MODIFIED) ?: -1L
                val volume = cursor.stringOrNull(MediaStore.MediaColumns.VOLUME_NAME) ?: uri.authority.orEmpty()
                val rowId = cursor.longOrNull(MediaStore.MediaColumns._ID)
                    ?: uri.lastPathSegment?.toLongOrNull() ?: -1L
                SourceMetadata(
                    displayName = displayNameHint ?: displayName ?: "selected-media",
                    lastModifiedAtEpochMillis = cursor.longOrNull(MediaStore.MediaColumns.DATE_MODIFIED)?.takeIf { it > 0L }?.let { it * 1000L }
                        ?: cursor.longOrNull(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.takeIf { it > 0L },
                    revision = if (uri.authority == MediaStore.AUTHORITY) {
                        val version = MediaStore.getVersion(appContext, MediaStore.VOLUME_EXTERNAL)
                        mediaStoreRevision(version, volume, rowId, generation, size)
                    } else "$volume:$rowId:$generation:$size",
                    lineageKey = "$volume:$rowId",
                )
            }
        }.getOrNull()
        return values ?: SourceMetadata(
            displayName = displayNameHint ?: uri.lastPathSegment ?: "selected-media",
            revision = "$uri:unknown",
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
        val MEDIA_TYPES = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val DISCARDING_OUTPUT = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }
    }
}
