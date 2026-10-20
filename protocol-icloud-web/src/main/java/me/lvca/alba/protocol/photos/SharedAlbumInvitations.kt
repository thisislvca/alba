package me.lvca.alba.protocol.photos

import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedAlbumLinks
import me.lvca.alba.engine.model.SharedInvitation
import me.lvca.alba.protocol.field
import me.lvca.alba.protocol.obj
import me.lvca.alba.protocol.text
import java.util.UUID
import kotlinx.serialization.json.*

/**
 * Invitations to join shared albums: pending legacy invitations, which can be accepted or declined,
 * and modern album links, which can only be accepted here.
 */
internal class SharedAlbumInvitations(
    private val connection: SharedAlbumSession,
    private val ids: SharedAlbumIds,
    private val journal: SharedAlbumJournal,
    private val authorized: () -> Boolean,
    private val discover: suspend () -> List<SharedAlbumRecord>,
    private val legacy: LegacySharedAlbums,
) {
    suspend fun pending(): List<SharedInvitation> {
        if (connection.bound().webservices["sharedstreams"] == null) return emptyList()
        val root = connection.request(legacy.accountUrl("webgetpendingalbums"), buildJsonObject {})
        return requireNotNull(root["albums"] as? JsonArray).map { it.jsonObject }.map { row ->
            check(row["httpstatus"] == null) { "Could not load an invitation." }
            SharedInvitation(ids.invitation("legacy", requireNotNull(row.text("albumguid"))),
                row.obj("attributes")?.text("name") ?: "—", SharedAlbumGeneration.LEGACY, true)
        }
    }

    suspend fun resolve(url: String): SharedInvitation {
        val guid = requireNotNull(SharedAlbumLinks.token(url)) { "Paste an iCloud Photos shared-album link." }
        val share = resolveShare(guid)
        return SharedInvitation(ids.invitation("modern", guid), share.field("cloudkit.title")?.jsonPrimitive?.contentOrNull ?: "—",
            SharedAlbumGeneration.MODERN)
    }

    suspend fun respond(id: String, accept: Boolean, operationId: String) {
        UUID.fromString(operationId); check(authorized())
        val (kind, value) = ids.invitationValue(id)
        require(kind == "legacy" || accept) { "New-album invitations are declined in iCloud." }
        val key = SharedAlbumJournal.key("shared-invitation", "$id|$accept")
        val old = journal.load(key)
        suspend fun confirmed(): Boolean = if (kind == "modern") {
            resolveShare(value).obj("currentUserParticipant")?.text("acceptanceStatus") == "ACCEPTED"
        } else {
            val joined = discover().any { it.isLegacy && it.zone == value }
            if (accept) joined else !joined && pending().none { it.id == id }
        }
        if (kind == "modern" && old == null && confirmed()) {
            journal.save(key, "DONE", buildJsonObject {})
            return
        }
        if (old != null) {
            if (confirmed()) {
                journal.save(key, "DONE", buildJsonObject {})
                return
            }
            check(old.phase != "SENDING") { "Invitation response is uncertain. Check iCloud before retrying." }
        }
        if (kind == "legacy") require(pending().any { it.id == id }) else resolveShare(value)
        journal.save(key, "SENDING", buildJsonObject { put("operationId", operationId) })
        if (kind == "legacy") {
            connection.request(legacy.accountUrl(if (accept) "subscribe" else "unsubscribe"), buildJsonObject { put("albumguid", value) }, true)
        } else {
            val response = connection.ck("public", "records/accept", shortGuids(value), true)
            val result = requireNotNull(response["results"] as? JsonArray).single().jsonObject
            check(result["serverErrorCode"] == null && result["share"] != null) { "Apple did not confirm joining the album." }
        }
        check(confirmed()) { "Refresh to check whether the invitation was accepted." }
        journal.save(key, "DONE", buildJsonObject {})
    }

    /** The share behind a modern album link; anything else is rejected before it is joined. */
    private suspend fun resolveShare(guid: String): JsonObject {
        require(guid.matches(Regex("[A-Za-z0-9_-]{1,4096}")))
        val response = connection.ck("public", "records/resolve", shortGuids(guid))
        val item = requireNotNull(response["results"] as? JsonArray).single().jsonObject
        check(item["serverErrorCode"] == null) { "The invitation is unavailable." }
        val share = requireNotNull(item["share"] as? JsonObject) { "Open this invitation in iCloud to finish signing in." }
        val zone = (item["zoneID"] ?: share["zoneID"])?.jsonObject
        require(zone?.text("zoneName")?.startsWith(ModernSharedAlbums.ZONE_PREFIX) == true && share.text("recordType") == "cloudkit.share") {
            "This link is not a new shared album."
        }
        return share
    }

    private fun shortGuids(guid: String) = buildJsonObject { putJsonArray("shortGUIDs") { add(buildJsonObject { put("value", guid) }) } }
}
