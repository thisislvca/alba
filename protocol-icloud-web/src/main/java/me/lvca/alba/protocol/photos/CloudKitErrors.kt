package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.lvca.alba.protocol.array

/**
 * Typed CloudKit Web Services errors. A code is read only from a `serverErrorCode` property, never
 * from record names or field values, which hold user content such as album and file names.
 */
internal object CloudKitErrors {
    private val SYNC_TOKEN_RESET_CODES = setOf("SYNC_TOKEN_EXPIRED", "INVALID_SYNC_TOKEN")

    /** Codes for the request itself, its `errors` entries, and each zone and record it returned. */
    fun serverErrorCodes(root: JsonObject): List<String> = buildList {
        root.serverErrorCode()?.let(::add)
        root.entries("errors").forEach { it.serverErrorCode()?.let(::add) }
        root.entries("records").forEach { it.serverErrorCode()?.let(::add) }
        root.entries("zones").forEach { zone ->
            zone.serverErrorCode()?.let(::add)
            zone.entries("records").forEach { it.serverErrorCode()?.let(::add) }
        }
    }

    /** True when CloudKit rejected the request as a whole, which it can do with a 2xx status. */
    fun rejectedRequest(root: JsonObject): Boolean = root.serverErrorCode() != null || root.array("errors").isNotEmpty()

    /** True when CloudKit no longer accepts the saved change token and the catalog must be rebuilt. */
    fun requiresSyncReset(root: JsonObject): Boolean = serverErrorCodes(root).any { it in SYNC_TOKEN_RESET_CODES }

    fun JsonObject.serverErrorCode(): String? =
        (this["serverErrorCode"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.entries(name: String): List<JsonObject> =
        array(name).filterIsInstance<JsonObject>()
}
