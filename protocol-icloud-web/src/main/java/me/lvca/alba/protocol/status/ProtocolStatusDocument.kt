package me.lvca.alba.protocol.status

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.lvca.alba.engine.model.ProtocolArea
import me.lvca.alba.engine.model.ProtocolPause

/**
 * Alba's protocol status document, `status/protocol-status.json` in the repository:
 *
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "pauses": [
 *     {
 *       "id": "2026-11-upload-change",
 *       "areas": ["uploads"],
 *       "versions": { "min": "0.1.0", "max": "0.4.2" },
 *       "message": { "en": "Backup is paused while we update Alba for an iCloud change.", "it": "…" }
 *     }
 *   ]
 * }
 * ```
 *
 * `areas` takes the [ProtocolArea] keys, or `all`; a key this version does not know is ignored, so a newer document
 * never pauses something an older app cannot name. `versions` is optional, and so is each bound; both are inclusive
 * and compare the `major.minor.patch` of the app's version name. `message` is optional: a string, or an object by
 * language code. Unknown fields are ignored. A document that breaks these rules is refused as a whole, and the app
 * keeps its last good status, so a typo cannot lift a pause by accident.
 */
object ProtocolStatusDocument {
    /** The schema this version reads. A document with a higher one is read for the fields this version knows. */
    const val SCHEMA_VERSION = 1

    private const val MAX_ID_LENGTH = 100
    private const val MAX_MESSAGE_LENGTH = 500
    private val json = Json { ignoreUnknownKeys = true }

    /** The pauses [document] lists, for every app version. Throws [IllegalArgumentException] for a malformed one. */
    fun parse(document: String): List<PauseEntry> {
        val root = runCatching { json.parseToJsonElement(document) }.getOrNull() as? JsonObject
            ?: throw IllegalArgumentException("The status document is not a JSON object")
        val schema = (root["schemaVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
        require(schema != null && schema >= 1) { "The status document has no valid schemaVersion" }
        val pauses = when (val value = root["pauses"]) {
            null, JsonNull -> return emptyList()
            is JsonArray -> value
            else -> throw IllegalArgumentException("pauses must be a list")
        }
        val entries = pauses.mapIndexed { index, element -> entry(index, element) }
        require(entries.map { it.id }.toSet().size == entries.size) { "Every pause needs its own id" }
        return entries
    }

    private fun entry(index: Int, element: JsonElement): PauseEntry {
        val item = element as? JsonObject ?: throw IllegalArgumentException("pauses[$index] must be an object")
        val id = item.string("id")?.trim()
        require(!id.isNullOrEmpty() && id.length <= MAX_ID_LENGTH) { "pauses[$index] needs an id of 1 to $MAX_ID_LENGTH characters" }
        val keys = (item["areas"] as? JsonArray)?.map { area ->
            (area as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("pauses[$index].areas must be strings")
        }
        require(!keys.isNullOrEmpty()) { "pauses[$index] needs at least one area" }
        val areas = if (ProtocolArea.ALL in keys) ProtocolArea.entries.toSet() else keys.mapNotNull(ProtocolArea::fromKey).toSet()
        val versions = when (val value = item["versions"]) {
            null, JsonNull -> null
            is JsonObject -> value
            else -> throw IllegalArgumentException("pauses[$index].versions must be an object")
        }
        val min = versions?.string("min")?.let { AppVersion.parse(it) ?: throw IllegalArgumentException("pauses[$index].versions.min is not x.y.z") }
        val max = versions?.string("max")?.let { AppVersion.parse(it) ?: throw IllegalArgumentException("pauses[$index].versions.max is not x.y.z") }
        require(min == null || max == null || min <= max) { "pauses[$index].versions.min is after max" }
        return PauseEntry(id, areas, min, max, messages(index, item["message"]))
    }

    private fun messages(index: Int, value: JsonElement?): Map<String, String> {
        val messages = when (value) {
            null, JsonNull -> emptyMap()
            is JsonPrimitive -> {
                require(value.isString) { "pauses[$index].message must be text" }
                mapOf("en" to value.content)
            }
            is JsonObject -> value.mapValues { (language, text) ->
                (text as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("pauses[$index].message.$language must be text")
            }
            else -> throw IllegalArgumentException("pauses[$index].message must be text or an object by language")
        }
        val trimmed = messages.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        require(trimmed.values.all { it.length <= MAX_MESSAGE_LENGTH }) { "pauses[$index].message is too long" }
        return trimmed
    }

    private fun JsonObject.string(name: String): String? = when (val value = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeIf { it.isString }?.content ?: throw IllegalArgumentException("$name must be text")
        else -> throw IllegalArgumentException("$name must be text")
    }
}

/** One pause the document lists: the areas it covers, the app versions it applies to, and its message. */
data class PauseEntry(
    val id: String,
    val areas: Set<ProtocolArea>,
    val minVersion: AppVersion? = null,
    val maxVersion: AppVersion? = null,
    val messages: Map<String, String> = emptyMap(),
) {
    /**
     * Whether this pause applies to [version]. An app whose version cannot be read is covered only by a pause that
     * names no version bound, since it cannot be placed in a range.
     */
    fun appliesTo(version: AppVersion?): Boolean = when {
        minVersion == null && maxVersion == null -> true
        version == null -> false
        else -> (minVersion == null || version >= minVersion) && (maxVersion == null || version <= maxVersion)
    }

    fun toPause(): ProtocolPause = ProtocolPause(id, areas, messages)
}

/** The pauses in [entries] that apply to [version] and cover at least one area this version knows. */
fun effectivePauses(entries: List<PauseEntry>, version: AppVersion?): List<ProtocolPause> =
    entries.filter { it.areas.isNotEmpty() && it.appliesTo(version) }.map(PauseEntry::toPause)

/** An app version's `major.minor.patch`, as Alba's version names start. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})""")

        /** The leading `x.y.z` of [name], so "0.4.2-dev" is 0.4.2; null when it does not start with one. */
        fun parse(name: String): AppVersion? = PATTERN.find(name.trim())?.destructured?.let { (major, minor, patch) ->
            AppVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}
