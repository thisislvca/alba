package me.lvca.alba.engine.companion

import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.StagedSourceEntity

/** Deletes privately staged originals once no transfer can still upload or verify them. */
internal class UploadStages(
    database: AlbaDatabase,
    private val staging: UploadStagingStore,
    private val now: () -> Long,
) {
    private val transferDao = database.transferDao()

    suspend fun sweepOrphans() {
        staging.sweepOrphans(transferDao.findOwnedStageTokens().toSet())
    }

    suspend fun deleteReleasable() {
        val releasedSourceIds = transferDao.findReleasableStages().mapNotNull { source ->
            source.stagedSourceId.takeIf { staging.deleteOwned(source.ownedFileToken) }
        }
        if (releasedSourceIds.isNotEmpty()) {
            transferDao.markStagesReleased(releasedSourceIds, now())
        }
    }

    suspend fun release(source: StagedSourceEntity) {
        if (staging.deleteOwned(source.ownedFileToken)) {
            transferDao.markStagesReleased(listOf(source.stagedSourceId), now())
        }
    }
}
