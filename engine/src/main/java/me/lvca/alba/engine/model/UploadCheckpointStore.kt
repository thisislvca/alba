package me.lvca.alba.engine.model

/** One upload checkpoint as stored. The engine never reads [sealedPayload]; the protocol seals and opens it. */
class SealedUploadCheckpoint(
    val attemptId: String,
    val phase: String,
    val sealedPayload: ByteArray,
    val updatedAtEpochMillis: Long,
)

/** Durable storage for the protocol's upload journal, keyed by upload attempt. */
interface UploadCheckpointStore {
    suspend fun load(attemptId: String): SealedUploadCheckpoint?

    suspend fun save(checkpoint: SealedUploadCheckpoint)
}
