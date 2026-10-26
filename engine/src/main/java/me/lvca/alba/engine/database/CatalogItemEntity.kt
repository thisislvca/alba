package me.lvca.alba.engine.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "catalog_items",
    indices = [
        Index(value = ["capturedAtEpochMillis", "mediaId"]),
        Index(value = ["origin", "lastSeenScanId"]),
        // Library changes name iCloud records, not media ids: these find the rows a change touches.
        Index(value = ["masterRecordName"]),
        Index(value = ["assetRecordName"]),
    ],
)
data class CatalogItemEntity(
    @PrimaryKey val mediaId: String,
    val fileName: String,
    val capturedAtEpochMillis: Long,
    val width: Int,
    val height: Int,
    val origin: String,
    val sourceRevision: String,
    val localUri: String?,
    val previewCachePath: String?,
    val originalCachePath: String?,
    val accentStartArgb: Long,
    val accentEndArgb: Long,
    val lastSeenScanId: String,
    @androidx.room.ColumnInfo(defaultValue = "'PHOTO'") val kind: String = "PHOTO",
    @androidx.room.ColumnInfo(defaultValue = "'image/jpeg'") val mimeType: String = "image/jpeg",
    val durationMillis: Long? = null,
    val motionCachePath: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "'video/quicktime'") val motionMimeType: String = "video/quicktime",
    val masterRecordName: String? = null,
    val assetRecordName: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "''") val resourceFingerprint: String = "",
    val addedAtEpochMillis: Long? = null,
    val viewerCachePath: String? = null,
    val byteCount: Long? = null,
    val deviceFolderId: String? = null,
    val deviceFolderName: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val localFavorite: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "0") val isTrashed: Boolean = false,
    val expiresAtEpochMillis: Long? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val isHidden: Boolean = false,
    /** Null when the row has no details at all; read it through [mediaDetails]. */
    @androidx.room.Embedded val details: me.lvca.alba.engine.model.MediaDetails? = null,
)

internal val CatalogItemEntity.mediaDetails: me.lvca.alba.engine.model.MediaDetails
    get() = details ?: me.lvca.alba.engine.model.MediaDetails()

@Entity(tableName = "catalog_checkpoints")
data class CatalogCheckpointEntity(
    @PrimaryKey val scope: String,
    val changeToken: String,
    val refreshedAtEpochMillis: Long,
)

/**
 * A first full read of an account's iCloud library that has not finished yet. Each page is written to the catalog as
 * it arrives, tagged with [scanId], and this row records where to continue, so the read survives process death, a
 * lost connection or the app leaving the screen. It is removed when the read is published.
 */
@Entity(tableName = "catalog_scans")
data class CatalogScanEntity(
    /** The account label the read belongs to. A read never continues under another account. */
    @PrimaryKey val account: String,
    val scanId: String,
    /** The next page to read; null before the first page. */
    val cursor: String?,
    /** Every page is read: what is left is catching up on changes made meanwhile, then publishing. */
    val pagesComplete: Boolean,
    /** The change token captured before the first page, to catch up from. Null when the source has none. */
    val catchUpToken: String?,
    /** Photos and videos read so far. */
    val loadedCount: Int,
    /** How many items iCloud says the library has, when it could say so cheaply. */
    val totalCount: Int?,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
