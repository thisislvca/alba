package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.*
import me.lvca.alba.engine.source.CatalogResetRequired
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.network.AppleClientProfile
import me.lvca.alba.protocol.network.AppleHttpRequest
import me.lvca.alba.protocol.recordType

/** The catalog's requests to the personal library's PrimarySync zone in the private CloudKit database. */
internal class PersonalLibraryDatabase(
    private val cloudKit: CloudKitClient,
    private val requireSession: () -> AppleSessionSnapshot,
    private val isAuthorized: () -> Boolean,
) {
    /** The library database URL for [path]. The catalog always asks for the current sync token. */
    fun url(session: AppleSessionSnapshot, path: String): String =
        cloudKit.withSessionQuery(cloudKit.databaseUrl(session, "private", path), session, currentSyncToken = true)

    /** Sends [body] to [path] for the signed-in session. An expired change token surfaces as [CatalogResetRequired]. */
    suspend fun call(path: String, body: JsonObject): JsonObject {
        val session = requireSession()
        cloudKit.bind(session)
        val raw = cloudKit.exchange(session, AppleHttpRequest("POST", url(session, path), HEADERS, body.toString(), oneShot = path == "records/modify"))
        // CloudKit reports an expired change token as a typed error, sometimes with a non-2xx status.
        val parsed = runCatching { Json.parseToJsonElement(raw.body) as? JsonObject }.getOrNull()
        if (path == "changes/zone" && (raw.code == 410 || parsed?.let(CloudKitErrors::requiresSyncReset) == true)) throw CatalogResetRequired()
        cloudKit.requireSuccess(session, raw)
        val root = parsed ?: Json.parseToJsonElement(raw.body).jsonObject
        CloudKitErrors.requireAccepted(root)
        return root
    }

    suspend fun lookup(names: Collection<String>): List<JsonObject> = PrimarySyncZone.lookupInBatches(names, LOOKUP_BATCH_SIZE) { batch ->
        call("records/lookup", PrimarySyncZone.lookupBody(batch))["records"]!!.jsonArray.map { it.jsonObject }
    }

    /**
     * Every record of the query [type], optionally filtered to [field] equal to [value]. A [ranked]
     * query is paged by start rank as well as by continuation marker.
     */
    suspend fun queryAll(type: String, field: String? = null, value: String? = null, ranked: Boolean = false): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val visited = mutableSetOf<String>()
        var marker: String? = null
        var offset = 0
        var logicalCount = 0
        var nextRank: Boolean
        do {
            check(visited.add("$offset:${marker.orEmpty()}")) { "Repeated collection cursor" }
            val page = call("records/query", buildJsonObject {
                put("zoneID", PrimarySyncZone.zoneId()); put("resultsLimit", 200)
                putJsonObject("query") {
                    put("recordType", type)
                    putJsonArray("filterBy") {
                        fun filter(key: String, item: String, kind: String = "STRING") = buildJsonObject {
                            put("fieldName", key); put("comparator", "EQUALS")
                            putJsonObject("fieldValue") { put("type", kind); if (kind == "INT64") put("value", item.toLong()) else put("value", item) }
                        }
                        if (field != null) add(filter(field, value!!))
                        if (ranked) { add(filter("direction", "ASCENDING")); add(filter("startRank", offset.toString(), "INT64")) }
                    }
                }
                marker?.let { put("continuationMarker", it) }
            })
            val records = page["records"]!!.jsonArray.map { it.jsonObject }
            result += records
            logicalCount += records.count { it.recordType() in setOf("CPLAsset", "CPLContainerRelation") }
            check(result.size <= 200_000) { "Collection is too large" }
            marker = page["continuationMarker"]?.jsonPrimitive?.contentOrNull
            val logicalLimit = if (type.contains("AssetAndMaster")) 100 else 200
            nextRank = ranked && marker == null && logicalCount >= logicalLimit
            if (nextRank) { offset += logicalCount; logicalCount = 0 }
        } while (marker != null || nextRank)
        return result
    }

    /** Fails when the account or its authorization changed since [session] was read. */
    fun requireUnchanged(session: AppleSessionSnapshot) {
        val current = requireSession()
        check(current.dsid == session.dsid && current.clientId == session.clientId && isAuthorized()) {
            "The account connection changed. Refresh to check the result."
        }
    }

    companion object {
        val HEADERS = mapOf("Content-Type" to AppleClientProfile.WEB_SERVICE_CONTENT_TYPE)
        private const val LOOKUP_BATCH_SIZE = 50
    }
}
