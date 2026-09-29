package dev.mela.engine.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "catalog_items",
    indices = [
        Index(value = ["capturedAtEpochMillis", "mediaId"]),
        Index(value = ["origin", "lastSeenScanId"]),
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

)

@Entity(tableName = "catalog_checkpoints")
data class CatalogCheckpointEntity(
    @PrimaryKey val scope: String,
    val changeToken: String,
    val refreshedAtEpochMillis: Long,
)
