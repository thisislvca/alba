package me.lvca.alba.engine.model

/** How far Alba's synced copy of the iCloud library can be trusted to say what is already there. */
enum class LibraryIndexState {
    /** Synced recently for the account backup writes to. */
    READY,

    /** Synced for that account, but not recently: what it lists is still there to be checked, but it may miss new items. */
    STALE,

    /** Never fully read for that account yet. */
    NOT_SYNCED,

    /** There is no catalog for that account, as in the demo or under another account: backup uploads as it always did. */
    UNAVAILABLE,
}

/** An item of the synced iCloud library that may hold the same original as a phone item. */
data class LibraryCandidate(
    val mediaId: String,
    val masterRecordName: String,
    val assetRecordName: String,
    val fileName: String,
    /** The original's size as iCloud lists it. */
    val byteCount: Long,
    val capturedAtEpochMillis: Long,
)

/**
 * What the synced iCloud catalog knows about the library, so backup can find a phone item that is already there instead of
 * uploading it again. It only proposes candidates: nothing counts as backed up until its bytes are compared.
 */
interface CloudLibraryIndex {
    suspend fun state(binding: AccountBinding): LibraryIndexState

    /** Library items of the account whose original is exactly [byteCount] bytes, at most [limit] of them. */
    suspend fun candidates(binding: AccountBinding, byteCount: Long, limit: Int): List<LibraryCandidate>

    /** Asks for the catalog to be read or brought up to date in the background. */
    fun requestRefresh() = Unit

    companion object {
        /** No catalog: backup uploads everything, as before Alba looked for copies already in iCloud. */
        val NONE = object : CloudLibraryIndex {
            override suspend fun state(binding: AccountBinding) = LibraryIndexState.UNAVAILABLE
            override suspend fun candidates(binding: AccountBinding, byteCount: Long, limit: Int) = emptyList<LibraryCandidate>()
        }
    }
}
