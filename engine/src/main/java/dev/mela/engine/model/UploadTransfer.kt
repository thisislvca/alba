package dev.mela.engine.model

data class UploadTransfer(
    val transferId: String,
    val mediaId: String,
    val fileName: String,
    val accountLabel: String,
    val state: UploadTransferState,
    val byteCount: Long,
    val sha256Hex: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val message: String?,
)

enum class UploadTransferState {
    READY_TO_UPLOAD,
    NEEDS_SIGN_IN,
    NEEDS_ATTENTION,
}
