package me.lvca.alba.protocol.photos

import me.lvca.alba.engine.model.SharedAlbumRole
import me.lvca.alba.protocol.obj
import me.lvca.alba.protocol.text
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Reads and writes the participant entries of CloudKit shares and legacy sharing rows. */
internal object SharedParticipants {
    fun role(participant: JsonObject): SharedAlbumRole = when {
        participant.text("type") == "OWNER" -> SharedAlbumRole.OWNER
        participant.text("type") == "ADMINISTRATOR" -> SharedAlbumRole.MANAGER
        participant.text("permission") == "READ_WRITE" && participant.text("customRole") == "commenter" -> SharedAlbumRole.COMMENTER
        participant.text("permission") == "READ_WRITE" -> SharedAlbumRole.CONTRIBUTOR
        else -> SharedAlbumRole.VIEWER
    }

    /** The key an access request or block is addressed by: the Apple user or, before sign-up, the email. */
    fun identityKey(identity: JsonObject): String = identity.text("userRecordName")
        ?: identity.obj("lookupInfo")?.text("emailAddress") ?: error("Missing participant identity")

    /** A display name for a CloudKit participant or a legacy sharing row. */
    fun name(participant: JsonObject): String {
        val identity = participant.obj("userIdentity")
        val names = identity?.obj("nameComponents")
        return listOfNotNull(names?.text("givenName"), names?.text("familyName")).joinToString(" ").ifBlank {
            identity?.obj("lookupInfo")?.text("emailAddress") ?: participant.text("fullname") ?: participant.text("email") ?: "—"
        }
    }

    fun nameOfIdentity(identity: JsonObject): String = name(buildJsonObject { put("userIdentity", identity) })

    /** The signed-in person's user record in [share], when CloudKit reports it. */
    fun currentUser(share: JsonObject): String? =
        share.obj("currentUserParticipant")?.obj("userIdentity")?.text("userRecordName")

    /** How CloudKit should find the person behind a requester or blocked identity. */
    fun lookupInfo(identity: JsonObject): JsonObject = buildJsonObject {
        val userName = identity.text("userRecordName")
        if (userName != null) {
            put("userRecordName", userName)
        } else {
            put("emailAddress", identity.getValue("lookupInfo").jsonObject.getValue("emailAddress"))
        }
    }

    /**
     * A new contributor entry. CloudKit fills in the identity when the person accepts. [lookupInfo] is
     * absent for a one-time link, which anyone holding it may accept.
     */
    fun invitedContributor(participantId: JsonElement, lookupInfo: JsonObject?, approvedRequester: Boolean) = buildJsonObject {
        put("participantId", participantId)
        put("type", "USER")
        put("permission", "READ_WRITE")
        put("customRole", "contributor")
        put("acceptanceStatus", "INVITED")
        put("orgUser", false)
        if (approvedRequester) put("isApprovedRequester", true)
        putJsonObject("userIdentity") { lookupInfo?.let { put("lookupInfo", it) } }
    }
}
