package me.lvca.alba.engine.companion

import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.StagedSourceEntity

/**
 * Deletes privately staged originals once no transfer can still upload or verify them. A source read in place has no
 * private file, so it is simply marked released.
 */
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
            source.stagedSourceId.takeIf { isInPlace(source.ownedFileToken) || staging.deleteOwned(source.ownedFileToken) }
        }
        if (releasedSourceIds.isNotEmpty()) {
            transferDao.markStagesReleased(releasedSourceIds, now())
        }
    }

    private fun isInPlace(token: String) = token.startsWith(IN_PLACE_TOKEN_PREFIX)

    suspend fun release(source: StagedSourceEntity) {
        if (source.readsInPlace || staging.deleteOwned(source.ownedFileToken)) {
            transferDao.markStagesReleased(listOf(source.stagedSourceId), now())
        }
    }
}
