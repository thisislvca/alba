package me.lvca.alba.protocol.photos

/** Writes what the phone knew about an uploaded photo onto its iCloud asset, through the library's own edits. */
fun interface UploadMetadataEditor {
    /** Adds [favorite] and [caption] to [assetRecordName] in [accountId]'s library; never clears or replaces either. */
    suspend fun applyPhoneMetadata(accountId: String, assetRecordName: String, favorite: Boolean, caption: String?)
}
