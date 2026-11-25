package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import me.lvca.alba.protocol.auth.AppleProtocolError
import me.lvca.alba.protocol.auth.AppleProtocolException
import me.lvca.alba.protocol.field
import me.lvca.alba.protocol.fieldText

/**
 * What the library's CheckIndexingState record says about Apple's server-side indexes. Seen live: `state` RUNNING,
 * FINISHED or FAILED, `progress` a number from 0 to 100, an `indexJobId` and, when FAILED, an `error` text.
 *
 * The record is advisory. iCloud.com never asks for it and reads libraries it calls FAILED (progress 100, a missing
 * CPLContainerRelation index) or RUNNING (a new job at 0% on a library already loaded) in full. pyicloud blocks on
 * anything but FINISHED; Alba waits only when the record says [inProgress] and the library has nothing to read yet.
 */
data class LibraryIndexing(
    val state: String?,
    /** From 0 to 100, when Apple said. */
    val progress: Int?,
    /** Apple's error text for a FAILED state. Not user data, but it stays in the protocol; diagnostics get [missingIndex]. */
    val error: String?,
) {
    val failed: Boolean get() = state == FAILED

    /** A FAILED state that names a missing index, as Apple answered for a library iCloud.com shows in full. */
    val missingIndex: Boolean get() = failed && error?.contains(MISSING_INDEX) == true

    /** Apple says it is still building the indexes: any state but FINISHED or FAILED, unless it reports them complete. */
    val inProgress: Boolean get() = state != null && state != FINISHED && state != FAILED && (progress == null || progress < COMPLETE)

    companion object {
        const val FINISHED = "FINISHED"
        const val FAILED = "FAILED"
        private const val COMPLETE = 100
        private const val MISSING_INDEX = "No index with requested name"

        /** The first record of a CheckIndexingState query, or null when Apple returned none, as a library read proceeds. */
        fun parse(response: JsonObject?): LibraryIndexing? {
            val record = (response?.get("records") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            return LibraryIndexing(
                state = record.fieldText("state"),
                progress = (record.field("progress") as? JsonPrimitive)?.let { value ->
                    // A number, or a numeric string; anything else, or out of range, is not a progress.
                    (value.doubleOrNull ?: value.content.toDoubleOrNull())?.takeIf { it.isFinite() && it in 0.0..COMPLETE.toDouble() }
                }?.toInt(),
                error = record.fieldText("error"),
            )
        }
    }
}

/** iCloud Photos is still indexing this library; it is not empty, and Alba reads it once [progress] completes. */
class LibraryPreparingException(val progress: Int?) : AppleProtocolException(
    AppleProtocolError.LIBRARY_PREPARING,
    "iCloud Photos is still preparing this library.",
)
