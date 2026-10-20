package me.lvca.alba.engine.source

/**
 * Identifies one version of a MediaStore row. Staging refuses a row whose revision changed, and stored
 * revisions are compared across releases, so the format must stay stable.
 */
internal fun mediaStoreRevision(
    volumeVersion: String,
    volumeName: String,
    mediaStoreId: Long,
    generationModified: Long,
    byteCount: Long,
): String = "$volumeVersion:$volumeName:$mediaStoreId:$generationModified:$byteCount"
