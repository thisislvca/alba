package dev.mela.protocol.photos

import dev.mela.protocol.account.*
import dev.mela.protocol.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream

class TrashAndMembershipTest {
    private val session = AppleSessionSnapshot("test@example.invalid", "client", "IT", "session", "token", null,
        "123", mapOf("ckdatabasews" to "https://p01-ckdatabasews.icloud.com"), emptyList())
    private fun source(transport: Transport) = LiveICloudCatalogSource(transport, { session }, isAuthorized = { true })
    private fun id(source: LiveICloudCatalogSource) = "icloud:${source.accountLabel}:photo"

    @Test fun trashAndRestoreUseFreshAssetVersionsAndPreserveOriginals() = runBlocking {
        val transport = Transport(); val source = source(transport)
        source.setCloudTrashed(listOf(id(source)), true)
        assertTrue(transport.trashed)
        source.setCloudTrashed(listOf(id(source)), false)
        assertFalse(transport.trashed)
        assertEquals(2, transport.writes.size)
        val restore = transport.writes.last()
        assertTrue(restore.oneShot)
        val record = Json.parseToJsonElement(restore.body!!).jsonObject["operations"]!!.jsonArray.single().jsonObject["record"]!!.jsonObject
        assertEquals("CPLAsset", record["recordType"]!!.jsonPrimitive.content)
        assertEquals("v2", record["recordChangeTag"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, record["fields"]!!.jsonObject["isDeleted"]!!.jsonObject["value"])
        assertFalse(restore.body!!.contains("CPLMaster"))
        assertFalse(restore.body!!.contains("force"))
    }

    @Test fun droppedResponseIsNotRetriedAndAUserRetryReadsTheNewStateFirst() = runBlocking {
        val transport = Transport(); val source = source(transport)
        transport.disconnect = true
        assertTrue(runCatching { source.setCloudTrashed(listOf(id(source)), true) }.isFailure)
        assertEquals(1, transport.writes.size)
        transport.disconnect = false
        source.setCloudTrashed(listOf(id(source)), true)
        assertEquals("Already applied state must not be written again", 1, transport.writes.size)
    }

    @Test fun rejectedOrUnconfirmedWritesAndExpungedPhotosDoNotSucceed() = runBlocking {
        val transport = Transport(); val source = source(transport)
        transport.reject = true
        assertTrue(runCatching { source.setCloudTrashed(listOf(id(source)), true) }.isFailure)
        transport.reject = false; transport.ignoreWrite = true
        assertTrue(runCatching { source.setCloudTrashed(listOf(id(source)), true) }.isFailure)
        transport.expunged = true
        val count = transport.writes.size
        assertTrue(runCatching { source.setCloudTrashed(listOf(id(source)), false) }.isFailure)
        assertEquals(count, transport.writes.size)
        assertTrue(runCatching { source.setCloudTrashed(listOf("icloud:another:photo"), true) }.isFailure)
    }

    @Test fun albumRemovalDeletesOnlyFreshMatchingRelationsAndRequiresConfirmation() = runBlocking {
        val transport = Transport(); val source = source(transport)
        source.removeFromAlbum("album", listOf(id(source)))
        val write = transport.writes.single()
        assertTrue(write.oneShot)
        assertTrue(write.body!!.contains("CPLContainerRelation"))
        assertFalse(write.body!!.contains("CPLAsset\""))
        assertTrue(write.body!!.contains("actual-relation-name"))
        transport.relationDeleted = false; transport.wrongAlbum = true
        assertTrue(runCatching { source.removeFromAlbum("album", listOf(id(source))) }.isFailure)
        assertEquals(1, transport.writes.size)
    }

    private class Transport : AppleHttpTransport {
        override val sessionHeaders = AppleSessionHeaders()
        val writes = mutableListOf<AppleHttpRequest>()
        var trashed = false; var expunged = false; var version = 1
        var disconnect = false; var reject = false; var ignoreWrite = false
        var relationDeleted = false; var wrongAlbum = false
        private fun record(name: String) = buildJsonObject {
            put("recordName", name)
            put("recordType", when(name) { "album" -> "CPLAlbum"; "actual-relation-name" -> "CPLContainerRelation"; else -> "CPLAsset" })
            put("recordChangeTag", "v$version")
            putJsonObject("fields") {
                fun field(key: String, value: JsonElement) { putJsonObject(key) { put("value", value) } }
                field("albumType", JsonPrimitive(0)); field("itemId", JsonPrimitive("photo"))
                field("containerId", JsonPrimitive(if (wrongAlbum) "other" else "album"))
                field("isExpunged", JsonPrimitive(if (expunged) 1 else 0))
                field("isDeleted", if (trashed && name == "photo") JsonPrimitive(1) else JsonNull)
            }
        }
        override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse {
            val body = Json.parseToJsonElement(request.body!!).jsonObject
            val records = when {
                request.url.contains("records/query") -> if (relationDeleted) emptyList() else listOf(record("actual-relation-name"))
                request.url.contains("records/lookup") -> body["records"]!!.jsonArray.map { record(it.jsonObject["recordName"]!!.jsonPrimitive.content) }
                request.url.contains("records/modify") -> {
                    writes += request
                    body["operations"]!!.jsonArray.map { op ->
                        val operation = op.jsonObject; val record = operation["record"]!!.jsonObject
                        val name = record["recordName"]!!.jsonPrimitive.content
                        if (reject) buildJsonObject { put("recordName", name); put("serverErrorCode", "CONFLICT") }
                        else if (operation["operationType"]!!.jsonPrimitive.content == "delete") {
                            relationDeleted = true
                            buildJsonObject { put("recordName", name); put("deleted", true) }
                        } else {
                            if (!ignoreWrite) trashed = record["fields"]!!.jsonObject["isDeleted"]!!.jsonObject["value"] != JsonNull
                            version++
                            if (disconnect) throw java.io.IOException("Lost after apply")
                            record(name)
                        }
                    }
                }
                else -> emptyList()
            }
            return AppleHttpResponse(200, emptyMap(), buildJsonObject { put("records", JsonArray(records)) }.toString())
        }
        override suspend fun stream(url: String, output: OutputStream) = Unit
        override fun restore(snapshot: AppleSessionSnapshot) = Unit
        override fun clear() = Unit
        override fun snapshotCookies() = emptyList<PersistedCookie>()
    }
}
