package me.lvca.alba.engine.database

import me.lvca.alba.engine.model.SealedUploadCheckpoint
import me.lvca.alba.engine.model.UploadCheckpointStore

/** Keeps upload checkpoints in the upload_checkpoints table. */
class RoomUploadCheckpointStore(database: AlbaDatabase) : UploadCheckpointStore {
    private val dao = database.libraryDao()

    override suspend fun load(attemptId: String): SealedUploadCheckpoint? = dao.checkpoint(attemptId)?.let { row ->
        SealedUploadCheckpoint(row.attemptId, row.phase, row.sealedPayload, row.updatedAt)
    }

    override suspend fun save(checkpoint: SealedUploadCheckpoint) {
        dao.putCheckpoint(
            UploadCheckpointEntity(checkpoint.attemptId, checkpoint.phase, checkpoint.sealedPayload, checkpoint.updatedAtEpochMillis),
        )
    }
}
