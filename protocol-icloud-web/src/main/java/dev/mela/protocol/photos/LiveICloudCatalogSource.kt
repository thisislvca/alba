package dev.mela.protocol.photos

import dev.mela.engine.source.CloudCatalogPage
import dev.mela.engine.source.CloudCatalogSource
import dev.mela.engine.source.RemoteMediaRecord
import dev.mela.protocol.account.AppleSessionSnapshot
import dev.mela.protocol.auth.AppleProtocolError
import dev.mela.protocol.auth.AppleProtocolException
import dev.mela.protocol.network.AppleHttpRequest
import dev.mela.protocol.network.AppleHttpResponse
import dev.mela.protocol.network.AppleHttpTransport
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import dev.mela.engine.source.*
import dev.mela.engine.model.*
import dev.mela.protocol.network.AppleMediaHttpException
import kotlinx.serialization.json.*

class LiveICloudCatalogSource(
    private val transport: AppleHttpTransport,
    private val sessionProvider: () -> AppleSessionSnapshot?,
    private val isAuthorized: () -> Boolean = { false },
    private val sharedJournal: UploadJournal? = null,
    private val onSessionUpdated: suspend (AppleSessionSnapshot) -> Unit = {},
    private val onSessionRejected: suspend (AppleSessionSnapshot) -> Unit = {},
) : CloudCatalogSource {
    private val shared = SharedAlbumsCatalog(
        transport,
        ::requireSession,
        { accountLabel },
        ::bind,
        ::persistTransportState,
        isAuthorized,
        sharedJournal,
        onSessionRejected,
    )
    override suspend fun saveLegacySharedPhoto(mediaId: String, fileName: String, source: dev.mela.engine.model.OneShotUploadSource) = shared.saveLegacySharedPhoto(mediaId, fileName, source)
    override suspend fun sharedActivity(id: String, rank: Int) = shared.sharedActivity(id, rank)
    override suspend fun sharedPostDiscussion(id: String) = shared.sharedPostDiscussion(id)
    override suspend fun sharedPostPhotos(id: String) = shared.sharedPostPhotos(id)
    override suspend fun pendingSharedInvitations() = shared.pendingSharedInvitations()
    override suspend fun resolveSharedInvitation(url: String) = shared.resolveSharedInvitation(url)
    override suspend fun respondSharedInvitation(id: String, accept: Boolean, operationId: String) = shared.respondSharedInvitation(id, accept, operationId)
    override suspend fun sharedManagement(id: String) = shared.sharedManagement(id)
    override suspend fun sharedDiscussion(mediaId: String) = shared.sharedDiscussion(mediaId)
    override suspend fun changeSharedAlbum(id: String, command: dev.mela.engine.model.SharedCommand, operationId: String) = shared.changeSharedAlbum(id, command, operationId)
    override suspend fun contributeToSharedAlbum(id: String, mediaIds: List<String>, operationId: String) = shared.contribute(id, mediaIds, operationId)
    override suspend fun uploadSharedPhoto(albumId: String, fileName: String, source: OneShotUploadSource, operationId: String) = shared.upload(albumId, fileName, source, operationId)
    override suspend fun sharedAlbums() = shared.snapshot()
    override suspend fun createSharedAlbum(name: String, operationId: String, generation: dev.mela.engine.model.SharedAlbumGeneration) = shared.create(name, operationId, generation)
    private val parser = CloudKitPhotoResponseParser()
    private val json = Json { ignoreUnknownKeys = true }
    private val downloads = ConcurrentHashMap<String, DownloadReferences>()
    private val currentRefreshIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var boundAuthentication: String? = null

    override val writeAccountId: String? get() = sessionProvider()?.let { session -> MessageDigest.getInstance("SHA-256")
        .digest("icloud-account\u0000${session.dsid}".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } }
    override val accountLabel: String
        get() = sessionProvider()?.let(::accountNamespace) ?: "signed-out"

    private suspend fun api(path: String, body: JsonObject): JsonObject {
        val session = requireSession()
        bind(session)
        val url = cloudKitUrl(session).replace("/records/query?", "/$path?")
        val raw = transport.execute(AppleHttpRequest("POST", url, JSON_HEADERS, body.toString(), oneShot = path == "records/modify"))
        persistTransportState(session)
        if (path == "changes/zone" && (raw.code == 410 || raw.body.contains("SYNC_TOKEN_EXPIRED") || raw.body.contains("INVALID_SYNC_TOKEN"))) throw CatalogResetRequired()
        if (raw.code !in 200..299) {
            if (raw.code == 401 || raw.code == 403) onSessionRejected(session)
            throw photosHttpError(raw)
        }
        val response = raw
        val root = Json.parseToJsonElement(response.body).jsonObject
        if (root.toString().contains("SYNC_TOKEN_EXPIRED") || root.toString().contains("INVALID_SYNC_TOKEN")) throw CatalogResetRequired()
        check(root["errors"]?.jsonArray.isNullOrEmpty()) { "Apple rejected the library request" }
        return root
    }
    private fun zone() = buildJsonObject { put("zoneName", PRIMARY_ZONE_NAME); put("zoneType", PRIMARY_ZONE_TYPE) }
    override suspend fun captureSyncToken(): String {
        val response = api("zones/list", buildJsonObject {})
        return response["zones"]!!.jsonArray.map { it.jsonObject }.first {
            it["zoneID"]!!.jsonObject["zoneName"]!!.jsonPrimitive.content == PRIMARY_ZONE_NAME
        }["syncToken"]!!.jsonPrimitive.content
    }
    private suspend fun lookup(names: Collection<String>): List<JsonObject> = names.distinct().chunked(50).flatMap { batch ->
        if (batch.isEmpty()) emptyList() else api("records/lookup", buildJsonObject {
            put("zoneID", zone()); putJsonArray("records") { batch.forEach { add(buildJsonObject { put("recordName", it) }) } }
        })["records"]!!.jsonArray.map { it.jsonObject }
    }
    private fun JsonObject.field(name: String): JsonElement? = ((get("fields") as? JsonObject)?.get(name) as? JsonObject)?.get("value")
    private fun JsonObject.name() = get("recordName")?.jsonPrimitive?.contentOrNull
    private fun JsonObject.deleted() = get("deleted")?.jsonPrimitive?.content in setOf("true", "1") ||
        field("isDeleted")?.jsonPrimitive?.content in setOf("true", "1") || field("isHidden")?.jsonPrimitive?.content in setOf("true", "1")
    private fun mapped(photo: CloudPhotoAsset): RemoteMediaRecord {
        val id = "icloud:${accountLabel}:${photo.assetRecordName}"
        downloads[id] = DownloadReferences(photo.previewDownloadUrl, photo.originalDownloadUrl, photo.playbackUrl, photo.motionDownloadUrl, photo.displayDownloadUrl)
        return RemoteMediaRecord(id, photo.fileName, photo.capturedAtEpochMillis, photo.width, photo.height,
            photo.sourceRevision, accent(id, 0), accent(id, 1), photo.kind, photo.mimeType, photo.durationMillis,
            photo.masterRecordName, photo.assetRecordName, photo.resourceFingerprint, photo.motionMimeType, photo.addedAtEpochMillis, photo.isTrashed, photo.byteCount)
    }
    private suspend fun hydrate(assetNames: Collection<String>, includeTrashed: Boolean = false): List<CloudPhotoAsset> {
        val assets = lookup(assetNames)
        if (assets.any { it["serverErrorCode"] != null }) throw CatalogResetRequired()
        val masters = lookup(assets.mapNotNull { (it.field("masterRef") as? JsonObject)?.get("recordName")?.jsonPrimitive?.content })
        if (masters.any { it["serverErrorCode"] != null }) throw CatalogResetRequired()
        val parsed = parser.parse(buildJsonObject { put("records", JsonArray(assets + masters)) }.toString(), includeTrashed).photos
        if (parsed.size < assets.count { !it.deleted() }) throw CatalogResetRequired()
        return parsed
    }
    override suspend fun changes(token: String, known: List<RemoteMediaRecord>): CloudChanges {
        val root = api("changes/zone", buildJsonObject {
            put("resultsLimit", 200)
            putJsonArray("zones") { add(buildJsonObject { put("zoneID", zone()); put("syncToken", token); put("reverse", false) }) }
        })
        val result = root["zones"]!!.jsonArray.single().jsonObject
        if (result["serverErrorCode"] != null) throw CatalogResetRequired()
        val deleted = mutableSetOf<String>()
        val changedAssets = mutableSetOf<String>()
        for (element in result["records"]!!.jsonArray) {
            val record = element.jsonObject
            val name = record.name() ?: throw CatalogResetRequired()
            val type = record["recordType"]?.jsonPrimitive?.content
            if (record.deleted()) { deleted += name; continue }
            when (type) {
                "CPLAsset" -> changedAssets += name
                "CPLMaster" -> {
                    val related = known.filter { it.masterRecordName == name }.mapNotNull { it.assetRecordName }
                    if (related.isEmpty()) throw CatalogResetRequired()
                    changedAssets += related
                }
                null -> throw CatalogResetRequired()
            }
        }
        val hydrated = hydrate(changedAssets - deleted)
        val present = hydrated.map { it.assetRecordName }.toSet()
        deleted += changedAssets - present
        return CloudChanges(hydrated.map(::mapped), deleted,
            result["syncToken"]!!.jsonPrimitive.content, result["moreComing"]?.jsonPrimitive?.booleanOrNull == true)
    }
    private suspend fun queryAll(type: String, field: String? = null, value: String? = null, ranked: Boolean = false): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val visited = mutableSetOf<String>()
        var marker: String? = null
        var offset = 0
        var logicalCount = 0
        var nextRank = false
        do {
            check(visited.add("$offset:${marker.orEmpty()}")) { "Repeated collection cursor" }
            val page = api("records/query", buildJsonObject {
                put("zoneID", zone()); put("resultsLimit", 200)
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
            logicalCount += records.count { it["recordType"]?.jsonPrimitive?.content in setOf("CPLAsset", "CPLContainerRelation") }
            check(result.size <= 200_000) { "Collection is too large" }
            marker = page["continuationMarker"]?.jsonPrimitive?.contentOrNull
            val logicalLimit = if (type.contains("AssetAndMaster")) 100 else 200
            nextRank = ranked && marker == null && logicalCount >= logicalLimit
            if (nextRank) { offset += logicalCount; logicalCount = 0 }
        } while (marker != null || nextRank)
        return result
    }
    override suspend fun collections(): CollectionSnapshot {
        val albums = mutableListOf<GalleryCollection>()
        val pending = java.util.LinkedList<String?>().apply { add(null) }
        val visited = mutableSetOf<String?>()
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            check(visited.add(parent)) { "Repeated album folder" }
            for (record in queryAll("CPLAlbumByPositionLive", if (parent == null) null else "parentId", parent)) {
                if (record.deleted()) continue
                val id = record.name() ?: continue
                if (albums.any { it.id == id }) continue
                val encoded = record.field("albumNameEnc")?.jsonPrimitive?.contentOrNull ?: continue
                val name = runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrDefault(encoded)
                val folder = record.field("albumType")?.jsonPrimitive?.intOrNull == 3
                albums += GalleryCollection(id, name, record.field("parentId")?.jsonPrimitive?.contentOrNull ?: parent,
                    folder, record.field("position")?.jsonPrimitive?.longOrNull ?: albums.size.toLong())
                if (folder) pending.add(id)
            }
            check(albums.size < 10000)
        }
        val members = mutableMapOf<String, Set<String>>()
        fun id(name: String) = "icloud:$accountLabel:$name"
        for (album in albums.filterNot { it.isFolder }) {
            members[album.id] = queryAll("CPLContainerRelationLiveByAssetDate", "parentId", album.id, true)
                .mapNotNull { it.field("itemId")?.jsonPrimitive?.contentOrNull?.let(::id) }.toSet()
        }
        members[GalleryQuery.FAVORITES] = queryAll("CPLAssetAndMasterInSmartAlbumByAssetDate", "smartAlbum", "FAVORITE", true)
            .filter { it["recordType"]?.jsonPrimitive?.content == "CPLAsset" }.mapNotNull { it.name()?.let(::id) }.toSet()
        for (smart in SmartCollection.entries) {
            albums += GalleryCollection(smart.id, smart.title, position = smart.ordinal.toLong())
            val records = if (smart == SmartCollection.BURSTS) queryAll("CPLBurstStackAssetAndMasterByAssetDate", ranked = true)
                else queryAll("CPLAssetAndMasterInSmartAlbumByAssetDate", "smartAlbum", smart.appleFilter, true)
            members[smart.id] = records.filter { it["recordType"]?.jsonPrimitive?.content == "CPLAsset" && !it.deleted() }
                .mapNotNull { it.name()?.let(::id) }.toSet()
        }
        return CollectionSnapshot(albums, members)
    }
    private val editLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun <T> authorizedEdit(action: suspend (AppleSessionSnapshot) -> T): T {
        editLock.lock()
        try {
            val session = requireSession()
            check(isAuthorized()) { "Reconnect to iCloud before editing the library." }
            return action(session)
        } finally { editLock.unlock() }
    }
    private fun checkIdentity(session: AppleSessionSnapshot) {
        val current = requireSession()
        check(current.dsid == session.dsid && current.clientId == session.clientId && isAuthorized()) {
            "The account connection changed. Refresh to check the result."
        }
    }
    private fun mediaRecordName(id: String, session: AppleSessionSnapshot): String {
        val prefix = "icloud:${accountNamespace(session)}:"
        require(id.startsWith(prefix)) { "Photo belongs to another account." }
        return id.removePrefix(prefix).also { require(it.isNotBlank() && ':' !in it) }
    }
    private suspend fun editableRecord(name: String, type: String): JsonObject = lookup(listOf(name)).single().also {
        check(it["serverErrorCode"] == null && it["recordType"]?.jsonPrimitive?.content == type && !it.deleted()) {
            "This item is no longer editable. Refresh the library."
        }
        check(it["recordChangeTag"]?.jsonPrimitive?.contentOrNull != null) { "Apple omitted the edit version. Refresh the library." }
    }
    private fun writeField(type: String, value: JsonElement) = buildJsonObject { put("type", type); put("value", value) }
    private fun operation(type: String, record: JsonObject) = buildJsonObject { put("operationType", type); put("record", record) }
    private suspend fun modify(session: AppleSessionSnapshot, operations: List<JsonObject>): List<JsonObject> {
        checkIdentity(session)
        val root = api("records/modify", buildJsonObject {
            put("zoneID", zone()); put("atomic", true); put("operations", JsonArray(operations))
        })
        checkIdentity(session)
        val results = root["records"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val expected = operations.map { it["record"]!!.jsonObject["recordName"]!!.jsonPrimitive.content }.toSet()
        check(results.size == expected.size && results.all { it.name() in expected && it["serverErrorCode"] == null } &&
            results.mapNotNull { it.name() }.toSet() == expected) {
            "Apple did not confirm the edit. Refresh the library before trying again."
        }
        return results
    }
    override suspend fun setFavorite(mediaId: String, favorite: Boolean) = authorizedEdit { session ->
        val name = mediaRecordName(mediaId, session)
        val existing = editableRecord(name, "CPLAsset")
        val result = modify(session, listOf(operation("update", buildJsonObject {
            put("recordName", name); put("recordType", "CPLAsset"); put("recordChangeTag", existing["recordChangeTag"]!!)
            putJsonObject("fields") { put("isFavorite", writeField("INT64", JsonPrimitive(if (favorite) 1 else 0))) }
        }))).single()
        check(result.field("isFavorite")?.jsonPrimitive?.content == if (favorite) "1" else "0") {
            "Favorite change was not confirmed. Refresh the library."
        }
    }
    private fun albumName(name: String): JsonObject {
        require(name.isNotBlank() && name.length <= 255)
        return writeField("ENCRYPTED_BYTES", JsonPrimitive(Base64.getEncoder().encodeToString(name.toByteArray(Charsets.UTF_8))))
    }
    override suspend fun createAlbum(name: String): GalleryCollection = authorizedEdit { session ->
        val id = java.util.UUID.randomUUID().toString().replace("-", "").uppercase()
        val position = System.currentTimeMillis()
        modify(session, listOf(operation("create", buildJsonObject {
            put("recordName", id); put("recordType", "CPLAlbum")
            putJsonObject("fields") {
                put("albumNameEnc", albumName(name))
                for ((key, value) in mapOf("albumType" to 0L, "isDeleted" to 0L, "isExpunged" to 0L,
                    "position" to position, "sortType" to 1L, "sortAscending" to 1L)) put(key, writeField("INT64", JsonPrimitive(value)))
            }
        })))
        GalleryCollection(id, name, position = position)
    }
    override suspend fun deleteAlbum(id: String) = authorizedEdit { session ->
        require(!id.startsWith("smart:"))
        val existing = editableRecord(id, "CPLAlbum")
        check(existing.field("albumType")?.jsonPrimitive?.intOrNull == 0) { "Choose a personal album." }
        val result = modify(session, listOf(operation("update", buildJsonObject {
            put("recordName", id); put("recordType", "CPLAlbum"); put("recordChangeTag", existing["recordChangeTag"]!!)
            putJsonObject("fields") { put("isDeleted", writeField("INT64", JsonPrimitive(1))) }
        }))).single()
        check(result.field("isDeleted")?.jsonPrimitive?.content in setOf("1", "true")) {
            "Album deletion was not confirmed. Refresh before retrying."
        }
        Unit
    }
    override suspend fun renameAlbum(id: String, name: String) = authorizedEdit { session ->
        require(!id.startsWith("smart:"))
        val existing = editableRecord(id, "CPLAlbum")
        check(existing.field("albumType")?.jsonPrimitive?.intOrNull == 0) { "Choose a personal album." }
        modify(session, listOf(operation("update", buildJsonObject {
            put("recordName", id); put("recordType", "CPLAlbum"); put("recordChangeTag", existing["recordChangeTag"]!!)
            putJsonObject("fields") { put("albumNameEnc", albumName(name)) }
        })))
        Unit
    }
    override suspend fun addToAlbum(id: String, mediaIds: List<String>) = authorizedEdit { session ->
        require(!id.startsWith("smart:"))
        val names = mediaIds.distinct().map { mediaRecordName(it, session) }
        require(names.isNotEmpty() && names.size <= 50) { "Select up to 50 iCloud photos." }
        val album = editableRecord(id, "CPLAlbum")
        check(album.field("albumType")?.jsonPrimitive?.intOrNull == 0) { "Choose a personal album." }
        val assets = lookup(names)
        check(assets.size == names.size && assets.all { it["serverErrorCode"] == null && !it.deleted() && it["recordType"]?.jsonPrimitive?.content == "CPLAsset" }) { "Some photos are no longer available. Refresh the library." }
        // Deterministic relation IDs make checking an earlier uncertain request possible, without replaying it.
        val relations = names.associateBy { "$it-IN-$id" }
        val existing = lookup(relations.keys)
        check(existing.all { it["serverErrorCode"] == null || it["serverErrorCode"]?.jsonPrimitive?.content == "NOT_FOUND" }) { "Could not check album membership." }
        val present = existing.filter { it["serverErrorCode"] == null && !it.deleted() }.mapNotNull { it.name() }.toSet()
        val missing = relations.filterKeys { it !in present }
        if (missing.isNotEmpty()) modify(session, missing.map { (relation, name) -> operation("create", buildJsonObject {
            put("recordName", relation); put("recordType", "CPLContainerRelation")
            putJsonObject("fields") {
                put("itemId", writeField("STRING", JsonPrimitive(name)))
                put("containerId", writeField("STRING", JsonPrimitive(id)))
                put("position", writeField("INT64", JsonPrimitive(1024)))
            }
        }) })
        checkIdentity(session)
    }

    override suspend fun accountProfile(): CloudAccountProfile {
        val session = requireSession()
        bind(session)
        val profile = ICloudAccountProfileService(transport).load(session)
        check(sessionProvider()?.let { it.dsid == session.dsid && it.clientId == session.clientId } == true) {
            "The iCloud account changed while checking the profile."
        }
        persistTransportState(session)
        return profile
    }

    override suspend fun storageUsage(): CloudStorageUsage {
        val session = requireSession()
        bind(session)
        val response = execute(session, AppleHttpRequest("POST", setupUrl("storageUsageInfo", session), JSON_HEADERS))
        check(sessionProvider()?.dsid == session.dsid) { "Account changed while checking storage." }
        return ICloudStorageParser.parse(response.body)
    }

    override suspend fun recentlyDeleted(): List<RemoteMediaRecord> {
        val session = requireSession()
        val records = queryAll("CPLAssetAndMasterDeletedByExpungedDate", ranked = true)
        checkIdentity(session)
        check(records.all { it["serverErrorCode"] == null })
        val parsed = parser.parse(buildJsonObject { put("records", JsonArray(records)) }.toString(), includeTrashed = true).photos
        val expected = records.count { it["recordType"]?.jsonPrimitive?.content == "CPLAsset" &&
            it.field("isHidden")?.jsonPrimitive?.content !in setOf("1", "true") && it.field("isExpunged")?.jsonPrimitive?.content !in setOf("1", "true") }
        check(parsed.size == expected) { "Recently Deleted was incomplete. Refresh to try again." }
        return parsed.filter { it.isTrashed }.map(::mapped)
    }

    override suspend fun removeFromAlbum(id: String, mediaIds: List<String>) = authorizedEdit { session ->
        require(!id.startsWith("smart:"))
        val album = editableRecord(id, "CPLAlbum")
        check(album.field("albumType")?.jsonPrimitive?.intOrNull == 0)
        val names = mediaIds.distinct().map { mediaRecordName(it, session) }.toSet()
        require(names.size in 1..50)
        // Discover actual relation names; do not assume every client used our deterministic name.
        val relations = queryAll("CPLContainerRelationLiveByAssetDate", "parentId", id, true)
            .filter { it.field("itemId")?.jsonPrimitive?.content in names }
        check(relations.size <= 50)
        val fresh = lookup(relations.mapNotNull { it.name() })
        check(fresh.size == relations.size && fresh.mapNotNull { it.name() }.toSet() == relations.mapNotNull { it.name() }.toSet())
        val operations = fresh.map { record ->
            check(record["serverErrorCode"] == null && record["recordType"]?.jsonPrimitive?.content == "CPLContainerRelation")
            check(record.field("containerId")?.jsonPrimitive?.content == id && record.field("itemId")?.jsonPrimitive?.content in names)
            val tag = JsonPrimitive(checkNotNull(record["recordChangeTag"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }))
            operation("delete", buildJsonObject { put("recordName", record.name()!!); put("recordType", "CPLContainerRelation"); put("recordChangeTag", tag) })
        }
        if (operations.isNotEmpty()) {
            val result = modify(session, operations)
            check(result.all { it["deleted"]?.jsonPrimitive?.booleanOrNull == true }) { "Album removal was not confirmed. Refresh before retrying." }
        }
        checkIdentity(session)
    }

    override suspend fun setCloudTrashed(mediaIds: List<String>, trashed: Boolean) = authorizedEdit { session ->
        val names = mediaIds.distinct().map { mediaRecordName(it, session) }
        require(names.size in 1..50)
        val records = lookup(names)
        check(records.mapNotNull { it.name() }.toSet() == names.toSet() && records.size == names.size)
        val operations = records.mapNotNull { record ->
            check(record["serverErrorCode"] == null && record["fields"] is JsonObject && record["recordType"]?.jsonPrimitive?.content == "CPLAsset" &&
                record["deleted"]?.jsonPrimitive?.booleanOrNull != true &&
                record.field("isExpunged")?.jsonPrimitive?.content !in setOf("1", "true") &&
                record.field("isHidden")?.jsonPrimitive?.content !in setOf("1", "true")) { "This photo is no longer available. Refresh the library." }
            val isTrashed = record.field("isDeleted")?.jsonPrimitive?.content in setOf("1", "true")
            if (isTrashed == trashed) return@mapNotNull null
            operation("update", buildJsonObject {
                put("recordName", record.name()!!); put("recordType", "CPLAsset"); put("recordChangeTag", checkNotNull(record["recordChangeTag"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }))
                putJsonObject("fields") {
                    // Apple clears the flags on recovery; it does not create another master/resource.
                    put("isDeleted", writeField("INT64", if (trashed) JsonPrimitive(1) else JsonNull))
                    if (!trashed) put("isExpunged", writeField("INT64", JsonNull))
                }
            })
        }
        if (operations.isNotEmpty()) {
            val result = modify(session, operations)
            check(result.all { (it.field("isDeleted")?.jsonPrimitive?.content in setOf("1", "true")) == trashed }) {
                "iCloud did not confirm the trash change. Refresh before retrying."
            }
            // A second read is required when a restore response omits nullable fields.
            val confirmed = lookup(operations.map { it["record"]!!.jsonObject["recordName"]!!.jsonPrimitive.content })
            val changedNames = operations.map { it["record"]!!.jsonObject["recordName"]!!.jsonPrimitive.content }.toSet()
            check(confirmed.size == operations.size && confirmed.mapNotNull { it.name() }.toSet() == changedNames && confirmed.all { it["serverErrorCode"] == null && it["fields"] is JsonObject && it["recordType"]?.jsonPrimitive?.content == "CPLAsset" &&
                it["deleted"]?.jsonPrimitive?.booleanOrNull != true && it.field("isExpunged")?.jsonPrimitive?.content !in setOf("1", "true") &&
                (it.field("isDeleted")?.jsonPrimitive?.content in setOf("1", "true")) == trashed }) { "Refresh to confirm the iCloud result." }
        }
        checkIdentity(session)
    }

    private suspend fun freshReferences(mediaId: String): DownloadReferences {
        require(mediaId.startsWith("icloud:$accountLabel:")) { "Media belongs to another account" }
        val photo = try { hydrate(listOf(mediaId.substringAfterLast(':')), includeTrashed = true).singleOrNull() }
        catch (_: CatalogResetRequired) { null }
        if (photo == null) throw AppleProtocolException(AppleProtocolError.PHOTOS_UNAVAILABLE, "Media is no longer available")
        mapped(photo)
        return downloads.getValue(mediaId)
    }
    override suspend fun openPlayback(mediaId: String, position: Long, length: Long): MediaRead {
        if (SharedMediaIdentity.isShared(mediaId)) return shared.playback(mediaId, position, length)
        require(mediaId.startsWith("icloud:$accountLabel:"))
        var ref = freshReferences(mediaId)
        return try { transport.openRange(ref.playbackUrl, position, length) }
        catch (expired: AppleMediaHttpException) {
            if (expired.status !in setOf(401, 403, 404, 410)) throw expired
            ref = freshReferences(mediaId)
            transport.openRange(ref.playbackUrl, position, length)
        }
    }

    override suspend fun fetchPage(cursor: String?, limit: Int): CloudCatalogPage {
        require(limit in 1..100) { "iCloud page size must be between 1 and 100" }
        val session = requireSession()
        bind(session)
        val pageCursor = PageCursor.decode(cursor)
        if (cursor == null) {
            preparePhotos(session)
            currentRefreshIds.clear()
        }

        val response = execute(
            session = session,
            request = AppleHttpRequest(
                method = "POST",
                url = cloudKitUrl(session),
                headers = JSON_HEADERS,
                body = photoQueryBody(
                    offset = pageCursor.offset,
                    limit = limit,
                    continuationMarker = pageCursor.continuationMarker,
                ),
            ),
        )
        val parsed = parser.parse(response.body)
        val namespace = accountNamespace(session)
        val records = parsed.photos.map { photo ->
            val mediaId = "icloud:$namespace:${photo.assetRecordName}"
            downloads[mediaId] = DownloadReferences(
                previewUrl = photo.previewDownloadUrl,
                displayUrl = photo.displayDownloadUrl,
                originalUrl = photo.originalDownloadUrl,
                playbackUrl = photo.playbackUrl,
                motionUrl = photo.motionDownloadUrl,
            )
            currentRefreshIds += mediaId
            RemoteMediaRecord(
                id = mediaId,
                fileName = photo.fileName,
                capturedAtEpochMillis = photo.capturedAtEpochMillis,
                addedAtEpochMillis = photo.addedAtEpochMillis,
                width = photo.width,
                height = photo.height,
                sourceRevision = photo.sourceRevision,
                accentStartArgb = accent(mediaId, 0),
                accentEndArgb = accent(mediaId, 1),
                kind = photo.kind, mimeType = photo.mimeType, durationMillis = photo.durationMillis,
                masterRecordName = photo.masterRecordName, assetRecordName = photo.assetRecordName,
                resourceFingerprint = photo.resourceFingerprint, motionMimeType = photo.motionMimeType, byteCount = photo.byteCount,
            )
        }
        val accumulatedCount = pageCursor.accumulatedCount + parsed.logicalAssetCount
        val nextOffset = pageCursor.offset + accumulatedCount
        val nextCursor = when {
            parsed.continuationMarker != null -> PageCursor(
                offset = pageCursor.offset,
                continuationMarker = parsed.continuationMarker,
                accumulatedCount = accumulatedCount,
            ).encode()

            accumulatedCount >= limit -> PageCursor(nextOffset).encode()
            else -> null
        }
        if (nextCursor == null) {
            downloads.keys.retainAll(currentRefreshIds)
            currentRefreshIds.clear()
        }

        return CloudCatalogPage(
            records = records,
            nextCursor = nextCursor,
            changeToken = parsed.syncToken
                ?: "rank:$nextOffset:${parsed.photos.lastOrNull()?.sourceRevision.orEmpty()}",
        )
    }

    override suspend fun writeDisplay(mediaId: String, output: OutputStream) {
        if (SharedMediaIdentity.isShared(mediaId)) return shared.write(mediaId, output, "display")
        stream(mediaId, output, DownloadReferences::displayUrl)
    }

    override suspend fun writePreview(mediaId: String, output: OutputStream) {
        if (SharedMediaIdentity.isShared(mediaId)) return shared.write(mediaId, output, "preview")
        stream(mediaId, output, DownloadReferences::previewUrl)
    }

    override suspend fun writeMotion(mediaId: String, output: OutputStream) {
        if (SharedMediaIdentity.isShared(mediaId)) return shared.write(mediaId, output, "motion")
        stream(mediaId, output) { requireNotNull(it.motionUrl) { "Live Photo motion is unavailable" } }
    }

    override suspend fun writeOriginal(mediaId: String, output: OutputStream) {
        if (SharedMediaIdentity.isShared(mediaId)) return shared.write(mediaId, output, "original")
        stream(mediaId, output, DownloadReferences::originalUrl)
    }

    private suspend fun stream(
        mediaId: String,
        output: OutputStream,
        selectUrl: (DownloadReferences) -> String,
    ) {
        val session = requireSession()
        bind(session)
        require(mediaId.startsWith("icloud:$accountLabel:"))
        val references = downloads[mediaId] ?: freshReferences(mediaId)
        check(selectUrl(references).isNotBlank()) { "No poster image available" }
        try { transport.stream(selectUrl(references), output) }
        catch (expired: AppleMediaHttpException) {
            if (expired.status !in setOf(401, 403, 404, 410)) throw expired
            transport.stream(selectUrl(freshReferences(mediaId)), output)
        }
        persistTransportState(session)
    }

    private suspend fun preparePhotos(session: AppleSessionSnapshot) {
        val webAccess = execute(
            session,
            AppleHttpRequest(
                method = "POST",
                url = setupUrl("requestWebAccessState", session),
            ),
        ).jsonObjectOrNull()
        val protectedWebAccessDisabled = webAccess
            ?.get("isICDRSDisabled")
            ?.jsonPrimitive
            ?.contentOrNull == "true"
        if (protectedWebAccessDisabled) {
            throw AppleProtocolException(
                AppleProtocolError.PHOTOS_UNAVAILABLE,
                "Access to iCloud data on the web is disabled for this account.",
            )
        }

        val indexing = execute(
            session = session,
            request = AppleHttpRequest(
                method = "POST",
                url = cloudKitUrl(session),
                headers = JSON_HEADERS,
                body = buildJsonObject {
                    putJsonObject("query") { put("recordType", "CheckIndexingState") }
                    putJsonObject("zoneID") {
                        put("zoneName", PRIMARY_ZONE_NAME)
                        put("zoneType", PRIMARY_ZONE_TYPE)
                    }
                    put("resultsLimit", 1)
                }.toString(),
            ),
        )
        if (indexing.code !in 200..299) throw photosHttpError(indexing)
        val state = indexing.jsonObjectOrNull()
            ?.get("records")
            ?.let { it as? kotlinx.serialization.json.JsonArray }
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.let { record ->
                (((record["fields"] as? JsonObject)?.get("state") as? JsonObject)?.get("value")
                    as? JsonPrimitive)?.contentOrNull
            }
        if (state != null && state != "FINISHED") {
            throw AppleProtocolException(
                AppleProtocolError.PHOTOS_UNAVAILABLE,
                "iCloud Photos is still preparing this library. Try again in a few minutes.",
            )
        }
    }

    private suspend fun execute(
        session: AppleSessionSnapshot,
        request: AppleHttpRequest,
    ): AppleHttpResponse {
        val response = transport.execute(request)
        persistTransportState(session)
        if (response.code !in 200..299) {
            if (response.code == 401 || response.code == 403) onSessionRejected(session)
            throw photosHttpError(response)
        }
        return response
    }

    private fun photosHttpError(response: AppleHttpResponse): AppleProtocolException =
        AppleProtocolException(
            error = if (response.code == 401 || response.code == 403) {
                AppleProtocolError.SESSION_EXPIRED
            } else {
                AppleProtocolError.NETWORK
            },
            message = when (response.code) {
                401, 403 -> "The iCloud session expired. Sign in again."
                429 -> "Apple is limiting Photos requests. Wait a moment and refresh."
                else -> "Apple Photos returned HTTP ${response.code}."
            },
        )

    private fun bind(session: AppleSessionSnapshot) {
        val authentication = "${session.dsid}:${session.clientId}"
        if (boundAuthentication == authentication) return
        transport.restore(session)
        downloads.clear()
        currentRefreshIds.clear()
        boundAuthentication = authentication
    }

    private suspend fun persistTransportState(session: AppleSessionSnapshot) {
        val headers = transport.sessionHeaders
        onSessionUpdated(
            session.copy(
                accountCountryCode = headers.accountCountryCode ?: session.accountCountryCode,
                sessionId = headers.sessionId ?: session.sessionId,
                sessionToken = headers.sessionToken ?: session.sessionToken,
                trustToken = headers.trustToken ?: session.trustToken,
                cookies = transport.snapshotCookies(),
            ),
        )
    }

    private fun requireSession(): AppleSessionSnapshot = sessionProvider()
        ?: throw AppleProtocolException(
            AppleProtocolError.SESSION_EXPIRED,
            "Sign in to iCloud to load the live library.",
        )

    private fun cloudKitUrl(session: AppleSessionSnapshot): String {
        val root = session.webservices["ckdatabasews"]
            ?: throw AppleProtocolException(
                AppleProtocolError.PHOTOS_UNAVAILABLE,
                "iCloud Photos is unavailable for this account.",
            )
        return "$root/database/1/com.apple.photos.cloud/production/private/records/query"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("clientBuildNumber", CLIENT_BUILD)
            .addQueryParameter("clientMasteringNumber", CLIENT_MASTERING)
            .addQueryParameter("clientId", session.clientId)
            .addQueryParameter("dsid", session.dsid)
            .addQueryParameter("remapEnums", "true")
            .addQueryParameter("getCurrentSyncToken", "true")
            .build()
            .toString()
    }

    private fun setupUrl(path: String, session: AppleSessionSnapshot): String =
        "$SETUP_ENDPOINT/$path".toHttpUrl().newBuilder()
            .addQueryParameter("clientBuildNumber", CLIENT_BUILD)
            .addQueryParameter("clientMasteringNumber", CLIENT_MASTERING)
            .addQueryParameter("clientId", session.clientId)
            .addQueryParameter("dsid", session.dsid)
            .build()
            .toString()

    private fun photoQueryBody(
        offset: Int,
        limit: Int,
        continuationMarker: String?,
    ): String = buildJsonObject {
        putJsonObject("query") {
            put("recordType", PHOTO_LIST_RECORD_TYPE)
            putJsonArray("filterBy") {
                add(buildJsonObject {
                    put("fieldName", "direction")
                    put("comparator", "EQUALS")
                    putJsonObject("fieldValue") {
                        put("type", "STRING")
                        put("value", "ASCENDING")
                    }
                })
                add(buildJsonObject {
                    put("fieldName", "startRank")
                    put("comparator", "EQUALS")
                    putJsonObject("fieldValue") {
                        put("type", "INT64")
                        put("value", offset)
                    }
                })
            }
        }
        putJsonObject("zoneID") {
            put("zoneName", PRIMARY_ZONE_NAME)
            put("zoneType", PRIMARY_ZONE_TYPE)
        }
        putJsonArray("desiredKeys") {
            PHOTO_DESIRED_KEYS.forEach { add(JsonPrimitive(it)) }
        }
        put("resultsLimit", limit * RECORDS_PER_PHOTO)
        continuationMarker?.let { put("continuationMarker", it) }
    }.toString()

    private fun AppleHttpResponse.jsonObjectOrNull(): JsonObject? = runCatching {
        json.parseToJsonElement(body) as? JsonObject
    }.getOrNull()

    private fun accountNamespace(session: AppleSessionSnapshot): String = MessageDigest
        .getInstance("SHA-256")
        .digest(session.dsid.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun accent(mediaId: String, offset: Int): Long {
        val index = ((mediaId.hashCode().toUInt().toLong() + offset) % ACCENTS.size).toInt()
        return ACCENTS[index]
    }

    private data class DownloadReferences(
        val previewUrl: String,
        val originalUrl: String,
        val playbackUrl: String,
        val motionUrl: String?,
        val displayUrl: String,
    )

    private data class PageCursor(
        val offset: Int,
        val continuationMarker: String? = null,
        val accumulatedCount: Int = 0,
    ) {
        fun encode(): String {
            val marker = continuationMarker
                ?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray()) }
                .orEmpty()
            return "$offset:$accumulatedCount:$marker"
        }

        companion object {
            fun decode(value: String?): PageCursor {
                if (value == null) return PageCursor(0)
                val parts = value.split(':', limit = 3)
                val offset = parts.firstOrNull()?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid iCloud cursor")
                require(offset >= 0) { "Invalid iCloud cursor" }
                val accumulatedCount = parts.getOrNull(1)?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid iCloud cursor")
                require(accumulatedCount >= 0) { "Invalid iCloud cursor" }
                val marker = parts.getOrNull(2)
                    ?.takeIf(String::isNotBlank)
                    ?.let { encoded ->
                        runCatching {
                            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
                        }.getOrElse { throw IllegalArgumentException("Invalid iCloud cursor") }
                    }
                return PageCursor(offset, marker, accumulatedCount)
            }
        }
    }

    private companion object {
        const val SETUP_ENDPOINT = "https://setup.icloud.com/setup/ws/1"
        const val CLIENT_BUILD = "2534Project66"
        const val CLIENT_MASTERING = "2534B22"
        const val PRIMARY_ZONE_NAME = "PrimarySync"
        const val PRIMARY_ZONE_TYPE = "REGULAR_CUSTOM_ZONE"
        const val PHOTO_LIST_RECORD_TYPE = "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted"
        const val RECORDS_PER_PHOTO = 2
        val JSON_HEADERS = mapOf("Content-Type" to "plain/text")
        val PHOTO_DESIRED_KEYS = listOf(
            "resOriginalVidComplRes", "resOriginalVidComplFileType", "resOriginalVidComplFingerprint",
            "isHidden", "isDeleted", "isFavorite", "duration", "resOriginalFingerprint", "resVidMedRes", "resVidFullRes",
            "resJPEGMedFingerprint", "resJPEGLargeFingerprint", "resJPEGFullFingerprint", "resJPEGThumbFingerprint",
            "resVidMedFingerprint", "resVidFullFingerprint",
            "filenameEnc",
            "itemType",
            "resOriginalFileType",
            "resOriginalWidth",
            "resOriginalHeight",
            "resOriginalRes",
            "resJPEGMedWidth",
            "resJPEGMedHeight",
            "resJPEGMedRes",
            "resJPEGLargeWidth",
            "resJPEGLargeHeight",
            "resJPEGLargeRes",
            "resJPEGFullWidth",
            "resJPEGFullHeight",
            "resJPEGFullRes",
            "resJPEGThumbWidth",
            "resJPEGThumbHeight",
            "resJPEGThumbRes",
            "masterRef",
            "assetDate", "addedDate",
        )
        val ACCENTS = longArrayOf(
            0xFFB86855L,
            0xFF496F82L,
            0xFF879B72L,
            0xFF7C668EL,
            0xFFD09A57L,
            0xFF506A65L,
        )
    }
}
