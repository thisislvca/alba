package me.lvca.alba.engine.model

/** Progress of one photo in a user batch: keep offline, remove a cached copy, upload or save to the phone. */
enum class BatchItemState {
    WAITING,
    RUNNING,
    DONE,
    FAILED,
    NEEDS_ATTENTION,
    STOPPED,
    CANCELED;

    /** Still queued or running, so the batch can be canceled. */
    val isPending: Boolean get() = this == WAITING || this == RUNNING

    companion object {
        /** Reads a stored state. Text no release writes reads as needing attention. */
        fun fromStored(value: String): BatchItemState = entries.firstOrNull { it.name == value } ?: NEEDS_ATTENTION
    }
}

/** One photo in a user batch, as the UI shows it. [action] is a `BatchAction` name. */
data class BatchItem(
    val batchId: String,
    val mediaId: String,
    val account: String,
    val action: String,
    val status: BatchItemState,
    val message: String?,
    val createdAt: Long,
)
