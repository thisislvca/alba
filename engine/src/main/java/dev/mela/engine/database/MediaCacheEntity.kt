package dev.mela.engine.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Cache writes must not invalidate the catalog and its ordering. */
@Entity(tableName = "media_cache", foreignKeys = [ForeignKey(
    entity = CatalogItemEntity::class, parentColumns = ["mediaId"], childColumns = ["mediaId"],
    onDelete = ForeignKey.CASCADE,
)])
data class MediaCacheEntity(
    @PrimaryKey val mediaId: String,
    val previewCachePath: String? = null,
    val viewerCachePath: String? = null,
    val originalCachePath: String? = null,
    val motionCachePath: String? = null,
    val resourceFingerprint: String = "",
) {
    fun applyTo(item: CatalogItemEntity) = if (resourceFingerprint != item.resourceFingerprint) item else item.copy(
        previewCachePath = previewCachePath, viewerCachePath = viewerCachePath,
        originalCachePath = originalCachePath, motionCachePath = motionCachePath,
    )
}
