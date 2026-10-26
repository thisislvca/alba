package me.lvca.alba.protocol.photos

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.fieldFlag
import me.lvca.alba.protocol.fieldInt
import me.lvca.alba.protocol.fieldText
import me.lvca.alba.protocol.isRemovedFromLibrary
import me.lvca.alba.protocol.recordName
import me.lvca.alba.protocol.recordType
import me.lvca.alba.protocol.text
import java.util.Base64
import java.util.UUID

/**
 * Edits to the personal library: favorites, albums, album membership and Recently Deleted. Edits run
 * one at a time, write at the change tag they read, and are confirmed from Apple's response.
 */
internal class PersonalLibraryEdits(
    private val database: PersonalLibraryDatabase,
    private val requireSession: () -> AppleSessionSnapshot,
    private val isAuthorized: () -> Boolean,
) {
    private val editLock = Mutex()

    private suspend fun <T> authorizedEdit(action: suspend (AppleSessionSnapshot) -> T): T = editLock.withLock {
        val session = requireSession()
        check(isAuthorized()) { "Reconnect to iCloud before editing the library." }
        action(session)
    }

    private fun mediaRecordName(id: String, session: AppleSessionSnapshot): String {
        val prefix = "icloud:${ICloudAccountIdentity.namespace(session)}:"
        require(id.startsWith(prefix)) { "Photo belongs to another account." }
        return id.removePrefix(prefix).also { require(it.isNotBlank() && ':' !in it) }
    }

    private suspend fun editableRecord(name: String, type: String): JsonObject = database.lookup(listOf(name)).single().also {
        check(it["serverErrorCode"] == null && it.recordType() == type && !it.isRemovedFromLibrary()) {
            "This item is no longer editable. Refresh the library."
        }
        check(it.text("recordChangeTag") != null) { "Apple omitted the edit version. Refresh the library." }
    }

    private suspend fun editablePersonalAlbum(id: String): JsonObject {
        require(!id.startsWith("smart:"))
        return editableRecord(id, "CPLAlbum").also { check(it.fieldInt("albumType") == 0) { "Choose a personal album." } }
    }

    private fun writeField(type: String, value: JsonElement) = buildJsonObject { put("type", type); put("value", value) }

    private fun operation(type: String, record: JsonObject) = buildJsonObject { put("operationType", type); put("record", record) }

    private fun JsonObject.operationRecordName() = this["record"]!!.jsonObject["recordName"]!!.jsonPrimitive.content

    /** Applies [operations] atomically and returns Apple's records, which must match them one to one. */
    private suspend fun modify(session: AppleSessionSnapshot, operations: List<JsonObject>): List<JsonObject> {
        database.requireUnchanged(session)
        val root = database.call("records/modify", buildJsonObject {
            put("zoneID", PrimarySyncZone.zoneId()); put("atomic", true); put("operations", JsonArray(operations))
        })
        database.requireUnchanged(session)
        val results = root["records"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val expected = operations.map { it.operationRecordName() }.toSet()
        check(results.size == expected.size && results.all { it.recordName() in expected && it["serverErrorCode"] == null } &&
            results.mapNotNull { it.recordName() }.toSet() == expected) {
            "Apple did not confirm the edit. Refresh the library before trying again."
        }
        return results
    }

    suspend fun setFavorite(mediaId: String, favorite: Boolean) = authorizedEdit { session ->
        val name = mediaRecordName(mediaId, session)
        val existing = editableRecord(name, "CPLAsset")
        val result = modify(session, listOf(operation("update", buildJsonObject {
            put("recordName", name); put("recordType", "CPLAsset"); put("recordChangeTag", existing["recordChangeTag"]!!)
            putJsonObject("fields") { put("isFavorite", writeField("INT64", JsonPrimitive(if (favorite) 1 else 0))) }
        }))).single()
        check(result.fieldText("isFavorite") == if (favorite) "1" else "0") {
            "Favorite change was not confirmed. Refresh the library."
        }
    }

    /**
     * Moves assets into or out of the Hidden album by writing CPLAsset `isHidden`, the INT64 flag the Hidden index
     * (`CPLAssetAndMasterHiddenByAssetDate`) and the main feed (`...WithoutHiddenOrDeleted`) read. It is written like
     * `isFavorite`: at the version just read, atomically, and confirmed from Apple's response.
     */
    suspend fun setHidden(mediaIds: List<String>, hidden: Boolean) = authorizedEdit { session ->
        val names = mediaIds.distinct().map { mediaRecordName(it, session) }
        require(names.size in 1..50)
        val records = database.lookup(names)
        check(records.mapNotNull { it.recordName() }.toSet() == names.toSet() && records.size == names.size)
        val operations = records.mapNotNull { record ->
            check(record.isTrashable(requireVisible = false) && !record.fieldFlag("isDeleted")) {
                "This photo is no longer available. Refresh the library."
            }
            if (record.fieldFlag("isHidden") == hidden) return@mapNotNull null
            operation("update", buildJsonObject {
                put("recordName", record.recordName()!!); put("recordType", "CPLAsset")
                put("recordChangeTag", checkNotNull(record.text("recordChangeTag")?.takeIf { it.isNotBlank() }))
                putJsonObject("fields") { put("isHidden", writeField("INT64", JsonPrimitive(if (hidden) 1 else 0))) }
            })
        }
        if (operations.isNotEmpty()) {
            val result = modify(session, operations)
            check(result.all { it.fieldFlag("isHidden") == hidden && it.fieldText("isHidden") != null }) {
                "iCloud did not confirm the change to Hidden. Refresh before retrying."
            }
        }
        database.requireUnchanged(session)
    }

    private fun albumName(name: String): JsonObject {
        require(name.isNotBlank() && name.length <= 255)
        return writeField("ENCRYPTED_BYTES", JsonPrimitive(Base64.getEncoder().encodeToString(name.toByteArray(Charsets.UTF_8))))
    }

    suspend fun createAlbum(name: String): GalleryCollection = authorizedEdit { session ->
        val id = UUID.randomUUID().toString().replace("-", "").uppercase()
        val position = System.currentTimeMillis()
        modify(session, listOf(operation("create", buildJsonObject {
            put("recordName", id); put("recordType", "CPLAlbum")
            putJsonObject("fields") {
                put("albumNameEnc", albumName(name))
                for ((key, value) in mapOf("albumType" to 0L, "isDeleted" to 0L, "isExpunged" to 0L,
                    "position" to position, "sortType" to 1L, "sortAscending" to 1L)) {
                        put(key, writeField("INT64", JsonPrimitive(value)))
                    }
            }
        })))
        GalleryCollection(id, name, position = position)
    }

    suspend fun deleteAlbum(id: String) = authorizedEdit { session ->
        val existing = editablePersonalAlbum(id)
        val result = modify(session, listOf(operation("update", buildJsonObject {
            put("recordName", id); put("recordType", "CPLAlbum"); put("recordChangeTag", existing["recordChangeTag"]!!)
            putJsonObject("fields") { put("isDeleted", writeField("INT64", JsonPrimitive(1))) }
        }))).single()
        check(result.fieldFlag("isDeleted")) {
            "Album deletion was not confirmed. Refresh before retrying."
        }
    }

    suspend fun renameAlbum(id: String, name: String) = authorizedEdit { session ->
        val existing = editablePersonalAlbum(id)
        modify(session, listOf(operation("update", buildJsonObject {
            put("recordName", id); put("recordType", "CPLAlbum"); put("recordChangeTag", existing["recordChangeTag"]!!)
            putJsonObject("fields") { put("albumNameEnc", albumName(name)) }
        })))
        Unit
    }

    suspend fun addToAlbum(id: String, mediaIds: List<String>) = authorizedEdit { session ->
        require(!id.startsWith("smart:"))
        val names = mediaIds.distinct().map { mediaRecordName(it, session) }
        require(names.isNotEmpty() && names.size <= 50) { "Select up to 50 iCloud photos." }
        editablePersonalAlbum(id)
        val assets = database.lookup(names)
        check(assets.size == names.size && assets.all { it["serverErrorCode"] == null && !it.isRemovedFromLibrary() && it.recordType() == "CPLAsset" }) { "Some photos are no longer available. Refresh the library." }
        // Deterministic relation IDs make checking an earlier uncertain request possible, without replaying it.
        val relations = names.associateBy { "$it-IN-$id" }
        val existing = database.lookup(relations.keys)
        check(existing.all { it["serverErrorCode"] == null || it.text("serverErrorCode") == "NOT_FOUND" }) { "Could not check album membership." }
        val present = existing.filter { it["serverErrorCode"] == null && !it.isRemovedFromLibrary() }.mapNotNull { it.recordName() }.toSet()
        val missing = relations.filterKeys { it !in present }
        if (missing.isNotEmpty()) modify(session, missing.map { (relation, name) -> operation("create", buildJsonObject {
            put("recordName", relation); put("recordType", "CPLContainerRelation")
            putJsonObject("fields") {
                put("itemId", writeField("STRING", JsonPrimitive(name)))
                put("containerId", writeField("STRING", JsonPrimitive(id)))
                put("position", writeField("INT64", JsonPrimitive(1024)))
            }
        }) })
        database.requireUnchanged(session)
    }

    suspend fun removeFromAlbum(id: String, mediaIds: List<String>) = authorizedEdit { session ->
        require(!id.startsWith("smart:"))
        val album = editableRecord(id, "CPLAlbum")
        check(album.fieldInt("albumType") == 0)
        val names = mediaIds.distinct().map { mediaRecordName(it, session) }.toSet()
        require(names.size in 1..50)
        // Discover actual relation names; do not assume every client used our deterministic name.
        val relations = database.queryAll("CPLContainerRelationLiveByAssetDate", "parentId", id, true)
            .filter { it.fieldText("itemId") in names }
        check(relations.size <= 50)
        val fresh = database.lookup(relations.mapNotNull { it.recordName() })
        check(fresh.size == relations.size && fresh.mapNotNull { it.recordName() }.toSet() == relations.mapNotNull { it.recordName() }.toSet())
        val operations = fresh.map { record ->
            check(record["serverErrorCode"] == null && record.recordType() == "CPLContainerRelation")
            check(record.fieldText("containerId") == id && record.fieldText("itemId") in names)
            val tag = JsonPrimitive(checkNotNull(record.text("recordChangeTag")?.takeIf { it.isNotBlank() }))
            operation("delete", buildJsonObject { put("recordName", record.recordName()!!); put("recordType", "CPLContainerRelation"); put("recordChangeTag", tag) })
        }
        if (operations.isNotEmpty()) {
            val result = modify(session, operations)
            check(result.all { it["deleted"]?.jsonPrimitive?.booleanOrNull == true }) { "Album removal was not confirmed. Refresh before retrying." }
        }
        database.requireUnchanged(session)
    }

    suspend fun setCloudTrashed(mediaIds: List<String>, trashed: Boolean) = authorizedEdit { session ->
        val names = mediaIds.distinct().map { mediaRecordName(it, session) }
        require(names.size in 1..50)
        val records = database.lookup(names)
        check(records.mapNotNull { it.recordName() }.toSet() == names.toSet() && records.size == names.size)
        val operations = records.mapNotNull { record ->
            // A hidden photo can come back from Recently Deleted (into Hidden); only moving to the trash needs it visible.
            check(record.isTrashable(requireVisible = trashed)) { "This photo is no longer available. Refresh the library." }
            if (record.fieldFlag("isDeleted") == trashed) return@mapNotNull null
            operation("update", buildJsonObject {
                put("recordName", record.recordName()!!); put("recordType", "CPLAsset"); put("recordChangeTag", checkNotNull(record.text("recordChangeTag")?.takeIf { it.isNotBlank() }))
                putJsonObject("fields") {
                    // Apple clears the flags on recovery; it does not create another master/resource.
                    put("isDeleted", writeField("INT64", if (trashed) JsonPrimitive(1) else JsonNull))
                    if (!trashed) put("isExpunged", writeField("INT64", JsonNull))
                }
            })
        }
        if (operations.isNotEmpty()) {
            val result = modify(session, operations)
            check(result.all { it.fieldFlag("isDeleted") == trashed }) {
                "iCloud did not confirm the trash change. Refresh before retrying."
            }
            // A second read is required when a restore response omits nullable fields.
            val changedNames = operations.map { it.operationRecordName() }
            val confirmed = database.lookup(changedNames)
            check(confirmed.size == operations.size && confirmed.mapNotNull { it.recordName() }.toSet() == changedNames.toSet() &&
                confirmed.all { it.isTrashable(requireVisible = false) && it.fieldFlag("isDeleted") == trashed }) { "Refresh to confirm the iCloud result." }
        }
        database.requireUnchanged(session)
    }

    /** A live asset that may move in or out of Recently Deleted; a hidden asset is excluded unless [requireVisible] is off. */
    private fun JsonObject.isTrashable(requireVisible: Boolean = true): Boolean =
        this["serverErrorCode"] == null && this["fields"] is JsonObject && recordType() == "CPLAsset" &&
            this["deleted"]?.jsonPrimitive?.booleanOrNull != true && !fieldFlag("isExpunged") &&
            (!requireVisible || !fieldFlag("isHidden"))
}
