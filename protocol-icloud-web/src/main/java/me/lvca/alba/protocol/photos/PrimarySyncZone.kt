package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The personal library's CloudKit zone, and the record reads the catalog and upload protocol share. */
internal object PrimarySyncZone {
    const val NAME = "PrimarySync"
    const val TYPE = "REGULAR_CUSTOM_ZONE"

    fun zoneId(): JsonObject = buildJsonObject {
        put("zoneName", NAME)
        put("zoneType", TYPE)
    }

    /** A records/lookup body for [names]. [desiredKeys] limits the fields Apple returns. */
    fun lookupBody(names: List<String>, desiredKeys: List<String>? = null): JsonObject = buildJsonObject {
        put("zoneID", zoneId())
        putJsonArray("records") { names.forEach { name -> add(buildJsonObject { put("recordName", name) }) } }
        desiredKeys?.let { keys -> putJsonArray("desiredKeys") { keys.forEach { add(JsonPrimitive(it)) } } }
    }

    /** A changes/zone body for up to [limit] changed records after [syncToken], oldest first. */
    fun changesBody(syncToken: String, limit: Int): JsonObject = buildJsonObject {
        putJsonArray("zones") {
            add(buildJsonObject {
                put("zoneID", zoneId())
                put("syncToken", syncToken)
                put("reverse", false)
            })
        }
        put("resultsLimit", limit)
    }

    /** Looks up the distinct [names] in requests of at most [batchSize] records. */
    suspend fun lookupInBatches(
        names: Collection<String>,
        batchSize: Int,
        lookup: suspend (List<String>) -> List<JsonObject>,
    ): List<JsonObject> = names.distinct().chunked(batchSize).flatMap { lookup(it) }
}
