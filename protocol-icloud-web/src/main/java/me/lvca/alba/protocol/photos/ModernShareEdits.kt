package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.*
import me.lvca.alba.engine.model.SharedAction
import me.lvca.alba.engine.model.SharedCommand
import me.lvca.alba.protocol.array
import me.lvca.alba.protocol.obj
import me.lvca.alba.protocol.text
import java.util.UUID

/**
 * The `cloudkit.share` properties a sharing command rewrites. Each value starts from the share as it
 * was read, so people, links and blocks the command does not touch are preserved.
 */
internal object ModernShareEdits {
    fun oneTimeLinks(share: JsonObject): JsonArray = share.obj("oneTimeStableUrlInfo")?.array("oneTimeLinks") ?: JsonArray(emptyList())

    /** The properties to send for [command]; empty when the command changes only fields or the zone. */
    fun properties(share: JsonObject, command: SharedCommand, pending: JsonObject): JsonObject {
        val participants = share.array("participants").map { it.jsonObject }
        return buildJsonObject {
            when (command.action) {
                SharedAction.CREATE_INVITE_LINK -> {
                    require(share.text("publicPermission") != "READ_WRITE") { "Use the public link for a public album." }
                    putJsonObject("oneTimeStableUrlInfo") { put("oneTimeLinks", JsonArray(oneTimeLinks(share) + buildJsonObject {
                        putJsonArray("participantId") { add(pending.getValue("recordName")) }
                    })) }
                    put("participants", JsonArray(participants +
                        SharedParticipants.invitedContributor(pending.getValue("recordName"), lookupInfo = null, approvedRequester = false)))
                }
                SharedAction.REVOKE_INVITE_LINKS -> {
                    putJsonObject("oneTimeStableUrlInfo") { put("oneTimeLinks", JsonArray(emptyList())) }
                    // One-time link participants have no identity until someone accepts the link.
                    put("participants", JsonArray(participants.filter { person ->
                        val identity = person.obj("userIdentity").orEmpty()
                        val contact = (identity["lookupInfo"] as? JsonObject).orEmpty()
                        person.text("type") == "OWNER" || identity["userRecordName"] != null || contact["emailAddress"] != null || contact["phoneNumber"] != null
                    }))
                }
                SharedAction.UNBLOCK -> {
                    val blocked = share.array("blocked")
                    fun key(entry: JsonElement) = SharedParticipants.identityKey(entry.jsonObject["blockedInformation"]!!.jsonObject)
                    require(blocked.any { key(it) == command.subject })
                    put("blocked", JsonArray(blocked.filterNot { key(it) == command.subject }))
                }
                SharedAction.BLOCK -> {
                    // Like declining a request: the person leaves and their identity joins the blocked list.
                    val target = participants.first { it.text("participantId") == command.subject }
                    require(target.text("type") != "OWNER")
                    val identity = requireNotNull(target.obj("userIdentity")) { "This participant cannot be blocked." }
                    SharedParticipants.identityKey(identity)
                    put("participants", JsonArray(participants.filter { it != target }))
                    put("blocked", JsonArray(share.array("blocked") + buildJsonObject { put("blockedInformation", identity) }))
                }
                SharedAction.APPROVE_REQUEST, SharedAction.DENY_REQUEST -> {
                    val requests = share.array("requesters").map { it.jsonObject }
                    val target = requests.first { request ->
                        val identity = request["requesterInformation"]!!.jsonObject
                        (identity.text("userRecordName") ?: identity.obj("lookupInfo")?.text("emailAddress")) == command.subject
                    }
                    val identity = target["requesterInformation"]!!.jsonObject
                    if (command.action == SharedAction.DENY_REQUEST) {
                        put("requesters", JsonArray(requests - target))
                        put("blocked", JsonArray(share.array("blocked") + buildJsonObject { put("blockedInformation", identity) }))
                    } else {
                        put("participants", JsonArray(participants + SharedParticipants.invitedContributor(pending.getValue("recordName"), SharedParticipants.lookupInfo(identity), approvedRequester = true)))
                    }
                }
                SharedAction.INVITE -> put("participants", JsonArray(participants + SharedParticipants.invitedContributor(pending.getValue("recordName"), buildJsonObject { put("emailAddress", command.value) }, approvedRequester = true)))
                SharedAction.REMOVE_PARTICIPANT, SharedAction.ROLE -> {
                    val target = participants.first { it.text("participantId") == command.subject }
                    require(target.text("type") != "OWNER")
                    if (command.action == SharedAction.REMOVE_PARTICIPANT) {
                        put("participants", JsonArray(participants.filter { it != target }))
                    } else {
                        require(command.value in setOf("contributor", "commenter", "manager"))
                        put("participants", JsonArray(participants.map { person -> if (person != target) person else JsonObject(person + mapOf(
                            // In a public album the participant takes the journaled ID, which reconciliation also accepts.
                            "participantId" to (if (share.text("publicPermission") == "READ_WRITE") pending.getValue("recordName") else person.getValue("participantId")),
                            "type" to JsonPrimitive(if (command.value == "manager") "ADMINISTRATOR" else "USER"),
                            "permission" to JsonPrimitive("READ_WRITE"), "customRole" to JsonPrimitive(if (command.value == "manager") "" else command.value))) }))
                    }
                }
                SharedAction.PUBLIC_ACCESS -> {
                    put("publicPermission", if (command.enabled) "READ_WRITE" else "NONE")
                    put("publicCustomAccess", if (command.enabled) "contributor" else "")
                    if (command.enabled) put("anonymousPublicAccess", true)
                    val requests = share.array("requesters").map { it.jsonObject }
                    if (command.enabled && requests.isNotEmpty()) {
                        // A public album admits everyone who was waiting.
                        put("requesters", JsonArray(emptyList()))
                        put("participants", JsonArray(participants + requests.map { request ->
                            SharedParticipants.invitedContributor(JsonPrimitive(UUID.randomUUID().toString()),
                                SharedParticipants.lookupInfo(request.getValue("requesterInformation").jsonObject), approvedRequester = true)
                        }))
                    } else if (!command.enabled && participants.any { it.text("type") == "PUBLIC_USER" }) {
                        // People who joined through the public link stay, under a new identity.
                        put("participants", JsonArray(participants.map { person -> if (person.text("type") != "PUBLIC_USER") person else JsonObject(person + mapOf(
                            "participantId" to JsonPrimitive(UUID.randomUUID().toString()),
                            "type" to JsonPrimitive("USER"), "customRole" to JsonPrimitive("contributor"))) }))
                    }
                }
                SharedAction.ACCESS_REQUESTS -> put("denyAccessRequests", !command.enabled)
                else -> Unit
            }
        }
    }
}
