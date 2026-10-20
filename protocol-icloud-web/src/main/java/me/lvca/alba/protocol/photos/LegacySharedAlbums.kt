package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.*
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.SharedAction
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedAlbumInfo
import me.lvca.alba.engine.model.SharedAlbumManagement
import me.lvca.alba.engine.model.SharedAlbumRole
import me.lvca.alba.engine.model.SharedCommand
import me.lvca.alba.engine.model.SharedComment
import me.lvca.alba.engine.model.SharedDiscussion
import me.lvca.alba.engine.model.SharedParticipant
import me.lvca.alba.protocol.field
import me.lvca.alba.protocol.network.AppleEndpointPolicy
import me.lvca.alba.protocol.obj
import me.lvca.alba.protocol.text
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Shared albums of the legacy shared-streams web service. */
internal class LegacySharedAlbums(
    private val connection: SharedAlbumSession,
    private val ids: SharedAlbumIds,
) : SharedAlbumBackend {
    private val parser = CloudKitPhotoResponseParser()

    /** Who contributed each photo, by shared photo ID, as of the album's last full read. */
    private val contributors = ConcurrentHashMap<String, String>()

    fun forgetAccount() = contributors.clear()

    /** A shared-streams URL on the signed-in account's own shard. */
    fun accountUrl(path: String): String {
        val session = connection.bound()
        return "${requireNotNull(session.webservices["sharedstreams"]).trimEnd('/')}/${session.dsid}/sharedstreams/$path"
    }

    /** A request to [album]'s own shard, which Apple names in the album list. */
    private suspend fun albumRequest(album: SharedAlbumRecord, path: String, body: JsonObject) =
        connection.request("${requireNotNull(album.location).trimEnd('/')}/$path", body)

    /** Calls [path] for [album] with its GUID, on the album's shard or, with [accountShard], the account's. */
    private suspend fun albumCall(
        album: SharedAlbumRecord,
        path: String,
        extra: JsonObject = buildJsonObject {},
        write: Boolean = false,
        accountShard: Boolean = false,
    ): JsonElement = connection.requestElement(
        if (accountShard) accountUrl(path) else "${requireNotNull(album.location).trimEnd('/')}/$path",
        JsonObject(mapOf("albumguid" to JsonPrimitive(album.zone)) + extra),
        write,
    )

    override suspend fun discover(): List<SharedAlbumRecord> {
        val current = connection.bound()
        val root = current.webservices["sharedstreams"] ?: return emptyList()
        val response = connection.request("${root.trimEnd('/')}/${current.dsid}/sharedstreams/webgetalbumslist", buildJsonObject {})
        val rows = requireNotNull(response["albums"] as? JsonArray) { "Incomplete shared album list." }
        return rows.map { value ->
            val row = value.jsonObject
            val attributes = row.obj("attributes") ?: JsonObject(emptyMap())
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
            val id = ids.album(SharedAlbumRecord.LEGACY_SCOPE, owner, guid)
            val info = SharedAlbumInfo(SharedAlbumGeneration.LEGACY, role, webUrl = sharedAlbumWebUrl(current, "sa,", guid))
            SharedAlbumRecord(GalleryCollection(id, attributes.text("name") ?: guid, shared = info),
                SharedAlbumRecord.LEGACY_SCOPE, owner, guid, location, row.text("albumctag"))
        }
    }

    override suspend fun readAlbum(album: SharedAlbumRecord): List<CloudPhotoAsset> {
        val output = linkedMapOf<String, CloudPhotoAsset>()
        val count = albumRequest(album, "webgetassetcount", buildJsonObject { put("albumguid", album.zone) })
            .text("albumassetcount")?.toIntOrNull() ?: error("Missing shared album count.")
        require(count in 0..100000) { "Shared album is too large." }
        var offset = 0
        while (offset < count) {
            val end = minOf(offset + 100, count)
            val root = albumRequest(album, "webgetassets", buildJsonObject {
                put("albumguid", album.zone); put("offset", offset.toString()); put("limit", end.toString())
                album.ctag?.let { put("albumctag", it) }
            })
            (root["records"] as? JsonArray).orEmpty().map { it.jsonObject }.forEach { record ->
                if (record.text("recordType") == "CPLAsset") {
                    val identity = ids.photo(album, requireNotNull(record.text("recordName")))
                    val contributor = record.field("contributedBy")?.jsonPrimitive?.contentOrNull
                    if (contributor == null) contributors.remove(identity) else contributors[identity] = contributor
                }
            }
            val page = parser.parse(root.toString(), legacy = true)
            check(page.logicalAssetCount == end - offset && page.photos.size == page.logicalAssetCount) { "Shared album changed during loading. Refresh again." }
            page.photos.forEach { check(output.put(it.assetRecordName, it) == null) { "Repeated shared album page." } }
            offset = end
        }
        return output.values.toList()
    }

    private suspend fun albumView(album: SharedAlbumRecord): JsonObject =
        connection.request(accountUrl("webgetalbumview"), buildJsonObject { putJsonArray("albumguids") { add(album.zone) } })

    override suspend fun management(album: SharedAlbumRecord): SharedAlbumManagement {
        val details = albumView(album)
        val row = requireNotNull(details["albums"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.text("albumguid") == album.zone })
        val attributes = row.obj("attributes").orEmpty()
        val info = connection.request(accountUrl("webgetsharinginfos"), buildJsonObject {
            putJsonArray("albums") { add(buildJsonObject { put("albumguid", album.zone); put("ownerdsid", album.owner) }) }
        })
        val sharing = (info["albums"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.firstOrNull { it.text("albumguid") == album.zone }
        val people = ((sharing?.get("sharinginfo") ?: info["sharinginfo"]) as? JsonArray).orEmpty().map { it.jsonObject }.map { person ->
            SharedParticipant(person.text("invitationguid") ?: person.text("personid").orEmpty(), SharedParticipants.name(person),
                if (person.text("sharingtype") == "owned") SharedAlbumRole.OWNER else SharedAlbumRole.CONTRIBUTOR,
                pending = person.text("sharingtype") == "pending", isCurrentUser = person.text("personid") == details.text("callerId"))
        }
        return SharedAlbumManagement(album.collection, people, attributes["ispublic"]?.jsonPrimitive?.content == "1",
            row.text("publicurl"), attributes["allowcontributions"]?.jsonPrimitive?.content == "1")
    }

    override suspend fun comments(album: SharedAlbumRecord, asset: String, post: Boolean, canManage: Boolean): List<SharedComment> {
        val result = albumCall(album, "getcomments", buildJsonObject { put("assetguid", asset) }).jsonObject
        // Type 0 is a comment and 1 a like; other types are deleted entries.
        return (result["comments"] as? JsonArray).orEmpty().map { it.jsonObject }.filter { it.text("commenttype") in setOf("0", "1") }.map { row ->
            SharedComment(requireNotNull(row.text("commentposition")), row.text("fullname") ?: row.text("firstname") ?: "—",
                row.text("comment") ?: "👍", legacyCommentTime(row.text("commenttimestamp")), row.text("createdbyme") == "1",
                row.text("candelete") == "1", row.text("commenttype") == "1")
        }
    }

    override suspend fun isOwnContribution(album: SharedAlbumRecord, asset: String): Boolean {
        readAlbum(album) // Author metadata must be fresh, not inferred from local file names.
        val caller = albumView(album).text("callerId") ?: return false
        return contributors[ids.photo(album, asset)] == caller
    }

    override fun newZoneName(): String = UUID.randomUUID().toString().uppercase()

    override suspend fun create(
        name: String,
        zoneName: String,
        markPhase: suspend (String) -> Unit,
        discover: suspend () -> List<SharedAlbumRecord>,
    ): SharedAlbumRecord {
        // The album GUID is ours, so an earlier attempt whose response was lost is found by it.
        discover().firstOrNull { it.isLegacy && it.zone == zoneName }?.let { return it }
        markPhase("CREATING_LEGACY")
        connection.request(accountUrl("createalbum"), buildJsonObject {
            put("albumguid", zoneName)
            putJsonObject("attributes") { put("name", name); put("ispublic", "0"); put("allowcontributions", "1") }
        }, write = true)
        return requireNotNull(discover().firstOrNull { it.isLegacy && it.zone == zoneName }) { "Refresh to check the new shared album." }
    }

    override suspend fun deleteComment(album: SharedAlbumRecord, asset: String, commentId: String) {
        albumCall(album, "deletecomment", buildJsonObject { put("assetguid", asset); put("commentposition", commentId) }, write = true)
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
        // Shared streams have one kind of reaction, a like.
        require(!reaction || value in setOf("", "👍"))
        val own = thread.comments.firstOrNull { it.reaction && it.isMine }
        if (reaction && value.isEmpty()) {
            if (own != null) {
                markSending(pending)
                deleteComment(album, asset, own.id)
            }
        } else if (!reaction || own == null) {
            markSending(pending)
            albumCall(album, "addcomment", buildJsonObject {
                put("assetguid", asset); put("commenttype", if (reaction) "1" else "0")
                put("commenttimestamp", legacyCommentTimestamp(pending.getValue("timestamp").jsonPrimitive.long))
                if (!reaction) put("comment", value)
            }, write = true)
        }
    }

    override suspend fun removeMedia(album: SharedAlbumRecord, asset: String) {
        val result = albumCall(album, "deleteassets", buildJsonObject { putJsonArray("assets") { add(asset) } }, write = true)
        val rows = result as? JsonArray ?: error("Unconfirmed shared removal.")
        check(rows.any { it.jsonObject.text("assetguid") == asset && it.jsonObject.text("success") == "1" })
    }

    override suspend fun changeSharing(
        album: SharedAlbumRecord,
        command: SharedCommand,
        pending: JsonObject,
        markSending: suspend () -> Unit,
    ) {
        val extra = buildJsonObject {
            when (command.action) {
                SharedAction.RENAME -> putJsonObject("attributes") { put("name", command.value) }
                SharedAction.CONTRIBUTIONS -> putJsonObject("attributes") {
                    put("name", album.collection.name); put("allowcontributions", if (command.enabled) "1" else "0")
                }
                SharedAction.PUBLIC_ACCESS -> put("ispublic", if (command.enabled) "1" else "0")
                SharedAction.INVITE -> putJsonArray("invitations") {
                    add(buildJsonObject { put("email", command.value); put("invitationguid", pending.getValue("recordName")) })
                }
                SharedAction.REMOVE_PARTICIPANT -> {
                    require(management(album).participants.any { it.id == command.subject && it.role != SharedAlbumRole.OWNER })
                    putJsonArray("invitations") { add(command.subject) }
                }
                SharedAction.DELETE_ALBUM, SharedAction.LEAVE -> Unit
                else -> error("This action requires a new shared album.")
            }
        }
        val path = when (command.action) {
            SharedAction.RENAME, SharedAction.CONTRIBUTIONS -> "updatealbum"
            SharedAction.PUBLIC_ACCESS -> "setalbumpublic"
            SharedAction.INVITE -> "share"
            SharedAction.REMOVE_PARTICIPANT -> "unshare"
            SharedAction.DELETE_ALBUM -> "deletealbum"
            SharedAction.LEAVE -> "unsubscribe"
            else -> error("Unsupported legacy action")
        }
        markSending()
        val response = albumCall(album, path, extra, write = true, accountShard = true)
        check((response as? JsonObject)?.text("hasfailure") != "1") { "Apple could not invite this participant." }
    }

    /** Legacy comments have no client-assigned ID, and matching text alone is not proof. */
    override suspend fun commentConfirmed(album: SharedAlbumRecord, asset: String, post: Boolean, text: String, pending: JsonObject) = false
}
