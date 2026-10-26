package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import me.lvca.alba.engine.source.CatalogCursorRejected
import me.lvca.alba.engine.source.CatalogResetRequired
import me.lvca.alba.protocol.array
import me.lvca.alba.protocol.auth.AppleProtocolError
import me.lvca.alba.protocol.auth.AppleProtocolException
import me.lvca.alba.protocol.auth.AppleThrottledException
import me.lvca.alba.protocol.network.AppleHttpResponse

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
    fun zoneChangesFailure(code: String, retryAfterMillis: Long? = null): Exception = when (code) {
        in ZONE_CHANGES_RESET_CODES -> CatalogResetRequired()
        in THROTTLE_CODES -> AppleThrottledException("Apple Photos is busy ($code). Wait a moment and refresh.", retryAfterMillis)
        else -> AppleProtocolException(AppleProtocolError.NETWORK, "Apple Photos could not list library changes ($code). Wait a moment and refresh.")
    }

    /** Throws when CloudKit refused [root]: an unusable change token, throttling, or any other request error. */
    fun requireAccepted(root: JsonObject) {
        if (requiresSyncReset(root)) throw CatalogResetRequired()
        throttle(root)?.let { throw it }
        check(!rejectedRequest(root)) { "Apple rejected the library request" }
    }

    /**
     * A refused page cursor, for a [resumed] page query that failed with [httpCode] or whose [root] carries a request
     * error. Sign-in (401, 403) and throttling (429) failures are not about the cursor and are left to their own handling.
     */
    fun cursorRejection(httpCode: Int, root: JsonObject?, resumed: Boolean): CatalogCursorRejected? = when {
        !resumed -> null
        httpCode in CLIENT_ERRORS && httpCode !in NOT_ABOUT_THE_CURSOR -> CatalogCursorRejected("Apple refused the saved page (HTTP $httpCode)")
        root != null && rejectedRequest(root) -> CatalogCursorRejected("Apple refused the saved page (${root.serverErrorCode()})")
        else -> null
    }

    /** The throttling error in [root], a request CloudKit answered but asked to be sent again later, or null. */
    fun throttle(root: JsonObject): AppleThrottledException? {
        val code = serverErrorCodes(root).firstOrNull { it in THROTTLE_CODES } ?: return null
        return AppleThrottledException("Apple Photos is busy ($code). Wait a moment and refresh.", retryAfterMillis(root))
    }

    /** The wait [response] asked for, from its Retry-After header or CloudKit's `retryAfter`, in milliseconds. */
    fun retryAfterMillis(response: AppleHttpResponse): Long? =
        response.header("retry-after")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(MILLIS_PER_SECOND)
            ?: runCatching { Json.parseToJsonElement(response.body) as? JsonObject }.getOrNull()?.let(::retryAfterMillis)

    /** CloudKit's `retryAfter`, in seconds, on the request or the first error that has one, in milliseconds. */
    fun retryAfterMillis(root: JsonObject): Long? =
        (sequenceOf(root) + root.entries("errors") + root.entries("records") + root.entries("zones"))
            .mapNotNull { (it["retryAfter"] as? JsonPrimitive)?.longOrNull?.takeIf { seconds -> seconds >= 0 } }
            .firstOrNull()?.times(MILLIS_PER_SECOND)

    /** Codes CloudKit Web Services uses for a request it may accept later. */
    private val THROTTLE_CODES = setOf("THROTTLED", "TRY_AGAIN_LATER", "ZONE_BUSY")
    private const val MILLIS_PER_SECOND = 1_000L
    private val CLIENT_ERRORS = 400..499

    /** Sign-in and throttling statuses, which have their own handling. */
    private val NOT_ABOUT_THE_CURSOR = setOf(401, 403, 429)

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
