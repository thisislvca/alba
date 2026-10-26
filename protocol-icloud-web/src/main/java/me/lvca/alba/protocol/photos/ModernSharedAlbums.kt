package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.*
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.SharedAccessRequest
import me.lvca.alba.engine.model.SharedAction
import me.lvca.alba.engine.model.SharedActivityPage
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedAlbumInfo
import me.lvca.alba.engine.model.SharedAlbumManagement
import me.lvca.alba.engine.model.SharedAlbumRole
import me.lvca.alba.engine.model.SharedCommand
import me.lvca.alba.engine.model.SharedComment
import me.lvca.alba.engine.model.SharedDiscussion
import me.lvca.alba.engine.model.SharedParticipant
import me.lvca.alba.engine.model.SharedPost
import me.lvca.alba.protocol.array
import me.lvca.alba.protocol.field
import me.lvca.alba.protocol.network.AppleHttpTransport
import me.lvca.alba.protocol.obj
import me.lvca.alba.protocol.text
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.UUID

/** Shared albums stored as CloudKit zones and shared through a `cloudkit.share` record. */
internal class ModernSharedAlbums(
    private val connection: SharedAlbumSession,
    private val ids: SharedAlbumIds,
    private val transport: AppleHttpTransport,
) : SharedAlbumBackend {
    private val parser = CloudKitPhotoResponseParser()

    override suspend fun discover(): List<SharedAlbumRecord> {
        val current = connection.bound()
        if (current.webservices["ckdatabasews"] == null) return emptyList()
        val result = mutableListOf<SharedAlbumRecord>()
        // Albums this account owns live in its private database, subscribed ones in the shared database.
        for (scope in listOf("private", "shared")) {
            for (zone in sharedZones(scope)) {
                val share = lookupShare(scope, zone) ?: continue
                val name = zone.text("zoneName")!!
                val owner = requireNotNull(zone.text("ownerRecordName")) { "Shared zone has no owner." }
                val participants = share.array("participants")
                val user = share.obj("currentUserParticipant")
                    ?: participants.map { it.jsonObject }.firstOrNull { it.text("isCurrentUser") == "true" }
                // Private zones belong to this account; subscribed zones fail closed if role data is absent.
                val role = when {
                    user?.text("type") == "OWNER" || (user == null && scope == "private") -> SharedAlbumRole.OWNER
                    user?.text("acceptanceStatus") == "ACCEPTED" && user.text("type") == "ADMINISTRATOR" -> SharedAlbumRole.MANAGER
                    user?.text("acceptanceStatus") == "ACCEPTED" && user.text("permission") == "READ_WRITE" && user.text("customRole") == "commenter" -> SharedAlbumRole.COMMENTER
                    user?.text("acceptanceStatus") == "ACCEPTED" && user.text("permission") == "READ_WRITE" -> SharedAlbumRole.CONTRIBUTOR
                    else -> SharedAlbumRole.VIEWER
                }
                val info = SharedAlbumInfo(SharedAlbumGeneration.MODERN, role, participantCount = participants.size,
                    webUrl = share.text("shortGUID")?.let { sharedAlbumWebUrl(current, "sharedalbums/sc,", it) })
                result += SharedAlbumRecord(GalleryCollection(ids.album(scope, owner, name),
                    share.field("cloudkit.title")?.jsonPrimitive?.contentOrNull ?: name, shared = info), scope, owner, name)
            }
        }
        return result
    }

    /** The zone IDs of the shared-album zones in [scope], read through all change pages. */
    private suspend fun sharedZones(scope: String): Collection<JsonObject> {
        val zones = linkedMapOf<String, JsonObject>()
        val tokens = mutableSetOf<String>()
        var token: String? = null
        do {
            val root = connection.ck(scope, "changes/database", buildJsonObject { token?.let { put("syncToken", it) } })
            for (entry in requireNotNull(root["zones"] as? JsonArray) { "Incomplete shared zone list." }) {
                val zone = entry.jsonObject
                val id = zone["zoneID"]?.jsonObject ?: continue
                val name = id.text("zoneName") ?: continue
                if (!name.startsWith(ZONE_PREFIX)) continue
                val key = "${id.text("ownerRecordName")}:$name"
                if (zone.text("deleted") in setOf("true", "1")) zones.remove(key) else zones[key] = id
            }
            val more = root.text("moreComing") == "true"
            token = if (more) requireNotNull(root.text("syncToken")) else null
            if (token != null) check(tokens.add(token) && tokens.size < 1000) { "Repeated shared zone cursor." }
        } while (token != null)
        return zones.values
    }

    /** The zone's share record, or null when the zone is not shared or, unless [creating], no longer accessible. */
    suspend fun lookupShare(scope: String, zone: JsonObject, creating: Boolean = false): JsonObject? {
        val response = connection.ck(scope, "records/lookup", buildJsonObject {
            put("zoneID", zone); putJsonArray("records") { add(buildJsonObject { put("recordName", SHARE_RECORD) }) }
        })
        val record = requireNotNull(response["records"]?.jsonArray?.singleOrNull()?.jsonObject) { "Missing shared album metadata." }
        if (record.text("serverErrorCode") == "NOT_FOUND") return null
        if (!creating && record.text("serverErrorCode") in setOf("ACCESS_DENIED", "ZONE_NOT_FOUND")) return null
        check(record["serverErrorCode"] == null) { "Shared album metadata is unavailable." }
        check(record.text("recordType") == "cloudkit.share") { "Unexpected shared album metadata." }
        return record
    }

    private suspend fun share(album: SharedAlbumRecord): JsonObject = requireNotNull(lookupShare(album.scope, album.zoneId()))

    suspend fun lookup(album: SharedAlbumRecord, name: String): JsonObject = connection.ck(album.scope, "records/lookup", buildJsonObject {
        put("zoneID", album.zoneId()); putJsonArray("records") { add(buildJsonObject { put("recordName", name) }) }
    })["records"]!!.jsonArray.single().jsonObject

    suspend fun modify(album: SharedAlbumRecord, operation: String, record: JsonObject, recordErrors: Boolean = false): JsonObject =
        connection.ck(album.scope, "records/modify", buildJsonObject {
            put("atomic", true); put("zoneID", album.zoneId())
            putJsonArray("operations") { add(buildJsonObject { put("operationType", operation); put("record", record) }) }
        }, write = true, recordErrors = recordErrors)

    /** Updates the share at the change tag that was read, so a concurrent edit fails instead of being lost. */
    private suspend fun patchShare(album: SharedAlbumRecord, share: JsonObject, properties: JsonObject, fields: JsonObject?) {
        modify(album, "update", buildJsonObject {
            put("recordType", "cloudkit.share"); put("recordName", SHARE_RECORD)
            put("recordChangeTag", requireNotNull(share.text("recordChangeTag")))
            properties.forEach { (key, value) -> put(key, value) }; fields?.let { put("fields", it) }
        })
    }

    override suspend fun readAlbum(album: SharedAlbumRecord): List<CloudPhotoAsset> {
        val output = linkedMapOf<String, CloudPhotoAsset>()
        var rank = 0
        var accumulated = 0
        var marker: String? = null
        val cursors = mutableSetOf<String>()
        do {
            check(cursors.add("$rank:$marker") && cursors.size <= 2000) { "Repeated shared album cursor." }
            val root = connection.ck(album.scope, "records/query", buildJsonObject {
                put("zoneID", album.zoneId()); put("resultsLimit", 200)
                putJsonObject("query") {
                    put("recordType", "CPLAssetAndMasterByAddedDate")
                    putJsonArray("filterBy") {
                        add(equalsFilter("direction", JsonPrimitive("ASCENDING"), "STRING"))
                        add(equalsFilter("startRank", JsonPrimitive(rank), "INT64"))
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
        return output.values.toList()
    }

    override suspend fun management(album: SharedAlbumRecord): SharedAlbumManagement {
        val share = share(album)
        val current = share.obj("currentUserParticipant")?.text("participantId")
        val participants = share.array("participants").map { it.jsonObject }.map { person ->
            SharedParticipant(requireNotNull(person.text("participantId")), SharedParticipants.name(person), SharedParticipants.role(person),
                person.text("acceptanceStatus") != "ACCEPTED", person.text("participantId") == current)
        }
        val session = connection.bound()
        return SharedAlbumManagement(album.collection, participants, share.text("publicPermission") == "READ_WRITE",
            share.text("shortGUID")?.let { sharedAlbumLink(session, it) }, allowAccessRequests = share.text("denyAccessRequests") == "false",
            temporary = share.field("sharedCollectionState")?.jsonPrimitive?.intOrNull == 1,
            invitationLinks = ModernShareEdits.oneTimeLinks(share).mapNotNull { (it as? JsonObject)?.text("sharingLink") }.map { sharedAlbumLink(session, it) },
            blocked = share.array("blocked").map { it.jsonObject["blockedInformation"]!!.jsonObject }.map {
                SharedAccessRequest(SharedParticipants.identityKey(it), SharedParticipants.nameOfIdentity(it))
            },
            requests = share.array("requesters").map { it.jsonObject["requesterInformation"]!!.jsonObject }.map {
                SharedAccessRequest(SharedParticipants.identityKey(it), SharedParticipants.nameOfIdentity(it))
            })
    }

    override suspend fun comments(album: SharedAlbumRecord, asset: String, post: Boolean, canManage: Boolean): List<SharedComment> {
        val share = share(album)
        val user = SharedParticipants.currentUser(share) ?: album.owner.takeIf { album.scope == "private" }
        val people = share.array("participants").map { it.jsonObject }.associateBy { it.obj("userIdentity")?.text("userRecordName") }
        return (commentRecords(album, asset, reaction = false, post) + commentRecords(album, asset, reaction = true, post)).map { record ->
            val creator = record.obj("created")?.text("userRecordName")
            val mine = user != null && creator == user
            SharedComment(requireNotNull(record.text("recordName")), people[creator]?.let(SharedParticipants::name) ?: "—",
                (record.field("commentText") ?: record.field("emojiStringEnc"))?.jsonPrimitive?.contentOrNull.orEmpty(),
                record.field("commentTimestamp")?.jsonPrimitive?.longOrNull ?: 0, mine, mine || canManage,
                record.text("recordType") == "CPLReact")
        }
    }

    private suspend fun commentRecords(album: SharedAlbumRecord, asset: String, reaction: Boolean, post: Boolean): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var marker: String? = null
        val seen = mutableSetOf<String>()
        do {
            val result = connection.ck(album.scope, "records/query", buildJsonObject {
                put("zoneID", album.zoneId()); put("resultsLimit", 200); marker?.let { put("continuationMarker", it) }
                putJsonObject("query") {
                    put("recordType", (if (reaction) "CPLReactFor" else "CPLTextCommentFor") + if (post) "Post" else "Asset")
                    putJsonArray("filterBy") { add(buildJsonObject {
                        put("fieldName", referenceField(post)); put("comparator", "EQUALS")
                        putJsonObject("fieldValue") { put("type", "REFERENCE"); put("value", reference(album, asset)) }
                    }) }
                }
            })
            val rows = requireNotNull(result["records"] as? JsonArray)
            all += rows.map { it.jsonObject.also { row -> check(row["serverErrorCode"] == null) } }
            marker = result.text("continuationMarker")
            if (marker != null) check(seen.add(marker) && seen.size < 1000)
        } while (marker != null)
        return all
    }

    override suspend fun isOwnContribution(album: SharedAlbumRecord, asset: String): Boolean {
        val record = lookup(album, asset)
        val user = SharedParticipants.currentUser(share(album))
        return user != null && record.obj("created")?.text("userRecordName") == user
    }

    override fun newZoneName(): String = "$ZONE_PREFIX${UUID.randomUUID()}"

    override suspend fun create(
        name: String,
        zoneName: String,
        markPhase: suspend (String) -> Unit,
        discover: suspend () -> List<SharedAlbumRecord>,
    ): SharedAlbumRecord {
        // The zone name is ours, so the zone and share of an earlier attempt are found, never created twice.
        val listed = connection.ck("private", "zones/list", buildJsonObject {})["zones"]?.jsonArray
            ?: error("Could not check pending shared albums.")
        var zone = listed.mapNotNull { it.jsonObject["zoneID"] as? JsonObject }.firstOrNull { it.text("zoneName") == zoneName }
        if (zone == null) {
            markPhase("ZONE_SENDING")
            val result = connection.ck("private", "zones/modify", buildJsonObject {
                putJsonArray("operations") { add(buildJsonObject {
                    put("operationType", "create"); putJsonObject("zone") { putJsonObject("zoneID") { put("zoneName", zoneName) } }
                }) }
            }, write = true)
            zone = requireNotNull(result["zones"]?.jsonArray?.singleOrNull()?.jsonObject?.get("zoneID") as? JsonObject) {
                "Refresh to check whether the shared album was created."
            }
        }
        check(zone.text("zoneName") == zoneName)
        markPhase("ZONE_CREATED")
        if (lookupShare("private", zone, creating = true) == null) {
            markPhase("SHARE_SENDING")
            connection.ck("private", "records/modify", buildJsonObject {
                put("atomic", true); put("zoneID", zone)
                putJsonArray("operations") { add(buildJsonObject {
                    put("operationType", "create")
                    putJsonObject("record") {
                        put("recordName", SHARE_RECORD); put("recordType", "cloudkit.share")
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
        return discover().firstOrNull { it.scope == "private" && it.zone == zoneName }
            ?: error("Refresh to check whether the shared album was created.")
    }

    override suspend fun deleteComment(album: SharedAlbumRecord, asset: String, commentId: String) {
        modify(album, "delete", buildJsonObject { put("recordName", commentId) })
    }

    override suspend fun writeComment(
        album: SharedAlbumRecord,
        asset: String,
        post: Boolean,
        reaction: Boolean,
        value: String,
        thread: SharedDiscussion,
        pending: JsonObject,
        markSending: suspend (JsonObject) -> Unit,
    ) {
        if (reaction) require(value in REACTIONS)
        val own = thread.comments.firstOrNull { it.reaction && it.isMine }
        if (reaction && value.isEmpty()) {
            if (own != null) {
                markSending(pending)
                deleteComment(album, asset, own.id)
            }
            return
        }
        // A person has one reaction, which is updated in place; each comment is a new record.
        val existing = if (reaction && own != null) lookup(album, own.id) else null
        markSending(if (existing == null) pending else JsonObject(pending + ("recordName" to JsonPrimitive(own!!.id))))
        modify(album, if (existing == null) "create" else "update", buildJsonObject {
            put("recordType", if (reaction) "CPLReact" else "CPLTextComment")
            put("recordName", own?.id.takeIf { reaction } ?: pending.text("recordName")!!)
            existing?.text("recordChangeTag")?.let { put("recordChangeTag", it) }
            putJsonObject("fields") {
                putJsonObject("commentTimestamp") { put("value", pending.getValue("timestamp")) }
                putJsonObject(if (reaction) "emojiStringEnc" else "commentText") { put("value", value); put("isEncrypted", true); put("type", "STRING") }
                putJsonObject(referenceField(post)) { put("value", reference(album, asset)) }
            }
        })
    }

    override suspend fun removeMedia(album: SharedAlbumRecord, asset: String) {
        modify(album, "delete", buildJsonObject { put("recordName", asset) })
    }

    override suspend fun changeSharing(
        album: SharedAlbumRecord,
        command: SharedCommand,
        pending: JsonObject,
        markSending: suspend () -> Unit,
    ) {
        val share = share(album)
        val properties = ModernShareEdits.properties(share, command, pending)
        val fields = buildJsonObject {
            when (command.action) {
                SharedAction.RENAME -> putJsonObject("cloudkit.title") { put("value", command.value) }
                SharedAction.TEMPORARY -> {
                    putJsonObject("sharedCollectionState") { put("value", if (command.enabled) 1 else 0) }
                    if (command.enabled) putJsonObject("expiryDate") { put("value", share.field("expiryDate") ?: pending.getValue("timestamp")) }
                }
                SharedAction.COVER -> {
                    require(readAlbum(album).any { it.assetRecordName == ids.recordName(album, command.subject) })
                    val name = ids.recordName(album, command.subject)
                    val thumbnail = coverThumbnail(album, name)
                    putJsonObject("keyAssetIdentifier") { put("value", name) }
                    putJsonObject("cloudkit.thumbnailImageData") { put("value", thumbnail) }
                }
                else -> Unit
            }
        }
        markSending()
        if (command.action in setOf(SharedAction.DELETE_ALBUM, SharedAction.LEAVE)) {
            connection.ck(album.scope, "zones/modify", buildJsonObject { putJsonArray("operations") { add(buildJsonObject {
                put("operationType", "delete"); putJsonObject("zone") { put("zoneID", album.zoneId()) }
            }) } }, write = true)
        } else {
            require(properties.isNotEmpty() || fields.isNotEmpty())
            patchShare(album, share, properties, fields.takeIf { it.isNotEmpty() })
        }
    }

    override suspend fun commentConfirmed(album: SharedAlbumRecord, asset: String, post: Boolean, text: String, pending: JsonObject): Boolean {
        val record = lookup(album, requireNotNull(pending.text("recordName")))
        return record.text("recordType") == "CPLTextComment" && record["serverErrorCode"] == null &&
            record.field("commentText")?.jsonPrimitive?.contentOrNull == text &&
            (record.field(referenceField(post)) as? JsonObject)?.text("recordName") == asset
    }

    /** Whether the one-time link journaled in [pending] has been issued. */
    suspend fun invitationLinkIssued(album: SharedAlbumRecord, pending: JsonObject): Boolean {
        val share = lookupShare(album.scope, album.zoneId()) ?: return false
        return ModernShareEdits.oneTimeLinks(share).any {
            val link = it.jsonObject
            !link.text("sharingLink").isNullOrBlank() && link.array("participantId").any { id -> id.jsonPrimitive.content == pending.text("recordName") }
        }
    }

    suspend fun coverAsset(album: SharedAlbumRecord): String? =
        lookupShare(album.scope, album.zoneId())?.field("keyAssetIdentifier")?.jsonPrimitive?.contentOrNull

    suspend fun requirePost(album: SharedAlbumRecord, name: String): JsonObject {
        require(!album.isLegacy)
        return lookup(album, name).also {
            check(it.text("recordType") == "CPLPost" && it["serverErrorCode"] == null && it.text("deleted") != "true") { "This post is no longer available." }
        }
    }

    suspend fun activity(album: SharedAlbumRecord, rank: Int): SharedActivityPage {
        val share = share(album)
        val me = SharedParticipants.currentUser(share)
        val people = share.array("participants").map { it.jsonObject }.associateBy { it.obj("userIdentity")?.text("userRecordName") }
        val rows = mutableListOf<JsonObject>()
        var marker: String? = null
        val seen = mutableSetOf<String>()
        do {
            val response = connection.ck(album.scope, "records/query", buildJsonObject {
                put("zoneID", album.zoneId()); put("resultsLimit", ACTIVITY_PAGE)
                marker?.let { put("continuationMarker", it) }
                putJsonObject("query") {
                    put("recordType", "CPLPostByPostTimestamp")
                    putJsonArray("filterBy") {
                        add(equalsFilter("startRank", JsonPrimitive(rank), "INT64"))
                        add(equalsFilter("direction", JsonPrimitive("ASCENDING"), "STRING"))
                    }
                }
            })
            rows += requireNotNull(response["records"] as? JsonArray).map {
                it.jsonObject.also { row -> check(row.text("recordType") == "CPLPost" && row["serverErrorCode"] == null) }
            }
            marker = response.text("continuationMarker")
            check(rows.size <= 100 && (marker == null || seen.add(marker)) && seen.size <= 10) { "Invalid activity page." }
        } while (marker != null)
        check(rows.map { it.text("recordName") }.distinct().size == rows.size) { "Repeated activity records." }
        return SharedActivityPage(rows.map { row ->
            val author = row.obj("created")?.text("userRecordName")
            SharedPost(ids.post(album, requireNotNull(row.text("recordName"))), people[author]?.let(SharedParticipants::name) ?: "—",
                author != null && author == me, row.field("postTimestamp")?.jsonPrimitive?.longOrNull ?: 0,
                row.field("caption")?.jsonPrimitive?.contentOrNull.orEmpty())
        }, if (rows.size >= ACTIVITY_PAGE) rank + rows.size else null)
    }

    /** The photos of a post, up to the limit asked for; [complete] is false when the post holds more. */
    data class PostPhotos(val photoIds: List<String>, val complete: Boolean)

    /** Up to [limit] photos of the post [post], in posting order. */
    suspend fun postPhotos(album: SharedAlbumRecord, post: String, limit: Int): PostPhotos {
        require(limit in 1..MAX_POST_PHOTOS)
        val photos = linkedSetOf<String>()
        var marker: String? = null
        val seen = mutableSetOf<String>()
        do {
            val response = connection.ck(album.scope, "records/query", buildJsonObject {
                // Each photo is an asset and a master record.
                put("zoneID", album.zoneId()); put("resultsLimit", minOf(200, (limit + 1) * 2))
                marker?.let { put("continuationMarker", it) }
                putJsonObject("query") { put("recordType", "CPLAssetAndMasterInPost"); putJsonArray("filterBy") {
                    add(equalsFilter("direction", JsonPrimitive("ASCENDING"), "STRING")); add(equalsFilter("startRank", JsonPrimitive(0), "INT64"))
                    add(equalsFilter("assetBatchId", JsonPrimitive(post), "STRING"))
                } }
            })
            requireNotNull(response["records"] as? JsonArray).forEach {
                val record = it.jsonObject
                check(record["serverErrorCode"] == null)
                if (record.text("recordType") == "CPLAsset") photos += ids.photo(album, requireNotNull(record.text("recordName")))
            }
            marker = response.text("continuationMarker")
            marker?.let { check(seen.add(it) && seen.size <= MAX_POST_PAGES) { "Incomplete post preview." } }
            // A truncated one-photo page must not route a grouped post's comments to its first photo.
        } while (marker != null && photos.size <= limit)
        return PostPhotos(photos.take(limit), complete = photos.size <= limit && marker == null)
    }

    /** How many reactions the post [post] has. */
    suspend fun reactionCount(album: SharedAlbumRecord, post: String): Int = commentRecords(album, post, reaction = true, post = true).size

    private suspend fun coverThumbnail(album: SharedAlbumRecord, asset: String): String {
        val photo = readAlbum(album).first { it.assetRecordName == asset }
        val bytes = ByteArrayOutputStream()
        transport.stream(photo.previewDownloadUrl, object : OutputStream() {
            override fun write(value: Int) { check(bytes.size() < MAX_COVER_BYTES); bytes.write(value) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                check(bytes.size().toLong() + length <= MAX_COVER_BYTES); bytes.write(buffer, offset, length)
            }
        })
        connection.checkOperation()
        return SharedCoverImage.encode(bytes.toByteArray())
    }

    /** A reference that CloudKit deletes along with the photo or post it points to. */
    private fun reference(album: SharedAlbumRecord, recordName: String) = buildJsonObject {
        put("recordName", recordName); put("zoneID", album.zoneId()); put("action", "DELETE_SELF")
    }

    private fun referenceField(post: Boolean) = if (post) "postRef" else "associatedAssetRef"

    private fun equalsFilter(name: String, value: JsonPrimitive, type: String) = buildJsonObject {
        put("fieldName", name); put("comparator", "EQUALS"); putJsonObject("fieldValue") { put("type", type); put("value", value) }
    }

    companion object {
        /** Every modern shared album zone is named with this prefix. */
        const val ZONE_PREFIX = "SharedCollection-"
        private const val SHARE_RECORD = "cloudkit.zoneshare"
        private const val ACTIVITY_PAGE = 25

        /** The most photos read for one post. */
        const val MAX_POST_PHOTOS = 500

        /** Photos a post preview reads: the four shown, and enough more to say "+N" for most posts. */
        const val PREVIEW_COUNT_PROBE = 20
        private const val MAX_POST_PAGES = 50
        private const val MAX_COVER_BYTES = 4 * 1024 * 1024
        private val REACTIONS = setOf("", "👍", "❤️", "😍", "🎉", "🔥", "😂")
    }
}
