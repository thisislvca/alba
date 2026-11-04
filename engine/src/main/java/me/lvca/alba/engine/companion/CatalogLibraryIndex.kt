package me.lvca.alba.engine.companion

import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.CloudLibraryIndex
import me.lvca.alba.engine.model.LibraryCandidate
import me.lvca.alba.engine.model.LibraryIndexState

/**
 * The iCloud library as the gallery last synced it into Room. Its rows are named `icloud:<namespace>:<asset record>`
 * and its read is complete once a `icloud:<namespace>` checkpoint exists (a first read in progress has none). It speaks
 * for the account backup writes to only while the catalog's source is signed in to that same account.
 */
class CatalogLibraryIndex(
    database: AlbaDatabase,
    /** The catalog's namespace for the signed-in account, as the catalog source names it. */
    private val namespace: () -> String?,
    /** The account id the catalog source is signed in to, comparable with [AccountBinding.accountId]. */
    private val accountId: () -> String?,
    private val refresh: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
) : CloudLibraryIndex {
    private val catalog = database.catalogDao()

    override suspend fun state(binding: AccountBinding): LibraryIndexState {
        val space = namespace()?.takeIf { it.isNotBlank() } ?: return LibraryIndexState.UNAVAILABLE
        if (accountId() != binding.accountId) return LibraryIndexState.UNAVAILABLE
        val checkpoint = catalog.checkpoint("icloud:$space") ?: return LibraryIndexState.NOT_SYNCED
        return if (now() - checkpoint.refreshedAtEpochMillis > maxAgeMillis) LibraryIndexState.STALE else LibraryIndexState.READY
    }

    override suspend fun candidates(binding: AccountBinding, byteCount: Long, limit: Int): List<LibraryCandidate> {
        if (state(binding) == LibraryIndexState.UNAVAILABLE) return emptyList()
        val space = namespace() ?: return emptyList()
        return catalog.librarySameSize("icloud:$space:", byteCount, limit).mapNotNull { row ->
            LibraryCandidate(
                mediaId = row.mediaId,
                masterRecordName = row.masterRecordName ?: return@mapNotNull null,
                assetRecordName = row.assetRecordName ?: return@mapNotNull null,
                fileName = row.fileName,
                byteCount = row.byteCount ?: return@mapNotNull null,
                capturedAtEpochMillis = row.capturedAtEpochMillis,
            )
        }
    }

    override fun requestRefresh() = refresh()

    companion object {
        /** A catalog synced longer ago than this is brought up to date before more items are looked up in it. */
        const val DEFAULT_MAX_AGE_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
