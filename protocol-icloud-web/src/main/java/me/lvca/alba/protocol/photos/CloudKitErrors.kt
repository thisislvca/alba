package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.lvca.alba.engine.source.CatalogResetRequired
import me.lvca.alba.protocol.array
import me.lvca.alba.protocol.auth.AppleProtocolError
import me.lvca.alba.protocol.auth.AppleProtocolException

/**
 * Typed CloudKit Web Services errors. A code is read only from a `serverErrorCode` property, never
 * from record names or field values, which hold user content such as album and file names.
 */
internal object CloudKitErrors {
    private val SYNC_TOKEN_RESET_CODES = setOf("SYNC_TOKEN_EXPIRED", "INVALID_SYNC_TOKEN")

    /** iCloud.com answers a corrupt saved change token with a zone-level BAD_REQUEST, observed on 2026-10-20. */
    private val ZONE_CHANGES_RESET_CODES = SYNC_TOKEN_RESET_CODES + "BAD_REQUEST"

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

    /**
     * Why `changes/zone` failed for a zone that returned [code]. Only an unusable change token rebuilds the catalog.
     * Anything else, such as THROTTLED or ZONE_BUSY, keeps the token, so a later refresh can continue from it.
     */
    fun zoneChangesFailure(code: String): Exception =
        if (code in ZONE_CHANGES_RESET_CODES) CatalogResetRequired()
        else AppleProtocolException(AppleProtocolError.NETWORK, "Apple Photos could not list library changes ($code). Wait a moment and refresh.")

    /**
     * True when Apple refused a write because the account's iCloud storage is full. CloudKit Web Services reports this
     * as `QUOTA_EXCEEDED` with HTTP 413; a 413 on a small JSON request cannot mean the request itself was too large.
     */
    fun quotaExceeded(httpCode: Int, root: JsonObject?): Boolean =
        httpCode == QUOTA_HTTP_STATUS || (root != null && QUOTA_EXCEEDED in serverErrorCodes(root))

    const val QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
    const val QUOTA_HTTP_STATUS = 413

    fun JsonObject.serverErrorCode(): String? =
        (this["serverErrorCode"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.entries(name: String): List<JsonObject> =
        array(name).filterIsInstance<JsonObject>()
}
