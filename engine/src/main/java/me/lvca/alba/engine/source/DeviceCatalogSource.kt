package me.lvca.alba.engine.source

import java.io.OutputStream

data class DeviceMediaRecord(
    val id: String,
    val fileName: String,
    val capturedAtEpochMillis: Long,
    val width: Int,
    val height: Int,
    val contentUri: String,
    val sourceRevision: String,
    val volumeName: String = "external",
    val volumeVersion: String = "legacy",
    val mediaStoreId: Long = contentUri.substringAfterLast('/').toLongOrNull() ?: 0L,
    val generationModified: Long = sourceRevision.toLongOrNull() ?: 0L,
    val mimeType: String = "image/jpeg",
    val byteCount: Long = 0L,
    val addedAtEpochMillis: Long? = null,
    val durationMillis: Long? = null,
    val deviceFolderId: String? = null,
    val deviceFolderName: String? = null,
    val isTrashed: Boolean = false,
    val expiresAtEpochMillis: Long? = null,

)

data class DeviceOriginalEvidence(
    val byteCount: Long,
    val sha256Hex: String,
)

interface DeviceCatalogSource {
    suspend fun scanImages(): List<DeviceMediaRecord>

    suspend fun writeOriginal(contentUri: String, output: OutputStream): DeviceOriginalEvidence
}

/** The id of a MediaStore row. The gallery, uploads and verification proofs all name a phone item by it. */
internal fun deviceMediaId(video: Boolean, volume: String, id: Long): String =
    if (video) "device:video:$volume:$id" else "device:$volume:$id"
