package me.lvca.alba.engine.model

enum class LocalMediaCategory { VIEWERS, PREVIEWS, ORIGINALS }

data class LocalMediaStorage(
    val previewBytes: Long,
    val viewerBytes: Long,
    val originalBytes: Long,
    val availableBytes: Long,
    /** How many previews [previewBytes] holds, to estimate what the whole library would take. */
    val previewCount: Int = 0,
) {
    val totalBytes: Long get() = previewBytes + viewerBytes + originalBytes

    /** The measured average preview, or null before any preview is on the phone. */
    val averagePreviewBytes: Long? get() = if (previewCount > 0) previewBytes / previewCount else null
}
