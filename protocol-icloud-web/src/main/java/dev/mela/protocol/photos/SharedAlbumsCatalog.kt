package dev.mela.protocol.photos

import dev.mela.engine.model.*
import dev.mela.engine.source.*
import dev.mela.protocol.account.AppleSessionSnapshot
import dev.mela.protocol.auth.AppleProtocolError
import dev.mela.protocol.auth.AppleProtocolException
import dev.mela.protocol.network.*
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The personal library, legacy streams and each CloudKit owner's zones are separate namespaces. */
internal class SharedAlbumsCatalog(
    private val transport: AppleHttpTransport,
    private val session: () -> AppleSessionSnapshot,
    private val account: () -> String,
    private val prepare: (AppleSessionSnapshot) -> Unit,
    private val persist: suspend (AppleSessionSnapshot) -> Unit,
    private val authorized: () -> Boolean,
    private val journal: UploadJournal?,
    private val reject: suspend (AppleSessionSnapshot) -> Unit = {},
) : SharedAlbumOperations {
    private data class Album(val collection: GalleryCollection, val scope: String, val owner: String,
        val zone: String, val location: String? = null, val ctag: String? = null) {
        fun zoneId() = buildJsonObject { put("zoneName", zone); put("ownerRecordName", owner); put("zoneType", "REGULAR_CUSTOM_ZONE") }
    }
    private val parser = CloudKitPhotoResponseParser()
    private val albums = ConcurrentHashMap<String, Album>()
    private val photos = ConcurrentHashMap<String, CloudPhotoAsset>()
    private val legacyContributors = ConcurrentHashMap<String, String>()
    private val lock = Mutex()
    private var binding: String? = null
    private var operationSession: AppleSessionSnapshot? = null
    private suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock {
        operationSession = bound()
        try { block() } finally { operationSession = null }
    }

    private fun bound(): AppleSessionSnapshot {
        val current = session()
        prepare(current)
        val key = "${current.dsid}:${current.clientId}"
        if (binding != key) { albums.clear(); photos.clear(); legacyContributors.clear(); binding = key }
        return current
    }
    private fun checkSession(expected: AppleSessionSnapshot) {
        check(session().let { it.dsid == expected.dsid && it.clientId == expected.clientId }) { "Account changed. Refresh shared albums." }
    }
    private fun webAlbumUrl(route: String, identifier: String): String {
        require(identifier.matches(Regex("[A-Za-z0-9_-]+")))
        val domain = if (session().webservices["ckdatabasews"].orEmpty().contains(".icloud.com.cn")) "www.icloud.com.cn" else "www.icloud.com"
        return "https://$domain/photos/#/$route$identifier/"
    }
    private fun sharedAlbumLink(token: String): String {
        require(token.matches(Regex("[A-Za-z0-9_-]{1,4096}")))
        val domain = if (bound().webservices["ckdatabasews"].orEmpty().contains(".icloud.com.cn")) "photos.icloud.com.cn" else "photos.icloud.com"
        return "https://$domain/shared/album/$token"
    }
    private fun encoded(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun albumId(scope: String, owner: String, zone: String) =
        "shared:${account()}:${encoded(scope)}:${encoded(owner)}:${encoded(zone)}"
    private fun photoId(album: Album, name: String) = "${album.collection.id}:${encoded(name)}"
    private fun collectionId(mediaId: String): String {
        require(mediaId.startsWith("shared:${account()}:")) { "Shared photo belongs to another account." }
        require(mediaId.split(':').size == 6) { "Invalid shared photo identity." }
        return mediaId.substringBeforeLast(':')
    }

    private suspend fun request(url: String, body: JsonObject, write: Boolean = false): JsonObject =
        requestElement(url, body, write) as? JsonObject ?: error("Apple returned an invalid shared album response.")
    private suspend fun requestElement(url: String, body: JsonObject, write: Boolean = false): JsonElement {
        operationSession?.let(::checkSession)
        val current = bound()
        if (write) check(authorized()) { "Sign in before changing shared albums." }
        AppleEndpointPolicy.requireAllowed(url)
        val target = url.toHttpUrl().newBuilder().addQueryParameter("clientId", current.clientId)
            .addQueryParameter("dsid", current.dsid).addQueryParameter("remapEnums", "true").build().toString()
        val response = transport.execute(AppleHttpRequest("POST", target, mapOf("Content-Type" to "text/plain;charset=UTF-8"), body.toString(), oneShot = write))
        checkSession(current)
        persist(current)
        if (response.code !in 200..299) {
            if (response.code == 401) reject(current)
            throw AppleProtocolException(
                if (response.code == 401) AppleProtocolError.SESSION_EXPIRED else AppleProtocolError.PHOTOS_UNAVAILABLE,
                "Shared albums returned HTTP ${response.code}. Refresh to check access.",
            )
        }
        if (write && response.body.isBlank()) return buildJsonObject {}
        val element = Json.parseToJsonElement(response.body)
        val root = element as? JsonObject ?: return element
        check(root["errors"]?.jsonArray.isNullOrEmpty() && root["serverErrorCode"] == null) { "Apple could not load shared albums." }
        if (write) {
            for (key in listOf("records", "zones")) check((root[key] as? JsonArray).orEmpty().none { (it as? JsonObject)?.get("serverErrorCode") != null }) {
                "Apple could not confirm the shared album change. Refresh before retrying."
            }
        }
        return root
    }
    private suspend fun ck(scope: String, path: String, body: JsonObject, write: Boolean = false): JsonObject {
        require(scope in setOf("private", "shared", "public"))
        val root = requireNotNull(bound().webservices["ckdatabasews"]) { "Shared albums are unavailable for this account." }
        return request("${root.trimEnd('/')}/database/1/com.apple.photos.cloud/production/$scope/$path", body, write)
    }
    private suspend fun streamRequest(album: Album, path: String, body: JsonObject) =
        request("${requireNotNull(album.location).trimEnd('/')}/$path", body)

    suspend fun snapshot(): SharedCatalogSnapshot = exclusive {
        val current = bound()
        val found = discover()
        val records = mutableListOf<RemoteMediaRecord>()
        val members = mutableMapOf<String, Set<String>>()
        val loaded = mutableMapOf<String, CloudPhotoAsset>()
        for (album in found) {
            val items = readAlbum(album)
            val mapped = items.map { photo ->
                val id = photoId(album, photo.assetRecordName)
                loaded[id] = photo
                RemoteMediaRecord(id, photo.fileName, photo.capturedAtEpochMillis, photo.width, photo.height,
                    photo.sourceRevision, 0xff294e63, 0xff497187, photo.kind, photo.mimeType, photo.durationMillis,
                    photo.masterRecordName, photo.assetRecordName,
                    photo.resourceFingerprint.ifBlank { "${album.ctag}:${photo.masterRecordName}" },
                    photo.motionMimeType, photo.addedAtEpochMillis, byteCount = photo.byteCount)
            }
            records += mapped
            check(records.size <= 100000) { "Shared library is too large for a single snapshot." }
            members[album.collection.id] = mapped.map { it.id }.toSet()
        }
        checkSession(current)
        albums.clear(); albums.putAll(found.associateBy { it.collection.id })
        photos.clear(); photos.putAll(loaded)
        SharedCatalogSnapshot(records, CollectionSnapshot(found.map { it.collection }, members))
    }

    private suspend fun discover(): List<Album> {
        val current = bound()
        val result = mutableListOf<Album>()
        current.webservices["sharedstreams"]?.let { root ->
            val response = request("${root.trimEnd('/')}/${current.dsid}/sharedstreams/webgetalbumslist", buildJsonObject {})
            val rows = requireNotNull(response["albums"] as? JsonArray) { "Incomplete shared album list." }
            for (value in rows) {
                val row = value.jsonObject
                val attributes = row["attributes"]?.jsonObject ?: JsonObject(emptyMap())
                val guid = requireNotNull(row.text("albumguid"))
                val owner = requireNotNull(row.text("ownerdsid"))
                val location = requireNotNull(row.text("albumlocation"))
                AppleEndpointPolicy.requireAllowed(location)
                val owned = row.text("sharingtype") in setOf("owned", "owner")
                val role = when {
                    owned -> SharedAlbumRole.OWNER
                    attributes.text("allowcontributions") == "1" && row.text("iswebuploadsupported") == "1" -> SharedAlbumRole.CONTRIBUTOR
                    else -> SharedAlbumRole.VIEWER
                }
                val id = albumId("legacy", owner, guid)
                result += Album(GalleryCollection(id, attributes.text("name") ?: guid,
                    shared = SharedAlbumInfo(SharedAlbumGeneration.LEGACY, role, webUrl = webAlbumUrl("sa,", guid))), "legacy", owner, guid, location, row.text("albumctag"))
            }
        }
        if (current.webservices["ckdatabasews"] != null) for (scope in listOf("private", "shared")) {
            val zones = linkedMapOf<String, JsonObject>()
            val tokens = mutableSetOf<String>()
            var token: String? = null
            do {
                val root = ck(scope, "changes/database", buildJsonObject { token?.let { put("syncToken", it) } })
                for (entry in requireNotNull(root["zones"] as? JsonArray) { "Incomplete shared zone list." }) {
                    val zone = entry.jsonObject
                    val id = zone["zoneID"]?.jsonObject ?: continue
                    val name = id.text("zoneName") ?: continue
                    if (!name.startsWith("SharedCollection-")) continue
                    val key = "${id.text("ownerRecordName")}:$name"
                    if (zone.text("deleted") in setOf("true", "1")) zones.remove(key) else zones[key] = id
                }
                val more = root.text("moreComing") == "true"
                token = if (more) requireNotNull(root.text("syncToken")) else null
                if (token != null) check(tokens.add(token) && tokens.size < 1000) { "Repeated shared zone cursor." }
            } while (token != null)
            for (zone in zones.values) {
                val share = lookupShare(scope, zone) ?: continue
                val name = zone.text("zoneName")!!
                val owner = requireNotNull(zone.text("ownerRecordName")) { "Shared zone has no owner." }
                val currentUser = share["currentUserParticipant"] as? JsonObject
                val participants = (share["participants"] as? JsonArray).orEmpty()
                val user = currentUser ?: participants.map { it.jsonObject }.firstOrNull { it.text("isCurrentUser") == "true" }
                // Private zones belong to this account; subscribed zones fail closed if role data is absent.
                val role = when {
                    user?.text("type") == "OWNER" || (user == null && scope == "private") -> SharedAlbumRole.OWNER
                    user?.text("acceptanceStatus") == "ACCEPTED" && user.text("type") == "ADMINISTRATOR" -> SharedAlbumRole.MANAGER
                    user?.text("acceptanceStatus") == "ACCEPTED" && user.text("permission") == "READ_WRITE" && user.text("customRole") == "commenter" -> SharedAlbumRole.COMMENTER
                    user?.text("acceptanceStatus") == "ACCEPTED" && user.text("permission") == "READ_WRITE" -> SharedAlbumRole.CONTRIBUTOR
                    else -> SharedAlbumRole.VIEWER
                }
                val id = albumId(scope, owner, name)
                result += Album(GalleryCollection(id, share.field("cloudkit.title")?.jsonPrimitive?.contentOrNull ?: name,
                    shared = SharedAlbumInfo(SharedAlbumGeneration.MODERN, role, participantCount = participants.size, webUrl = share.text("shortGUID")?.let { webAlbumUrl("sharedalbums/sc,", it) })), scope, owner, name)
            }
        }
        check(result.size <= 10000) { "Too many shared albums." }
        return result.distinctBy { it.collection.id }
    }

    private suspend fun lookupShare(scope: String, zone: JsonObject, creating: Boolean = false): JsonObject? {
        val response = ck(scope, "records/lookup", buildJsonObject {
            put("zoneID", zone); putJsonArray("records") { add(buildJsonObject { put("recordName", "cloudkit.zoneshare") }) }
        })
        val record = requireNotNull(response["records"]?.jsonArray?.singleOrNull()?.jsonObject) { "Missing shared album metadata." }
        if (record.text("serverErrorCode") == "NOT_FOUND") return null
        if (!creating && record.text("serverErrorCode") in setOf("ACCESS_DENIED", "ZONE_NOT_FOUND")) return null
        check(record["serverErrorCode"] == null) { "Shared album metadata is unavailable." }
        check(record.text("recordType") == "cloudkit.share") { "Unexpected shared album metadata." }
        return record
    }

    private suspend fun readAlbum(album: Album): List<CloudPhotoAsset> {
        val output = linkedMapOf<String, CloudPhotoAsset>()
        if (album.scope == "legacy") {
            val count = streamRequest(album, "webgetassetcount", buildJsonObject { put("albumguid", album.zone) })
                .text("albumassetcount")?.toIntOrNull() ?: error("Missing shared album count.")
            require(count in 0..100000) { "Shared album is too large." }
            var offset = 0
            while (offset < count) {
                val end = minOf(offset + 100, count)
                val root = streamRequest(album, "webgetassets", buildJsonObject {
                    put("albumguid", album.zone); put("offset", offset.toString()); put("limit", end.toString())
                    album.ctag?.let { put("albumctag", it) }
                })
                (root["records"] as? JsonArray).orEmpty().map { it.jsonObject }.forEach { record ->
                    if (record.text("recordType") == "CPLAsset") {
                        val identity = photoId(album, requireNotNull(record.text("recordName")))
                        val contributor = record.field("contributedBy")?.jsonPrimitive?.contentOrNull
                        if (contributor == null) legacyContributors.remove(identity) else legacyContributors[identity] = contributor
                    }
                }
                val page = parser.parse(root.toString(), legacy = true)
                check(page.logicalAssetCount == end - offset && page.photos.size == page.logicalAssetCount) { "Shared album changed during loading. Refresh again." }
                page.photos.forEach { check(output.put(it.assetRecordName, it) == null) { "Repeated shared album page." } }
                offset = end
            }
        } else {
            var rank = 0
            var accumulated = 0
            var marker: String? = null
            val cursors = mutableSetOf<String>()
            do {
                check(cursors.add("$rank:$marker") && cursors.size <= 2000) { "Repeated shared album cursor." }
                val root = ck(album.scope, "records/query", buildJsonObject {
                    put("zoneID", album.zoneId()); put("resultsLimit", 200)
                    putJsonObject("query") {
                        put("recordType", "CPLAssetAndMasterByAddedDate")
                        putJsonArray("filterBy") {
                            add(filter("direction", JsonPrimitive("ASCENDING"), "STRING"))
                            add(filter("startRank", JsonPrimitive(rank), "INT64"))
                        }
                    }
                    marker?.let { put("continuationMarker", it) }
                })
                val page = parser.parse(root.toString())
                page.photos.forEach { check(output.put(it.assetRecordName, it) == null) { "Repeated shared photo." } }
                accumulated += page.logicalAssetCount
                marker = page.continuationMarker
                if (marker == null) {
                    if (accumulated < 100) break
                    rank += accumulated; accumulated = 0
                }
                check(output.size <= 100000) { "Shared album is too large." }
            } while (true)
        }
        return output.values.toList()
    }

    private suspend fun reference(mediaId: String, refresh: Boolean = false): CloudPhotoAsset = exclusive {
        bound()
        val id = collectionId(mediaId)
        if (!refresh) photos[mediaId]?.let { return@exclusive it }
        val album = discover().firstOrNull { it.collection.id == id }
            ?: error("Access to this shared album is no longer available. Refresh the library.")
        val items = readAlbum(album)
        albums[id] = album
        photos.keys.removeAll { it.startsWith("$id:") }
        items.forEach { photos[photoId(album, it.assetRecordName)] = it }
        requireNotNull(photos[mediaId]) { "This shared photo is no longer available." }
    }

    suspend fun write(mediaId: String, output: OutputStream, kind: String) {
        fun url(photo: CloudPhotoAsset): String = when (kind) {
            "preview" -> photo.previewDownloadUrl
            "display" -> photo.displayDownloadUrl
            "motion" -> requireNotNull(photo.motionDownloadUrl)
            else -> photo.originalDownloadUrl
        }.also { check(it.isNotBlank()) { "No preview is available for this shared item." } }
        val current = bound()
        val ref = reference(mediaId)
        try { transport.stream(url(ref), output) }
        catch (e: AppleMediaHttpException) {
            if (e.status !in setOf(401, 403, 404, 410)) throw e
            transport.stream(url(reference(mediaId, refresh = true)), output)
        }
        checkSession(current)
    }
    suspend fun playback(mediaId: String, position: Long, length: Long): MediaRead {
        val current = bound()
        var ref = reference(mediaId)
        val result = try { transport.openRange(ref.playbackUrl, position, length) }
        catch (e: AppleMediaHttpException) {
            if (e.status !in setOf(401, 403, 404, 410)) throw e
            ref = reference(mediaId, refresh = true)
            transport.openRange(ref.playbackUrl, position, length)
        }
        try { checkSession(current) } catch (e: Exception) { result.close(); throw e }
        return result
    }

    private fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun journalBinding() = AccountBinding("shared:${account()}", 0, 0)
    private suspend fun save(key: String, phase: String, data: JsonObject) = requireNotNull(journal)
        .save(journalBinding(), key, UploadCheckpoint(phase, data.toString()))

    /** A pending creation is recovered by account/name even after force-stop loses UI saved state. */
    suspend fun create(name: String, operationId: String, generation: SharedAlbumGeneration): GalleryCollection = exclusive {
        require(name.isNotBlank() && name.length <= 255)
        java.util.UUID.fromString(operationId)
        check(authorized()) { "Sign in before creating a shared album." }
        val key = "shared-create:" + digest("${account()}|$generation|$name")
        val old = requireNotNull(journal) { "Durable shared album storage is unavailable." }.load(journalBinding(), key)
        var data = old?.payload?.let { Json.parseToJsonElement(it).jsonObject }
        if (data == null || (old?.phase == "DONE" && data.text("operationId") != operationId)) {
            data = buildJsonObject { put("operationId", operationId); put("zoneName", if (generation == SharedAlbumGeneration.MODERN) "SharedCollection-${java.util.UUID.randomUUID()}" else java.util.UUID.randomUUID().toString().uppercase()) }
            save(key, "READY", data)
        }
        val pending = data
        val zoneName = requireNotNull(pending.text("zoneName"))
        if (generation == SharedAlbumGeneration.LEGACY) {
            var found = discover().firstOrNull { it.scope == "legacy" && it.zone == zoneName }
            if (found == null) {
                save(key, "CREATING_LEGACY", pending)
                val current = bound()
                val root = requireNotNull(current.webservices["sharedstreams"])
                request("${root.trimEnd('/')}/${current.dsid}/sharedstreams/createalbum", buildJsonObject {
                    put("albumguid", zoneName)
                    putJsonObject("attributes") { put("name", name); put("ispublic", "0"); put("allowcontributions", "1") }
                }, write = true)
                found = discover().firstOrNull { it.scope == "legacy" && it.zone == zoneName }
            }
            val created = requireNotNull(found) { "Refresh to check the new shared album." }
            save(key, "DONE", pending); albums[created.collection.id] = created
            return@exclusive created.collection
        }
        val listed = ck("private", "zones/list", buildJsonObject {})["zones"]?.jsonArray
            ?: error("Could not check pending shared albums.")
        var zone = listed.mapNotNull { it.jsonObject["zoneID"] as? JsonObject }.firstOrNull { it.text("zoneName") == zoneName }
        if (zone == null) {
            save(key, "ZONE_SENDING", pending)
            val result = ck("private", "zones/modify", buildJsonObject {
                putJsonArray("operations") { add(buildJsonObject {
                    put("operationType", "create"); putJsonObject("zone") { putJsonObject("zoneID") { put("zoneName", zoneName) } }
                }) }
            }, write = true)
            zone = requireNotNull(result["zones"]?.jsonArray?.singleOrNull()?.jsonObject?.get("zoneID") as? JsonObject) { "Refresh to check whether the shared album was created." }
        }
        check(zone.text("zoneName") == zoneName)
        save(key, "ZONE_CREATED", pending)
        if (lookupShare("private", zone, creating = true) == null) {
            save(key, "SHARE_SENDING", pending)
            ck("private", "records/modify", buildJsonObject {
                put("atomic", true); put("zoneID", zone)
                putJsonArray("operations") { add(buildJsonObject {
                    put("operationType", "create")
                    putJsonObject("record") {
                        put("recordName", "cloudkit.zoneshare"); put("recordType", "cloudkit.share")
                        putJsonObject("fields") {
                            putJsonObject("cloudkit.title") { put("value", name) }
                            putJsonObject("cloudkit.type") { put("value", "photos_sharedcollections") }
                            putJsonObject("sharedCollectionState") { put("value", 0) }
                        }
                        put("publicPermission", "NONE"); put("publicCustomAccess", "")
                        put("participantSelfRemovalBehavior", "removeFromShare"); put("denyAccessRequests", true)
                    }
                }) }
            }, write = true)
        }
        val created = discover().firstOrNull { it.scope == "private" && it.zone == zoneName }
            ?: error("Refresh to check whether the shared album was created.")
        save(key, "DONE", pending)
        albums[created.collection.id] = created
        created.collection
    }

    /** Server-side copy, never a relation to a PrimarySync original or cleanup proof. */
    suspend fun contribute(id: String, mediaIds: List<String>, operationId: String) = exclusive {
        java.util.UUID.fromString(operationId)
        require(mediaIds.size in 1..50)
        val prefix = "icloud:${account()}:"
        val names = mediaIds.distinct().map { require(it.startsWith(prefix)); it.removePrefix(prefix).also { n -> require(n.isNotBlank() && ':' !in n) } }
        val album = discover().firstOrNull { it.collection.id == id } ?: error("Shared album is no longer available.")
        require(album.collection.shared?.canContribute == true) { "You do not have permission to add photos." }
        require(album.scope != "legacy") { "Use iCloud Photos to contribute to this legacy album." }
        check(authorized()) { "Sign in before adding shared photos." }
        val key = "shared-copy:" + digest("${account()}|$id|${names.sorted().joinToString(",")}")
        val old = requireNotNull(journal).load(journalBinding(), key)
        var data = old?.payload?.let { Json.parseToJsonElement(it).jsonObject }
        var phase = old?.phase ?: "READY"
        if (data == null || (phase == "DONE" && data.text("operationId") != operationId)) {
            data = buildJsonObject { put("operationId", operationId); put("batchId", java.util.UUID.randomUUID().toString()); put("target", id); put("timestamp", System.currentTimeMillis()) }
            phase = "READY"; save(key, phase, data)
        }
        check(data.text("target") == id)
        if (phase == "DONE") return@exclusive
        var pending = data
        val batchId = requireNotNull(pending.text("batchId"))
        if (phase == "STARTING") error("The contribution result is uncertain. Check the album in iCloud before adding these photos again.")
        if (phase == "READY") {
            val zones = ck("private", "zones/list", buildJsonObject {})["zones"]!!.jsonArray
            val source = zones.map { it.jsonObject["zoneID"]!!.jsonObject }.first { it.text("zoneName") == "PrimarySync" }
            save(key, "STARTING", pending)
            val response = ck("private", "records/copy/start", buildJsonObject {
                put("sourceZoneID", source); put("targetZoneID", album.zoneId()); put("targetShareName", "cloudkit.zoneshare")
                put("batchId", batchId); put("includeRecords", JsonArray(names.map(::JsonPrimitive)))
            }, write = true)
            pending = JsonObject(pending + ("jobID" to JsonPrimitive(requireNotNull(response.text("jobID")))))
            save(key, "POLLING", pending); phase = "POLLING"
        }
        if (phase == "POLLING") {
            var completed = false
            repeat(20) {
                if (!completed) {
                    val response = ck("private", "records/copy/status", buildJsonObject { put("jobID", requireNotNull(pending.text("jobID"))) })
                    when (response.text("status")) {
                        "COMPLETED" -> completed = true
                        "SUBMITTED", "RUNNING", "PROCESSING" -> kotlinx.coroutines.delay(1000)
                        else -> error("Apple has not confirmed this shared contribution. Check the album before retrying.")
                    }
                }
            }
            check(completed) { "Shared photos are still processing. Retry to check the same job." }
            save(key, "POSTING", pending)
        }
        // A stable post record name makes the last step recoverable after response loss.
        val existing = ck(album.scope, "records/lookup", buildJsonObject {
            put("zoneID", album.zoneId()); putJsonArray("records") { add(buildJsonObject { put("recordName", batchId) }) }
        })["records"]!!.jsonArray.single().jsonObject
        if (existing.text("serverErrorCode") == "NOT_FOUND") {
            ck(album.scope, "records/modify", buildJsonObject {
                put("atomic", true); put("zoneID", album.zoneId())
                putJsonArray("operations") { add(buildJsonObject {
                    put("operationType", "create"); putJsonObject("record") {
                        put("recordType", "CPLPost"); put("recordName", batchId)
                        putJsonObject("fields") { putJsonObject("postTimestamp") { put("value", pending.getValue("timestamp")) } }
                    }
                }) }
            }, write = true)
        } else check(existing.text("recordType") == "CPLPost" && existing["serverErrorCode"] == null) { "Could not confirm the shared post." }
        save(key, "DONE", pending)
    }

    suspend fun saveLegacySharedPhoto(mediaId: String, fileName: String, source: OneShotUploadSource) = exclusive {
        val a = fresh(collectionId(mediaId)); require(a.scope == "legacy")
        require(readAlbum(a).any { it.assetRecordName == mediaName(a, mediaId) })
        val current = bound(); val root = requireNotNull(current.webservices["photosupload"])
        val key = "shared-save:" + digest("${account()}|$mediaId|${source.sha256Hex}")
        val accepted = PhotosUpload(transport, current, root.trimEnd('/'), fileName, source,
            { checkSession(current); persist(current) }, requireNotNull(journal), journalBinding(),
            checkAuthorized = { checkSession(current); check(authorized()) },
            onSessionRejected = reject).startOnce(key)
        accepted.uploadJobId?.let { pollUpload(root, it) }
        Unit
    }
    private suspend fun pollUpload(root: String, job: String) {
        repeat(20) {
            val response = request("${root.trimEnd('/')}/photosupload/uploadStatus", buildJsonObject { putJsonArray("uploadJobIds") { add(job) } })
            val status = response[job]?.jsonObject ?: error("Upload status unavailable. Retry to check again.")
            check(status["errorCode"] == null) { "Apple could not process this upload." }
            if (status.text("progress") == "100") return
            kotlinx.coroutines.delay(1000)
        }
        error("Upload is still processing. Retry to check the same upload.")
    }

    suspend fun upload(id: String, fileName: String, source: OneShotUploadSource, operationId: String) {
        if (id.split(':').getOrNull(2) == encoded("legacy")) return uploadLegacy(id, fileName, source, operationId)
        exclusive {
            val a = fresh(id); require(a.collection.shared?.canContribute == true)
            val current = bound(); val root = requireNotNull(current.webservices["photosupload"])
            val key = "shared-direct:" + digest("${account()}|$id|$fileName|${source.sha256Hex}")
            val batch = "CPLPost-" + java.util.UUID.nameUUIDFromBytes(key.toByteArray()).toString()
            val accepted = PhotosUpload(transport, current, root.trimEnd('/'), fileName, source,
                { checkSession(current); persist(current) }, requireNotNull(journal), journalBinding(),
                checkAuthorized = { checkSession(current); check(authorized()) }, zoneName = a.zone, assetBatchId = batch,
                onSessionRejected = reject).startOnce(key)
            accepted.uploadJobId?.let { job ->
                var done = false
                repeat(20) {
                    if (!done) {
                        val response = request("${root.trimEnd('/')}/photosupload/uploadStatus", buildJsonObject { putJsonArray("uploadJobIds") { add(job) } })
                        val status = response[job]?.jsonObject ?: error("Upload status unavailable. Retry to check again.")
                        check(status["errorCode"] == null) { "Apple could not process this shared upload." }
                        done = status.text("progress") == "100"
                        if (!done) kotlinx.coroutines.delay(1000)
                    }
                }
                check(done) { "Upload is still processing. Retry to check the same upload." }
            }
            require(fresh(id).collection.shared?.canContribute == true)
            val existing = lookup(a, batch)
            if (existing.text("serverErrorCode") == "NOT_FOUND") {
                modify(a, "create", buildJsonObject {
                    put("recordType", "CPLPost"); put("recordName", batch)
                    putJsonObject("fields") { putJsonObject("postTimestamp") { put("value", System.currentTimeMillis()) } }
                })
            } else check(existing.text("recordType") == "CPLPost")
        }
    }

    suspend fun uploadLegacy(id: String, fileName: String, source: OneShotUploadSource, operationId: String) = exclusive {
        require(source.byteCount > 0 && fileName.isNotBlank())
        check(authorized()) { "Sign in before contributing photos." }
        val current = bound()
        val album = discover().firstOrNull { it.collection.id == id } ?: error("Shared album is no longer available.")
        require(album.scope == "legacy" && album.collection.shared?.canContribute == true) { "You cannot add photos to this album." }
        // A force-stop may lose the UI operation ID. Bind retries to the actual content instead.
        java.util.UUID.fromString(operationId)
        val key = "shared-upload:" + digest("${account()}|$id|$fileName|${source.sha256Hex}")
        val old = requireNotNull(journal).load(journalBinding(), key)
        var phase = old?.phase ?: "READY"
        fun newUpload() = buildJsonObject {
            put("target", id); put("size", source.byteCount); put("sha256", source.sha256Hex); put("fileName", fileName)
            put("assetGuid", java.util.UUID.randomUUID().toString()); put("batchGuid", java.util.UUID.randomUUID().toString())
            put("date", java.time.Instant.now().toString()); put("timezone", java.time.ZoneId.systemDefault().id)
        }
        var saved = old?.payload?.let { Json.parseToJsonElement(it).jsonObject } ?: newUpload()
        check(saved.text("target") == id && saved.text("fileName") == fileName && saved.text("sha256") == source.sha256Hex && saved.text("size")?.toLong() == source.byteCount) { "Shared upload source or destination changed." }
        if (phase == "DONE") {
            if (readAlbum(album).any { it.assetRecordName == saved.text("assetGuid") }) return@exclusive
            // A confirmed completed copy was subsequently removed. An explicit new add is safe.
            saved = newUpload(); phase = "READY"
        }
        suspend fun savePhase(next: String, additions: Map<String, JsonElement> = emptyMap()) {
            saved = JsonObject(saved + additions); save(key, next, saved); phase = next
        }
        if (phase == "REGISTERING") {
            // The client-assigned asset GUID survives response loss. Do not register the same bytes twice.
            if (readAlbum(album).any { it.assetRecordName == saved.text("assetGuid") }) { savePhase("DONE"); return@exclusive }
            error("The shared upload result is uncertain. Check the album before adding it again.")
        }
        check(phase != "BYTES_SENDING") { "The shared upload was interrupted. Its bytes will not be replayed automatically." }
        if (phase in setOf("READY", "RESERVING")) {
            savePhase("RESERVING")
            val reserved = request("${album.location!!.trimEnd('/')}/webgetuploadurl", buildJsonObject {
                put("albumguid", album.zone); put("filesize", source.byteCount)
            }, write = true)
            val target = requireNotNull(reserved.text("uploadurl")).toHttpUrl().newBuilder().addQueryParameter("chunker", "FIXED").build().toString()
            val allowed = AppleEndpointPolicy.requireAllowed(target)
            require(allowed.host.endsWith(".icloud-content.com") || allowed.host.endsWith(".icloud-content.com.cn"))
            savePhase("RESERVED", mapOf("uploadUrl" to JsonPrimitive(target)))
        }
        if (phase == "RESERVED") {
            checkSession(current); check(authorized())
            savePhase("BYTES_SENDING")
            val raw = transport.executeRawUpload(requireNotNull(saved.text("uploadUrl")), emptyMap(), source.byteCount, source::openOnce)
            checkSession(current); persist(current)
            check(raw.code in 200..299) { "Shared upload bytes were not accepted." }
            val receipt = requireNotNull(Json.parseToJsonElement(raw.body).jsonObject["singleFile"] as? JsonObject)
            check(receipt.text("size")?.toLong() == source.byteCount && listOf("referenceChecksum", "fileChecksum", "wrappingKey", "receipt").all { !receipt.text(it).isNullOrBlank() })
            savePhase("RECEIPT_SAVED", mapOf("receipt" to receipt))
        }
        if (phase == "RECEIPT_SAVED") {
            // Re-read permission before the publishing step; UI capabilities can be stale.
            check(discover().firstOrNull { it.collection.id == id }?.collection?.shared?.canContribute == true)
            savePhase("REGISTERING")
            val registered = request("${album.location!!.trimEnd('/')}/webputasset", buildJsonObject {
                put("albumguid", album.zone); put("assetguid", saved.getValue("assetGuid")); put("filename", fileName)
                putJsonObject("collectionmetadata") {
                    put("batchDateCreated", saved.getValue("date")); put("dateCreated", saved.getValue("date")); put("batchGUID", saved.getValue("batchGuid"))
                }
                put("singlefileupload", saved.getValue("receipt")); put("localtimezoneid", saved.getValue("timezone"))
            }, write = true)
            check(registered.text("albumguid") == album.zone && registered.text("assetguid") == saved.text("assetGuid"))
            savePhase("POLLING", mapOf("jobId" to JsonPrimitive(requireNotNull(registered.text("uploadjobid")))))
        }
        if (phase == "POLLING") {
            var complete = false
            repeat(20) {
                if (!complete) {
                    val result = requestElement("${album.location!!.trimEnd('/')}/webuploadstatus", buildJsonObject {
                        putJsonArray("uploadjobids") { add(requireNotNull(saved.text("jobId"))) }
                    }).jsonArray.single().jsonObject
                    check(result.text("uploadjobid") == saved.text("jobId"))
                    when (result.text("status")) {
                        "COMPLETED" -> complete = true
                        "IN_PROGRESS" -> kotlinx.coroutines.delay(1000)
                        else -> error("Apple could not finish the shared upload. Check the album before retrying.")
                    }
                }
            }
            check(complete) { "Shared upload is still processing. Retry to check the same job." }
            savePhase("DONE")
        }
    }

    private suspend fun fresh(id: String): Album {
        require(id.startsWith("shared:${account()}:")) { "Shared album belongs to another account." }
        return discover().firstOrNull { it.collection.id == id } ?: error("Shared album is no longer available.")
    }
    private fun legacyUrl(path: String): String {
        val s = bound()
        return "${requireNotNull(s.webservices["sharedstreams"]).trimEnd('/')}/${s.dsid}/sharedstreams/$path"
    }
    private suspend fun legacy(album: Album, path: String, extra: JsonObject = buildJsonObject {}, write: Boolean = false, root: Boolean = false): JsonElement =
        requestElement(if (root) legacyUrl(path) else "${requireNotNull(album.location).trimEnd('/')}/$path",
            JsonObject(mapOf("albumguid" to JsonPrimitive(album.zone)) + extra), write)
    private fun participantRole(p: JsonObject): SharedAlbumRole = when {
        p.text("type") == "OWNER" -> SharedAlbumRole.OWNER
        p.text("type") == "ADMINISTRATOR" -> SharedAlbumRole.MANAGER
        p.text("permission") == "READ_WRITE" && p.text("customRole") == "commenter" -> SharedAlbumRole.COMMENTER
        p.text("permission") == "READ_WRITE" -> SharedAlbumRole.CONTRIBUTOR
        else -> SharedAlbumRole.VIEWER
    }
    private fun identityKey(identity: JsonObject): String = identity.text("userRecordName")
        ?: (identity["lookupInfo"] as? JsonObject)?.text("emailAddress") ?: error("Missing participant identity")
    private fun participantName(p: JsonObject): String {
        val u = p["userIdentity"] as? JsonObject
        val n = u?.get("nameComponents") as? JsonObject
        return listOfNotNull(n?.text("givenName"), n?.text("familyName")).joinToString(" ").ifBlank {
            (u?.get("lookupInfo") as? JsonObject)?.text("emailAddress") ?: p.text("fullname") ?: p.text("email") ?: "—"
        }
    }
    private fun invitationId(kind: String, value: String) = "invitation:${account()}:$kind:${encoded(value)}"
    override suspend fun pendingSharedInvitations(): List<SharedInvitation> = exclusive { pendingInvitations() }
    private suspend fun pendingInvitations(): List<SharedInvitation> {
        if (bound().webservices["sharedstreams"] == null) return emptyList()
        val root = request(legacyUrl("webgetpendingalbums"), buildJsonObject {})
        return requireNotNull(root["albums"] as? JsonArray).map { it.jsonObject }.map { row ->
            check(row["httpstatus"] == null) { "Could not load an invitation." }
            SharedInvitation(invitationId("legacy", requireNotNull(row.text("albumguid"))),
                (row["attributes"] as? JsonObject)?.text("name") ?: "—", SharedAlbumGeneration.LEGACY, true)
        }
    }
    private fun invitationValue(id: String): Pair<String, String> {
        require(id.startsWith("invitation:${account()}:")) { "Invitation belongs to another account." }
        val parts = id.split(':'); require(parts.size == 4 && parts[2] in setOf("legacy", "modern"))
        return parts[2] to String(Base64.getUrlDecoder().decode(parts[3]), Charsets.UTF_8)
    }
    private suspend fun resolveShare(guid: String): JsonObject {
        require(guid.matches(Regex("[A-Za-z0-9_-]{1,4096}")))
        val response = ck("public", "records/resolve", buildJsonObject { putJsonArray("shortGUIDs") { add(buildJsonObject { put("value", guid) }) } })
        val item = requireNotNull(response["results"] as? JsonArray).single().jsonObject
        check(item["serverErrorCode"] == null) { "The invitation is unavailable." }
        val share = requireNotNull(item["share"] as? JsonObject) { "Open this invitation in iCloud to finish signing in." }
        val zone = (item["zoneID"] ?: share["zoneID"])?.jsonObject
        require(zone?.text("zoneName")?.startsWith("SharedCollection-") == true && share.text("recordType") == "cloudkit.share") { "This link is not a new shared album." }
        return share
    }
    override suspend fun resolveSharedInvitation(url: String): SharedInvitation = exclusive {
        val guid = requireNotNull(SharedAlbumLinks.token(url)) { "Paste an iCloud Photos shared-album link." }
        val share = resolveShare(guid)
        SharedInvitation(invitationId("modern", guid), share.field("cloudkit.title")?.jsonPrimitive?.contentOrNull ?: "—", SharedAlbumGeneration.MODERN)
    }
    override suspend fun respondSharedInvitation(id: String, accept: Boolean, operationId: String) = exclusive {
        java.util.UUID.fromString(operationId); check(authorized())
        val (kind, value) = invitationValue(id)
        require(kind == "legacy" || accept) { "New-album invitations are declined in iCloud." }
        val key = "shared-invitation:" + digest("$id|$accept")
        val old = requireNotNull(journal).load(journalBinding(), key)
        suspend fun confirmed(): Boolean = if (kind == "modern") {
            (resolveShare(value)["currentUserParticipant"] as? JsonObject)?.text("acceptanceStatus") == "ACCEPTED"
        } else {
            val joined = discover().any { it.scope == "legacy" && it.zone == value }
            if (accept) joined else !joined && pendingInvitations().none { it.id == id }
        }
        if (kind == "modern" && old == null && confirmed()) {
            save(key, "DONE", buildJsonObject {}); return@exclusive
        }
        if (old != null) {
            if (confirmed()) { save(key, "DONE", buildJsonObject {}); return@exclusive }
            check(old.phase != "SENDING") { "Invitation response is uncertain. Check iCloud before retrying." }
        }
        if (kind == "legacy") require(pendingInvitations().any { it.id == id }) else resolveShare(value)
        save(key, "SENDING", buildJsonObject { put("operationId", operationId) })
        if (kind == "legacy") request(legacyUrl(if(accept) "subscribe" else "unsubscribe"), buildJsonObject { put("albumguid", value) }, true)
        else {
            val response = ck("public", "records/accept", buildJsonObject { putJsonArray("shortGUIDs") { add(buildJsonObject { put("value", value) }) } }, true)
            val result = requireNotNull(response["results"] as? JsonArray).single().jsonObject
            check(result["serverErrorCode"] == null && result["share"] != null) { "Apple did not confirm joining the album." }
        }
        check(confirmed()) { "Refresh to check whether the invitation was accepted." }
        save(key, "DONE", buildJsonObject {})
    }

    override suspend fun sharedManagement(id: String): SharedAlbumManagement = exclusive { management(fresh(id)) }
    private suspend fun management(a: Album): SharedAlbumManagement {
        if (a.scope == "legacy") {
            val details = request(legacyUrl("webgetalbumview"), buildJsonObject { putJsonArray("albumguids") { add(a.zone) } })
            val row = requireNotNull(details["albums"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.text("albumguid") == a.zone })
            val attrs = (row["attributes"] as? JsonObject).orEmpty()
            val info = request(legacyUrl("webgetsharinginfos"), buildJsonObject { putJsonArray("albums") { add(buildJsonObject { put("albumguid", a.zone); put("ownerdsid", a.owner) }) } })
            val sharing = (info["albums"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.firstOrNull { it.text("albumguid") == a.zone }
            val people = ((sharing?.get("sharinginfo") ?: info["sharinginfo"]) as? JsonArray).orEmpty().map { it.jsonObject }.map { p ->
                SharedParticipant(p.text("invitationguid") ?: p.text("personid").orEmpty(), participantName(p),
                    if (p.text("sharingtype") == "owned") SharedAlbumRole.OWNER else SharedAlbumRole.CONTRIBUTOR,
                    pending = p.text("sharingtype") == "pending", isCurrentUser = p.text("personid") == details.text("callerId"))
            }
            return SharedAlbumManagement(a.collection, people, attrs["ispublic"]?.jsonPrimitive?.content == "1",
                row.text("publicurl"), attrs["allowcontributions"]?.jsonPrimitive?.content == "1")
        }
        val share = requireNotNull(lookupShare(a.scope, a.zoneId()))
        val current = (share["currentUserParticipant"] as? JsonObject)?.text("participantId")
        val participants = (share["participants"] as? JsonArray).orEmpty().map { it.jsonObject }.map { p ->
            SharedParticipant(requireNotNull(p.text("participantId")), participantName(p), participantRole(p),
                p.text("acceptanceStatus") != "ACCEPTED", p.text("participantId") == current)
        }
        return SharedAlbumManagement(a.collection, participants, share.text("publicPermission") == "READ_WRITE",
            share.text("shortGUID")?.let(::sharedAlbumLink), allowAccessRequests = share.text("denyAccessRequests") == "false",
            temporary = share.field("sharedCollectionState")?.jsonPrimitive?.intOrNull == 1,
            invitationLinks = ((share["oneTimeStableUrlInfo"] as? JsonObject)?.get("oneTimeLinks") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.text("sharingLink") }.map(::sharedAlbumLink),
            blocked = (share["blocked"] as? JsonArray).orEmpty().map { it.jsonObject["blockedInformation"]!!.jsonObject }.map {
                SharedAccessRequest(identityKey(it), participantName(buildJsonObject { put("userIdentity", it) }))
            },
            requests = (share["requesters"] as? JsonArray).orEmpty().map { it.jsonObject["requesterInformation"]!!.jsonObject }.map {
                SharedAccessRequest(it.text("userRecordName") ?: (it["lookupInfo"] as? JsonObject)?.text("emailAddress") ?: error("Missing requester identity"),
                    participantName(buildJsonObject { put("userIdentity", it) }))
            })
    }
    private suspend fun lookup(a: Album, name: String): JsonObject = ck(a.scope, "records/lookup", buildJsonObject {
        put("zoneID", a.zoneId()); putJsonArray("records") { add(buildJsonObject { put("recordName", name) }) }
    })["records"]!!.jsonArray.single().jsonObject
    private suspend fun modify(a: Album, operation: String, record: JsonObject): JsonObject = ck(a.scope, "records/modify", buildJsonObject {
        put("atomic", true); put("zoneID", a.zoneId()); putJsonArray("operations") { add(buildJsonObject {
            put("operationType", operation); put("record", record)
        }) }
    }, write = true)
    private suspend fun patchShare(a: Album, share: JsonObject, properties: JsonObject = buildJsonObject {}, fields: JsonObject? = null) {
        modify(a, "update", buildJsonObject {
            put("recordType", "cloudkit.share"); put("recordName", "cloudkit.zoneshare")
            put("recordChangeTag", requireNotNull(share.text("recordChangeTag")))
            properties.forEach { (k, v) -> put(k, v) }; fields?.let { put("fields", it) }
        })
    }
    private fun mediaName(a: Album, media: String): String {
        require(collectionId(media) == a.collection.id) { "Photo belongs to a different shared album." }
        return String(Base64.getUrlDecoder().decode(media.substringAfterLast(':')), Charsets.UTF_8)
    }
    private fun postAlbumId(id: String): String {
        require(id.startsWith("post:")) { "Invalid post identity" }
        return collectionId(id.removePrefix("post:"))
    }
    private fun postName(a: Album, id: String): String {
        require(postAlbumId(id) == a.collection.id)
        return mediaName(a, id.removePrefix("post:"))
    }
    private suspend fun requirePost(a: Album, name: String): JsonObject {
        require(a.scope != "legacy")
        return lookup(a, name).also { check(it.text("recordType") == "CPLPost" && it["serverErrorCode"] == null && it.text("deleted") != "true") { "This post is no longer available." } }
    }
    override suspend fun sharedActivity(id: String, rank: Int): SharedActivityPage = exclusive {
        val a = fresh(id); require(a.scope != "legacy"); require(rank in 0..100000)
        val share = requireNotNull(lookupShare(a.scope, a.zoneId()))
        val me = (share["currentUserParticipant"] as? JsonObject)?.get("userIdentity")?.jsonObject?.text("userRecordName")
        val people = (share["participants"] as? JsonArray).orEmpty().map { it.jsonObject }.associateBy { (it["userIdentity"] as? JsonObject)?.text("userRecordName") }
        val rows = mutableListOf<JsonObject>(); var marker: String? = null; val seen = mutableSetOf<String>()
        do {
            val response = ck(a.scope, "records/query", buildJsonObject {
                put("zoneID", a.zoneId()); put("resultsLimit", 25)
                marker?.let { put("continuationMarker", it) }
                putJsonObject("query") {
                    put("recordType", "CPLPostByPostTimestamp")
                    putJsonArray("filterBy") {
                        add(filter("startRank", JsonPrimitive(rank), "INT64"))
                        add(filter("direction", JsonPrimitive("ASCENDING"), "STRING"))
                    }
                }
            })
            rows += requireNotNull(response["records"] as? JsonArray).map { it.jsonObject.also { r -> check(r.text("recordType") == "CPLPost" && r["serverErrorCode"] == null) } }
            marker = response.text("continuationMarker")
            check(rows.size <= 100 && (marker == null || seen.add(marker)) && seen.size <= 10) { "Invalid activity page." }
        } while (marker != null)
        check(rows.map { it.text("recordName") }.distinct().size == rows.size) { "Repeated activity records." }
        SharedActivityPage(rows.map { r ->
            val author = (r["created"] as? JsonObject)?.text("userRecordName")
            SharedPost("post:" + photoId(a, requireNotNull(r.text("recordName"))), people[author]?.let(::participantName) ?: "—", author != null && author == me,
                r.field("postTimestamp")?.jsonPrimitive?.longOrNull ?: 0, r.field("caption")?.jsonPrimitive?.contentOrNull.orEmpty())
        }, if (rows.size >= 25) rank + rows.size else null)
    }
    override suspend fun sharedPostPhotos(id: String): List<String> = exclusive {
        val a = fresh(postAlbumId(id)); val post = postName(a, id); requirePost(a, post)
        val photos = linkedSetOf<String>()
        var marker: String? = null
        val seen = mutableSetOf<String>()
        do {
            val response = ck(a.scope, "records/query", buildJsonObject {
                put("zoneID", a.zoneId()); put("resultsLimit", 8)
                marker?.let { put("continuationMarker", it) }
                putJsonObject("query") { put("recordType", "CPLAssetAndMasterInPost"); putJsonArray("filterBy") {
                    add(filter("direction", JsonPrimitive("ASCENDING"), "STRING")); add(filter("startRank", JsonPrimitive(0), "INT64"))
                    add(filter("assetBatchId", JsonPrimitive(post), "STRING"))
                } }
            })
            requireNotNull(response["records"] as? JsonArray).forEach {
                val record = it.jsonObject
                check(record["serverErrorCode"] == null)
                if (record.text("recordType") == "CPLAsset") photos += photoId(a, requireNotNull(record.text("recordName")))
            }
            marker = response.text("continuationMarker")
            marker?.let { check(seen.add(it) && seen.size <= 20) { "Incomplete post preview." } }
            // A truncated one-photo page must not route a grouped post's comments to its first photo.
        } while (marker != null && photos.size < 4)
        photos.take(4)
    }
    override suspend fun sharedPostDiscussion(id: String): SharedDiscussion = exclusive {
        val a = fresh(postAlbumId(id)); val name = postName(a, id); requirePost(a, name); discussion(a, name, post = true)
    }
    private suspend fun commentRecords(a: Album, asset: String, reaction: Boolean, post: Boolean = false): List<JsonObject> {
        val all = mutableListOf<JsonObject>(); var marker: String? = null; val seen = mutableSetOf<String>()
        do {
            val result = ck(a.scope, "records/query", buildJsonObject {
                put("zoneID", a.zoneId()); put("resultsLimit", 200); marker?.let { put("continuationMarker", it) }
                putJsonObject("query") {
                    put("recordType", (if (reaction) "CPLReactFor" else "CPLTextCommentFor") + if (post) "Post" else "Asset")
                    putJsonArray("filterBy") { add(buildJsonObject {
                        put("fieldName", if (post) "postRef" else "associatedAssetRef"); put("comparator", "EQUALS")
                        putJsonObject("fieldValue") { put("type", "REFERENCE"); putJsonObject("value") {
                            put("recordName", asset); put("zoneID", a.zoneId()); put("action", "DELETE_SELF")
                        } }
                    }) }
                }
            })
            val rows = requireNotNull(result["records"] as? JsonArray)
            all += rows.map { it.jsonObject.also { r -> check(r["serverErrorCode"] == null) } }
            marker = result.text("continuationMarker")
            if (marker != null) check(seen.add(marker) && seen.size < 1000)
        } while (marker != null)
        return all
    }
    override suspend fun sharedDiscussion(mediaId: String): SharedDiscussion = exclusive {
        val a = fresh(collectionId(mediaId)); discussion(a, mediaName(a, mediaId))
    }
    private suspend fun discussion(a: Album, asset: String, post: Boolean = false): SharedDiscussion {
        val info = requireNotNull(a.collection.shared)
        val rows = if (a.scope == "legacy") {
            val result = legacy(a, "getcomments", buildJsonObject { put("assetguid", asset) }).jsonObject
            (result["comments"] as? JsonArray).orEmpty().map { it.jsonObject }.filter { it.text("commenttype") in setOf("0", "1") }.map { r ->
                val timestamp = legacyCommentTime(r.text("commenttimestamp"))
                SharedComment(requireNotNull(r.text("commentposition")), r.text("fullname") ?: r.text("firstname") ?: "—",
                    r.text("comment") ?: "👍", timestamp, r.text("createdbyme") == "1", r.text("candelete") == "1", r.text("commenttype") == "1")
            }
        } else {
            val share = requireNotNull(lookupShare(a.scope, a.zoneId()))
            val user = ((share["currentUserParticipant"] as? JsonObject)?.get("userIdentity") as? JsonObject)?.text("userRecordName")
                ?: a.owner.takeIf { a.scope == "private" }
            val people = (share["participants"] as? JsonArray).orEmpty().map { it.jsonObject }.associateBy {
                (it["userIdentity"] as? JsonObject)?.text("userRecordName")
            }
            (commentRecords(a, asset, false, post) + commentRecords(a, asset, true, post)).map { r ->
                val creator = (r["created"] as? JsonObject)?.text("userRecordName")
                val mine = user != null && creator == user
                SharedComment(requireNotNull(r.text("recordName")), people[creator]?.let(::participantName) ?: "—",
                    (r.field("commentText") ?: r.field("emojiStringEnc"))?.jsonPrimitive?.contentOrNull.orEmpty(),
                    r.field("commentTimestamp")?.jsonPrimitive?.longOrNull ?: 0, mine, mine || info.canManage,
                    r.text("recordType") == "CPLReact")
            }
        }
        return SharedDiscussion(rows.sortedBy { it.timestamp }, info.canComment, info.generation, canRemovePhoto = !post && canRemove(a, asset))
    }

    private suspend fun canRemove(a: Album, asset: String): Boolean {
        if (a.collection.shared?.canManage == true) return true
        if (a.scope == "legacy") {
            readAlbum(a) // Author metadata must be fresh, not inferred from local file names.
            val details = request(legacyUrl("webgetalbumview"), buildJsonObject { putJsonArray("albumguids") { add(a.zone) } })
            val caller = details.text("callerId") ?: return false
            return legacyContributors[photoId(a, asset)] == caller
        }
        val record = lookup(a, asset)
        val share = requireNotNull(lookupShare(a.scope, a.zoneId()))
        val user = ((share["currentUserParticipant"] as? JsonObject)?.get("userIdentity") as? JsonObject)?.text("userRecordName")
        return user != null && (record["created"] as? JsonObject)?.text("userRecordName") == user
    }

    override suspend fun changeSharedAlbum(id: String, command: SharedCommand, operationId: String) = exclusive {
        java.util.UUID.fromString(operationId)
        require(id.startsWith("shared:${account()}:"))
        check(authorized()) { "Sign in before changing shared albums." }
        val key = "shared-action:" + digest("${account()}|$id|${command.action}|${command.subject}|${command.value}|${command.enabled}")
        val previous = requireNotNull(journal).load(journalBinding(), key)
        if (previous?.phase == "DONE" && Json.parseToJsonElement(previous.payload).jsonObject.text("operationId") == operationId) return@exclusive
        val found = discover().firstOrNull { it.collection.id == id }
        if (found == null && previous?.phase == "SENDING" && command.action in setOf(SharedAction.DELETE_ALBUM, SharedAction.LEAVE)) {
            save(key, "DONE", Json.parseToJsonElement(previous.payload).jsonObject); return@exclusive
        }
        val a = requireNotNull(found) { "Shared album is no longer available." }
        val info = requireNotNull(a.collection.shared)
        // Stable record names reconcile modern comments after a lost response. Other uncertain writes
        // require a fresh read and explicit confirmation; never blindly repeat an invitation or delete.
        val pending = if (previous?.phase == "SENDING") Json.parseToJsonElement(previous.payload).jsonObject else buildJsonObject {
            put("operationId", operationId); put("recordName", java.util.UUID.randomUUID().toString()); put("timestamp", System.currentTimeMillis())
        }
        if (previous?.phase == "SENDING") {
            if (reconciled(a, command, pending)) { save(key, "DONE", pending); return@exclusive }
            error("The last change is unconfirmed. Refresh and check iCloud before trying again.")
        }
        when (command.action) {
            SharedAction.COMMENT, SharedAction.REACTION, SharedAction.DELETE_COMMENT, SharedAction.POST_COMMENT, SharedAction.POST_REACTION, SharedAction.POST_DELETE_COMMENT -> {
                val post = command.action in setOf(SharedAction.POST_COMMENT, SharedAction.POST_REACTION, SharedAction.POST_DELETE_COMMENT)
                val action = when (command.action) { SharedAction.POST_COMMENT -> SharedAction.COMMENT; SharedAction.POST_REACTION -> SharedAction.REACTION; SharedAction.POST_DELETE_COMMENT -> SharedAction.DELETE_COMMENT; else -> command.action }
                val asset = if (post) postName(a, command.subject).also { requirePost(a, it) } else mediaName(a, command.subject)
                val thread = discussion(a, asset, post)
                require(thread.canComment || action == SharedAction.DELETE_COMMENT)
                if (action == SharedAction.COMMENT) require(command.value.isNotBlank() && command.value.length <= 1000)
                if (action == SharedAction.DELETE_COMMENT) {
                    require(thread.comments.any { it.id == command.value && it.canDelete }) { "You cannot delete this comment." }
                    save(key, "SENDING", pending)
                    if (a.scope == "legacy") legacy(a, "deletecomment", buildJsonObject { put("assetguid", asset); put("commentposition", command.value) }, true)
                    else modify(a, "delete", buildJsonObject { put("recordName", command.value) })
                } else if (a.scope == "legacy") {
                    require(action != SharedAction.REACTION || command.value in setOf("", "👍"))
                    val own = thread.comments.firstOrNull { it.reaction && it.isMine }
                    if (action == SharedAction.REACTION && command.value.isEmpty()) {
                        if (own != null) { save(key, "SENDING", pending); legacy(a, "deletecomment", buildJsonObject { put("assetguid", asset); put("commentposition", own.id) }, true) }
                    } else if (action != SharedAction.REACTION || own == null) {
                        save(key, "SENDING", pending)
                        legacy(a, "addcomment", buildJsonObject {
                            put("assetguid", asset); put("commenttype", if (action == SharedAction.COMMENT) "0" else "1")
                            put("commenttimestamp", legacyCommentTimestamp(pending.getValue("timestamp").jsonPrimitive.long))
                            if (action == SharedAction.COMMENT) put("comment", command.value)
                        }, true)
                    }
                } else {
                    val reaction = action == SharedAction.REACTION
                    if (reaction) require(command.value in setOf("", "👍", "❤️", "😍", "🎉", "🔥", "😂"))
                    val own = thread.comments.firstOrNull { it.reaction && it.isMine }
                    if (reaction && command.value.isEmpty()) {
                        if (own != null) { save(key, "SENDING", pending); modify(a, "delete", buildJsonObject { put("recordName", own.id) }) }
                    } else {
                        val existing = if (reaction && own != null) lookup(a, own.id) else null
                        save(key, "SENDING", if (existing == null) pending else JsonObject(pending + ("recordName" to JsonPrimitive(own!!.id))))
                        modify(a, if (existing == null) "create" else "update", buildJsonObject {
                            put("recordType", if (reaction) "CPLReact" else "CPLTextComment")
                            put("recordName", own?.id.takeIf { reaction } ?: pending.text("recordName")!!)
                            existing?.text("recordChangeTag")?.let { put("recordChangeTag", it) }
                            putJsonObject("fields") {
                                putJsonObject("commentTimestamp") { put("value", pending.getValue("timestamp")) }
                                putJsonObject(if (reaction) "emojiStringEnc" else "commentText") { put("value", command.value); put("isEncrypted", true); put("type", "STRING") }
                                putJsonObject(if (post) "postRef" else "associatedAssetRef") { putJsonObject("value") { put("recordName", asset); put("zoneID", a.zoneId()); put("action", "DELETE_SELF") } }
                            }
                        })
                    }
                }
            }
            SharedAction.REMOVE_MEDIA -> {
                val asset = mediaName(a, command.subject)
                require(readAlbum(a).any { it.assetRecordName == asset })
                require(canRemove(a, asset)) { "You can only remove your own contributions." }
                save(key, "SENDING", pending)
                if (a.scope == "legacy") {
                    val result = legacy(a, "deleteassets", buildJsonObject { putJsonArray("assets") { add(asset) } }, true)
                    val rows = result as? JsonArray ?: error("Unconfirmed shared removal.")
                    check(rows.any { it.jsonObject.text("assetguid") == asset && it.jsonObject.text("success") == "1" })
                } else modify(a, "delete", buildJsonObject { put("recordName", asset) })
            }
            SharedAction.SAVE_TO_LIBRARY -> { require(a.scope != "legacy"); copyToPersonal(a, mediaName(a, command.subject), key, pending); return@exclusive }
            else -> {
                if (command.action == SharedAction.LEAVE) require(info.role != SharedAlbumRole.OWNER)
                else require(info.canManage) { "Only an album manager can change sharing." }
                if (command.action == SharedAction.DELETE_ALBUM) require(info.role == SharedAlbumRole.OWNER)
                if (command.action == SharedAction.RENAME) require(command.value.isNotBlank() && command.value.length <= 255)
                if (command.action == SharedAction.INVITE) require(command.value.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) { "Enter an email address." }
                if (a.scope == "legacy") {
                    val extra = buildJsonObject {
                        when(command.action) {
                            SharedAction.RENAME -> putJsonObject("attributes") { put("name", command.value) }
                            SharedAction.CONTRIBUTIONS -> putJsonObject("attributes") { put("name", a.collection.name); put("allowcontributions", if (command.enabled) "1" else "0") }
                            SharedAction.PUBLIC_ACCESS -> put("ispublic", if (command.enabled) "1" else "0")
                            SharedAction.INVITE -> putJsonArray("invitations") { add(buildJsonObject { put("email", command.value); put("invitationguid", pending.getValue("recordName")) }) }
                            SharedAction.REMOVE_PARTICIPANT -> {
                                require(management(a).participants.any { it.id == command.subject && it.role != SharedAlbumRole.OWNER })
                                putJsonArray("invitations") { add(command.subject) }
                            }
                            SharedAction.DELETE_ALBUM, SharedAction.LEAVE -> Unit
                            else -> error("This action requires a new shared album.")
                        }
                    }
                    val path = when(command.action) {
                        SharedAction.RENAME, SharedAction.CONTRIBUTIONS -> "updatealbum"
                        SharedAction.PUBLIC_ACCESS -> "setalbumpublic"
                        SharedAction.INVITE -> "share"
                        SharedAction.REMOVE_PARTICIPANT -> "unshare"
                        SharedAction.DELETE_ALBUM -> "deletealbum"
                        SharedAction.LEAVE -> "unsubscribe"
                        else -> error("Unsupported legacy action")
                    }
                    save(key, "SENDING", pending)
                    val response = legacy(a, path, extra, true, root = true)
                    check((response as? JsonObject)?.text("hasfailure") != "1") { "Apple could not invite this participant." }
                } else {
                    val share = requireNotNull(lookupShare(a.scope, a.zoneId()))
                    val participants = (share["participants"] as? JsonArray).orEmpty().map { it.jsonObject }
                    val properties = buildJsonObject {
                        when(command.action) {
                            SharedAction.CREATE_INVITE_LINK -> {
                                require(share.text("publicPermission") != "READ_WRITE") { "Use the public link for a public album." }
                                val links = ((share["oneTimeStableUrlInfo"] as? JsonObject)?.get("oneTimeLinks") as? JsonArray).orEmpty()
                                putJsonObject("oneTimeStableUrlInfo") { put("oneTimeLinks", JsonArray(links + buildJsonObject {
                                    putJsonArray("participantId") { add(pending.getValue("recordName")) }
                                })) }
                                put("participants", JsonArray(participants + buildJsonObject {
                                    put("participantId", pending.getValue("recordName")); put("type", "USER"); put("permission", "READ_WRITE")
                                    put("customRole", "contributor"); put("acceptanceStatus", "INVITED"); put("orgUser", false); putJsonObject("userIdentity") {}
                                }))
                            }
                            SharedAction.REVOKE_INVITE_LINKS -> {
                                putJsonObject("oneTimeStableUrlInfo") { put("oneTimeLinks", JsonArray(emptyList())) }
                                put("participants", JsonArray(participants.filter { p ->
                                    val u = (p["userIdentity"] as? JsonObject).orEmpty()
                                    val contact = (u["lookupInfo"] as? JsonObject).orEmpty()
                                    p.text("type") == "OWNER" || u["userRecordName"] != null || contact["emailAddress"] != null || contact["phoneNumber"] != null
                                }))
                            }
                            SharedAction.UNBLOCK -> {
                                val blocked = (share["blocked"] as? JsonArray).orEmpty()
                                require(blocked.any { identityKey(it.jsonObject["blockedInformation"]!!.jsonObject) == command.subject })
                                put("blocked", JsonArray(blocked.filterNot { identityKey(it.jsonObject["blockedInformation"]!!.jsonObject) == command.subject }))
                            }
                            SharedAction.APPROVE_REQUEST, SharedAction.DENY_REQUEST -> {
                                val requests = (share["requesters"] as? JsonArray).orEmpty().map { it.jsonObject }
                                val target = requests.first { r -> val identity = r["requesterInformation"]!!.jsonObject
                                    (identity.text("userRecordName") ?: (identity["lookupInfo"] as? JsonObject)?.text("emailAddress")) == command.subject }
                                val identity = target["requesterInformation"]!!.jsonObject
                                if (command.action == SharedAction.DENY_REQUEST) {
                                    put("requesters", JsonArray(requests - target))
                                    put("blocked", JsonArray((share["blocked"] as? JsonArray).orEmpty() + buildJsonObject { put("blockedInformation", identity) }))
                                } else put("participants", JsonArray(participants + buildJsonObject {
                                    put("participantId", pending.getValue("recordName")); put("type", "USER"); put("permission", "READ_WRITE")
                                    put("customRole", "contributor"); put("acceptanceStatus", "INVITED"); put("orgUser", false); put("isApprovedRequester", true)
                                    putJsonObject("userIdentity") { putJsonObject("lookupInfo") {
                                        val userName = identity.text("userRecordName")
                                        if (userName != null) put("userRecordName", userName) else put("emailAddress", identity.getValue("lookupInfo").jsonObject.getValue("emailAddress"))
                                    } }
                                }))
                            }
                            SharedAction.INVITE -> put("participants", JsonArray(participants + buildJsonObject {
                                put("participantId", pending.getValue("recordName")); put("type", "USER"); put("permission", "READ_WRITE")
                                put("customRole", "contributor"); put("acceptanceStatus", "INVITED"); put("orgUser", false); put("isApprovedRequester", true)
                                putJsonObject("userIdentity") { putJsonObject("lookupInfo") { put("emailAddress", command.value) } }
                            }))
                            SharedAction.REMOVE_PARTICIPANT, SharedAction.ROLE -> {
                                val target = participants.first { it.text("participantId") == command.subject }
                                require(target.text("type") != "OWNER")
                                if (command.action == SharedAction.REMOVE_PARTICIPANT) put("participants", JsonArray(participants.filter { it != target }))
                                else {
                                    require(command.value in setOf("contributor", "commenter", "manager"))
                                    put("participants", JsonArray(participants.map { p -> if (p != target) p else JsonObject(p + mapOf(
                                        "participantId" to (if (share.text("publicPermission") == "READ_WRITE") pending.getValue("recordName") else p.getValue("participantId")),
                                        "type" to JsonPrimitive(if (command.value == "manager") "ADMINISTRATOR" else "USER"),
                                        "permission" to JsonPrimitive("READ_WRITE"), "customRole" to JsonPrimitive(if (command.value == "manager") "" else command.value))) }))
                                }
                            }
                            SharedAction.PUBLIC_ACCESS -> {
                                put("publicPermission", if (command.enabled) "READ_WRITE" else "NONE")
                                put("publicCustomAccess", if (command.enabled) "contributor" else "")
                                if (command.enabled) put("anonymousPublicAccess", true)
                                val requests = (share["requesters"] as? JsonArray).orEmpty().map { it.jsonObject }
                                if (command.enabled && requests.isNotEmpty()) {
                                    put("requesters", JsonArray(emptyList()))
                                    put("participants", JsonArray(participants + requests.map { r ->
                                        val identity = r.getValue("requesterInformation").jsonObject
                                        buildJsonObject {
                                            put("participantId", java.util.UUID.randomUUID().toString()); put("type", "USER")
                                            put("permission", "READ_WRITE"); put("customRole", "contributor"); put("acceptanceStatus", "INVITED")
                                            put("orgUser", false); put("isApprovedRequester", true)
                                            putJsonObject("userIdentity") { putJsonObject("lookupInfo") {
                                                val userName = identity.text("userRecordName")
                                                if (userName != null) put("userRecordName", userName) else put("emailAddress", identity.getValue("lookupInfo").jsonObject.getValue("emailAddress"))
                                            } }
                                        }
                                    }))
                                } else if (!command.enabled && participants.any { it.text("type") == "PUBLIC_USER" }) {
                                    put("participants", JsonArray(participants.map { p -> if (p.text("type") != "PUBLIC_USER") p else JsonObject(p + mapOf(
                                        "participantId" to JsonPrimitive(java.util.UUID.randomUUID().toString()),
                                        "type" to JsonPrimitive("USER"), "customRole" to JsonPrimitive("contributor"))) }))
                                }
                            }
                            SharedAction.ACCESS_REQUESTS -> put("denyAccessRequests", !command.enabled)
                            else -> Unit
                        }
                    }
                    val fields = buildJsonObject {
                        when(command.action) {
                            SharedAction.RENAME -> putJsonObject("cloudkit.title") { put("value", command.value) }
                            SharedAction.TEMPORARY -> {
                                putJsonObject("sharedCollectionState") { put("value", if (command.enabled) 1 else 0) }
                                if (command.enabled) putJsonObject("expiryDate") { put("value", share.field("expiryDate") ?: pending.getValue("timestamp")) }
                            }
                            SharedAction.COVER -> {
                                require(readAlbum(a).any { it.assetRecordName == mediaName(a, command.subject) })
                                val name = mediaName(a, command.subject)
                                val thumbnail = coverThumbnail(a, name)
                                putJsonObject("keyAssetIdentifier") { put("value", name) }
                                putJsonObject("cloudkit.thumbnailImageData") { put("value", thumbnail) }
                            }
                            else -> Unit
                        }
                    }
                    save(key, "SENDING", pending)
                    if (command.action in setOf(SharedAction.DELETE_ALBUM, SharedAction.LEAVE)) {
                        ck(a.scope, "zones/modify", buildJsonObject { putJsonArray("operations") { add(buildJsonObject {
                            put("operationType", "delete"); putJsonObject("zone") { put("zoneID", a.zoneId()) }
                        }) } }, true)
                    } else {
                        require(properties.isNotEmpty() || fields.isNotEmpty())
                        patchShare(a, share, properties, fields.takeIf { it.isNotEmpty() })
                    }
                }
            }
        }
        if (command.action == SharedAction.CREATE_INVITE_LINK) check(reconciled(a, command, pending)) { "Apple has not returned the invitation link yet. Reload to check it." }
        save(key, "DONE", pending)
    }

    private suspend fun reconciled(a: Album, command: SharedCommand, pending: JsonObject): Boolean = when(command.action) {
        SharedAction.RENAME -> a.collection.name == command.value
        SharedAction.PUBLIC_ACCESS -> management(a).publicAccess == command.enabled
        SharedAction.CONTRIBUTIONS -> management(a).allowContributions == command.enabled
        SharedAction.ACCESS_REQUESTS -> management(a).allowAccessRequests == command.enabled
        SharedAction.TEMPORARY -> management(a).temporary == command.enabled
        SharedAction.REVOKE_INVITE_LINKS -> management(a).invitationLinks.isEmpty()
        SharedAction.CREATE_INVITE_LINK -> {
            val share = lookupShare(a.scope, a.zoneId())
            ((share?.get("oneTimeStableUrlInfo") as? JsonObject)?.get("oneTimeLinks") as? JsonArray).orEmpty().any {
                val link = it.jsonObject
                !link.text("sharingLink").isNullOrBlank() && (link["participantId"] as? JsonArray).orEmpty().any { p -> p.jsonPrimitive.content == pending.text("recordName") }
            }
        }
        SharedAction.APPROVE_REQUEST, SharedAction.INVITE -> management(a).participants.any { it.id == pending.text("recordName") }
        SharedAction.DENY_REQUEST -> management(a).let { state -> state.requests.none { it.id == command.subject } && state.blocked.any { it.id == command.subject } }
        SharedAction.UNBLOCK -> management(a).blocked.none { it.id == command.subject }
        SharedAction.REMOVE_PARTICIPANT -> management(a).participants.none { it.id == command.subject }
        SharedAction.ROLE -> management(a).participants.any { it.id in setOf(command.subject, pending.text("recordName")) && it.role == when(command.value) {
            "manager" -> SharedAlbumRole.MANAGER; "commenter" -> SharedAlbumRole.COMMENTER; else -> SharedAlbumRole.CONTRIBUTOR
        } }
        SharedAction.REMOVE_MEDIA -> readAlbum(a).none { it.assetRecordName == mediaName(a, command.subject) }
        SharedAction.POST_DELETE_COMMENT -> discussion(a, postName(a, command.subject), true).comments.none { it.id == command.value }
        SharedAction.DELETE_COMMENT -> discussion(a, mediaName(a, command.subject)).comments.none { it.id == command.value }
        SharedAction.POST_REACTION -> discussion(a, postName(a, command.subject), true).comments.filter { it.reaction && it.isMine }.let { own ->
            if (command.value.isEmpty()) own.isEmpty() else own.any { it.text == command.value }
        }
        SharedAction.POST_COMMENT -> {
            val r = lookup(a, requireNotNull(pending.text("recordName")))
            r.text("recordType") == "CPLTextComment" && r["serverErrorCode"] == null && r.field("commentText")?.jsonPrimitive?.contentOrNull == command.value &&
                (r.field("postRef") as? JsonObject)?.text("recordName") == postName(a, command.subject)
        }
        SharedAction.REACTION -> discussion(a, mediaName(a, command.subject)).comments.filter { it.reaction && it.isMine }.let { own ->
            if (command.value.isEmpty()) own.isEmpty() else own.any { it.text == command.value }
        }
        SharedAction.COMMENT -> {
            if (a.scope == "legacy") false // Legacy comments have no client-assigned ID; text alone is not proof.
            else {
                val r = lookup(a, requireNotNull(pending.text("recordName")))
                r.text("recordType") == "CPLTextComment" && r["serverErrorCode"] == null &&
                    r.field("commentText")?.jsonPrimitive?.contentOrNull == command.value &&
                    (r.field("associatedAssetRef") as? JsonObject)?.text("recordName") == mediaName(a, command.subject)
            }
        }
        SharedAction.COVER -> lookupShare(a.scope, a.zoneId())?.field("keyAssetIdentifier")?.jsonPrimitive?.contentOrNull == mediaName(a, command.subject)
        else -> false
    }

    private suspend fun coverThumbnail(a: Album, asset: String): String {
        val photo = readAlbum(a).first { it.assetRecordName == asset }
        val bytes = java.io.ByteArrayOutputStream()
        transport.stream(photo.previewDownloadUrl, object : OutputStream() {
            override fun write(value: Int) { check(bytes.size() < 4 * 1024 * 1024); bytes.write(value) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                check(bytes.size().toLong() + length <= 4 * 1024 * 1024); bytes.write(buffer, offset, length)
            }
        })
        operationSession?.let(::checkSession)
        return SharedCoverImage.encode(bytes.toByteArray())
    }

    private suspend fun copyToPersonal(a: Album, asset: String, key: String, data: JsonObject) {
        val old = requireNotNull(journal).load(journalBinding(), key)
        var pending = old?.payload?.let { Json.parseToJsonElement(it).jsonObject } ?: data
        if (old?.phase == "DONE") return
        if (old?.phase != "POLLING") {
            require(readAlbum(a).any { it.assetRecordName == asset })
            val target = ck("private", "zones/list", buildJsonObject {})["zones"]!!.jsonArray.map { it.jsonObject["zoneID"]!!.jsonObject }.first { it.text("zoneName") == "PrimarySync" }
            save(key, "SENDING", pending)
            val result = ck("private", "records/copy/start", buildJsonObject {
                put("sourceZoneID", a.zoneId()); put("targetZoneID", target); put("sourceShareName", "cloudkit.zoneshare")
                putJsonArray("includeRecords") { add(asset) }
            }, true)
            pending = JsonObject(pending + ("jobID" to JsonPrimitive(requireNotNull(result.text("jobID")))))
            save(key, "POLLING", pending)
        }
        pollCopy(requireNotNull(pending.text("jobID")))
        save(key, "DONE", pending)
    }
    private suspend fun pollCopy(job: String) {
        repeat(20) {
            val result = ck("private", "records/copy/status", buildJsonObject { put("jobID", job) })
            when(result.text("status")) {
                "COMPLETED" -> return
                "SUBMITTED", "RUNNING", "PROCESSING" -> kotlinx.coroutines.delay(1000)
                else -> error("Apple could not finish the copy. Check the album before retrying.")
            }
        }
        error("The copy is still processing. Retry to check the same job.")
    }

    private fun filter(name: String, value: JsonPrimitive, type: String) = buildJsonObject {
        put("fieldName", name); put("comparator", "EQUALS"); putJsonObject("fieldValue") { put("type", type); put("value", value) }
    }
    private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.field(name: String) = ((get("fields") as? JsonObject)?.get(name) as? JsonObject)?.get("value")
}
