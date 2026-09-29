package dev.mela.engine.model

enum class LocalMediaCategory { VIEWERS, PREVIEWS, ORIGINALS }

data class LocalMediaStorage(
    val previewBytes: Long,
    val viewerBytes: Long,
    val originalBytes: Long,
    val availableBytes: Long,
) {
    val totalBytes: Long get() = previewBytes + viewerBytes + originalBytes
}
