package me.lvca.alba.protocol.photos

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.LivePhotoAttachRefusedException
import me.lvca.alba.engine.model.LivePhotoVideo
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.account.PersistedCookie
import me.lvca.alba.protocol.network.AppleHttpRequest
import me.lvca.alba.protocol.network.AppleHttpResponse
import me.lvca.alba.protocol.network.AppleHttpTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.URI

/**
 * The Live Photo attach, against a fake CloudKit: the exact `assets/upload` tokens, one POST of the video per resource,
 * and one atomic `records/modify` with the fields the recipe proven on a live account sets, in nanoseconds and with
 * `assetSubtypeV2` 2. A crash at any step resumes from the journal without sending anything twice.
 */
class LivePhotoAttachTest {
    private val video = LivePhotoVideo(
        bytes = ByteArray(64) { it.toByte() }, sha256Hex = "video-sha", durationNanos = 2_966_000_000L,
        stillTimeNanos = 1_483_000_000L, width = 1080, height = 1440, contentIdentifier = "6C4E5D2A-0D8B-4C3A-9A0E-2F1B3C4D5E6F",
    )

    @Test
    fun `the attach sends the proven asset upload and one atomic modify`() = runBlocking {
        val cloud = FakeCloudKit()
        val result = protocol(cloud, MemoryJournal()).attachLivePhotoVideo(binding(), "part:0", "master-1", "asset-1", video)

        val upload = cloud.bodies("assets/upload").single()
        assertEquals(buildJsonObject { put("zoneName", "PrimarySync") }, upload["zoneID"])
        assertEquals(
            listOf("resOriginalVidComplRes", "resVidMedRes", "resVidSmallRes").map { field ->
                buildJsonObject { put("recordType", "CPLMaster"); put("fieldName", field); put("recordName", "master-1") }
            },
            upload["tokens"]!!.jsonArray.toList(),
        )
        assertEquals("One POST of the whole video per resource", 3, cloud.posts.size)
        cloud.posts.forEach { (url, bytes) ->
            assertTrue(url.startsWith("https://cvws.icloud-content.com/"))
            assertArrayEquals(video.bytes, bytes)
        }

        val modify = cloud.bodies("records/modify").single()
        assertEquals(JsonPrimitive(true), modify["atomic"])
        val operations = modify["operations"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("update", "update"), operations.map { it["operationType"]!!.jsonPrimitive.content })
        val master = operations[0]["record"]!!.jsonObject
        assertEquals("master-1", master["recordName"]!!.jsonPrimitive.content)
        assertEquals("CPLMaster", master["recordType"]!!.jsonPrimitive.content)
        assertEquals("master-tag", master["recordChangeTag"]!!.jsonPrimitive.content)
        val masterFields = master["fields"]!!.jsonObject
        listOf("resOriginalVidCompl" to 0, "resVidMed" to 1, "resVidSmall" to 2).forEach { (prefix, index) ->
            val res = masterFields["${prefix}Res"]!!.jsonObject
            assertEquals("ASSETID", res["type"]!!.jsonPrimitive.content)
            val receipt = res["value"]!!.jsonObject
            assertEquals(setOf("fileChecksum", "size", "wrappingKey", "referenceChecksum", "receipt"), receipt.keys)
            assertEquals("sum-$index", receipt["fileChecksum"]!!.jsonPrimitive.content)
            assertEquals(64L, receipt["size"]!!.jsonPrimitive.long)
            assertEquals(field("STRING", "com.apple.quicktime-movie"), masterFields["${prefix}FileType"])
            assertEquals("The fingerprint is that resource's own checksum", field("STRING", "sum-$index"), masterFields["${prefix}Fingerprint"])
            assertEquals(field("INT64", 64), masterFields["${prefix}FileSize"])
            assertEquals(field("INT64", 1080), masterFields["${prefix}Width"])
            assertEquals(field("INT64", 1440), masterFields["${prefix}Height"])
        }
        assertEquals(18, masterFields.size)

        val asset = operations[1]["record"]!!.jsonObject
        assertEquals("asset-1", asset["recordName"]!!.jsonPrimitive.content)
        assertEquals("asset-tag", asset["recordChangeTag"]!!.jsonPrimitive.content)
        val assetFields = asset["fields"]!!.jsonObject
        assertEquals(field("INT64", 2_966_000_000L), assetFields["vidComplDurValue"])
        assertEquals(field("INT64", 1_000_000_000L), assetFields["vidComplDurScale"])
        assertEquals(field("INT64", 1_483_000_000L), assetFields["vidComplDispValue"])
        assertEquals(field("INT64", 1_000_000_000L), assetFields["vidComplDispScale"])
        assertEquals(field("INT64", 0), assetFields["vidComplVisibilityState"])
        assertEquals(field("INT64", 2), assetFields["assetSubtypeV2"])
        assertEquals(field("STRING", video.contentIdentifier), assetFields["mediaGroupId"])

        assertEquals("sum-0", result.receipt.fileChecksum)
        assertEquals(64L, result.receipt.byteCount)
        assertTrue(result.mediaGroupIdWritten)
        assertEquals("sum-0", protocol(cloud, MemoryJournal()).livePhotoVideoResource(binding(), "master-1")?.fileChecksum)
    }

    @Test
    fun `a crash after the modify reached iCloud resumes without sending anything again`() = runBlocking {
        val cloud = FakeCloudKit()
        val journal = MemoryJournal(crashOnSave = LivePhotoAttach.MODIFIED)
        assertTrue(runCatching { protocol(cloud, journal).attachLivePhotoVideo(binding(), "part:0", "master-1", "asset-1", video) }.isFailure)
        assertEquals(LivePhotoAttach.MODIFYING, journal.checkpoint?.phase)

        val result = protocol(cloud, journal).attachLivePhotoVideo(binding(), "part:0", "master-1", "asset-1", video)

        assertEquals("sum-0", result.receipt.fileChecksum)
        assertEquals("The video was sent once per resource", 3, cloud.posts.size)
        assertEquals("The master already holds the video, so the modify is not sent again", 1, cloud.bodies("records/modify").size)
        assertEquals(LivePhotoAttach.MODIFIED, journal.checkpoint?.phase)
    }

    @Test
    fun `a crash after the receipts were saved sends the modify without uploading again`() = runBlocking {
        val cloud = FakeCloudKit()
        val journal = MemoryJournal(crashOnSave = LivePhotoAttach.MODIFYING)
        assertTrue(runCatching { protocol(cloud, journal).attachLivePhotoVideo(binding(), "part:0", "master-1", "asset-1", video) }.isFailure)
        assertEquals(LivePhotoAttach.RECEIPTS_SAVED, journal.checkpoint?.phase)
        assertEquals(0, cloud.bodies("records/modify").size)

        protocol(cloud, journal).attachLivePhotoVideo(binding(), "part:0", "master-1", "asset-1", video)

        assertEquals(1, cloud.bodies("assets/upload").size)
        assertEquals(3, cloud.posts.size)
        assertEquals(1, cloud.bodies("records/modify").size)
    }

    @Test
    fun `receipts older than their lifetime are uploaded again before the modify`() = runBlocking {
        val cloud = FakeCloudKit()
        var clock = 1_000_000L
        val journal = MemoryJournal(crashOnSave = LivePhotoAttach.MODIFYING)
        assertTrue(runCatching { protocol(cloud, journal) { clock }.attachLivePhotoVideo(binding(), "part:0", "master-1", "asset-1", video) }.isFailure)
        clock += LivePhotoAttach.RECEIPT_LIFETIME_MILLIS

        protocol(cloud, journal) { clock }.attachLivePhotoVideo(binding(), "part:0", "master-1", "asset-1", video)

        assertEquals(2, cloud.bodies("assets/upload").size)
        assertEquals(6, cloud.posts.size)
        assertEquals(1, cloud.bodies("records/modify").size)
    }

    @Test
    fun `when CloudKit will not take mediaGroupId the attach goes on without it and says so`() = runBlocking {
        val cloud = FakeCloudKit(refuseMediaGroup = true)
        val result = protocol(cloud, MemoryJournal()).attachLivePhotoVideo(binding(), "part:0", "master-1", "asset-1", video)

        val modifies = cloud.bodies("records/modify")
        assertEquals(2, modifies.size)
        assertTrue(assetFields(modifies[0]).containsKey("mediaGroupId"))
        assertFalse(assetFields(modifies[1]).containsKey("mediaGroupId"))
        assertEquals(field("INT64", 2), assetFields(modifies[1])["assetSubtypeV2"])
        assertFalse(result.mediaGroupIdWritten)
    }

    @Test
    fun `a still that already has another video is never changed`() = runBlocking {
        val cloud = FakeCloudKit(existingVideoChecksum = "someone-else")
        val error = runCatching {
            protocol(cloud, MemoryJournal()).attachLivePhotoVideo(binding(), "part:0", "master-1", "asset-1", video)
        }.exceptionOrNull()

        assertTrue(error is LivePhotoAttachRefusedException)
        assertEquals("ALREADY_HAS_VIDEO", (error as LivePhotoAttachRefusedException).code)
        assertEquals(0, cloud.posts.size)
        assertEquals(0, cloud.bodies("records/modify").size)
    }

    @Test
    fun `a record error iCloud would repeat is refused with its code`() = runBlocking {
        val cloud = FakeCloudKit(modifyError = "PERMISSION_FAILURE")
        val error = runCatching {
            protocol(cloud, MemoryJournal()).attachLivePhotoVideo(binding(), "part:0", "master-1", "asset-1", video)
        }.exceptionOrNull() as? LivePhotoAttachRefusedException

        assertEquals("PERMISSION_FAILURE", error?.code)
        assertNull(protocol(cloud, MemoryJournal()).livePhotoVideoResource(binding(), "master-1"))
    }

    private fun assetFields(modify: JsonObject) =
        modify["operations"]!!.jsonArray[1].jsonObject["record"]!!.jsonObject["fields"]!!.jsonObject

    private fun field(type: String, value: Any) = buildJsonObject {
        when (value) {
            is String -> put("value", value)
            is Int -> put("value", value)
            is Long -> put("value", value)
        }
        put("type", type)
    }

    private fun protocol(cloud: FakeCloudKit, journal: UploadJournal, now: () -> Long = { 1_000_000L }) =
        LiveICloudPhotoProtocol(cloud, { SESSION }, uploadJournal = journal, clock = now)

    private fun binding() = AccountBinding(ICloudAccountIdentity.accountId(SESSION), 1, 1)

    private class MemoryJournal(private val crashOnSave: String? = null) : UploadJournal {
        var checkpoint: UploadCheckpoint? = null
        private var crashed = false

        override suspend fun load(binding: AccountBinding, attempt: String) = checkpoint

        override suspend fun save(binding: AccountBinding, attempt: String, checkpoint: UploadCheckpoint) {
            if (checkpoint.phase == crashOnSave && !crashed) {
                crashed = true
                error("process died")
            }
            this.checkpoint = checkpoint
        }
    }

    /** CloudKit's library database and content store for one still, as the attach sees them. */
    private class FakeCloudKit(
        val refuseMediaGroup: Boolean = false,
        existingVideoChecksum: String? = null,
        val modifyError: String? = null,
    ) : AppleHttpTransport {
        val requests = mutableListOf<AppleHttpRequest>()
        val posts = mutableListOf<Pair<String, ByteArray>>()
        private var videoChecksum: String? = existingVideoChecksum

        fun bodies(path: String) = requests.filter { URI(it.url).path.endsWith(path) }.map { Json.parseToJsonElement(it.body!!).jsonObject }

        override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse {
            requests += request
            val path = URI(request.url).path
            assertTrue(path.startsWith("/database/1/com.apple.photos.cloud/production/private/"))
            val body = Json.parseToJsonElement(request.body!!).jsonObject
            return when {
                path.endsWith("records/lookup") -> ok(lookup())
                path.endsWith("assets/upload") -> ok(buildJsonObject {
                    put("tokens", JsonArray(body["tokens"]!!.jsonArray.mapIndexed { index, token ->
                        buildJsonObject {
                            put("recordName", token.jsonObject["recordName"]!!)
                            put("fieldName", token.jsonObject["fieldName"]!!)
                            put("url", "https://cvws.icloud-content.com/upload/$index")
                        }
                    }))
                }.toString())
                path.endsWith("records/modify") -> {
                    assertTrue("A write is never retried by the transport", request.oneShot)
                    val operations = body["operations"]!!.jsonArray
                    val assetFields = operations[1].jsonObject["record"]!!.jsonObject["fields"]!!.jsonObject
                    val code = when {
                        refuseMediaGroup && "mediaGroupId" in assetFields -> "BAD_REQUEST"
                        modifyError != null -> modifyError
                        else -> null
                    }
                    if (code != null) {
                        ok("""{"records":[{"recordName":"master-1","serverErrorCode":"ATOMIC_ERROR"},{"recordName":"asset-1","serverErrorCode":"$code"}]}""")
                    } else {
                        val res = operations[0].jsonObject["record"]!!.jsonObject["fields"]!!.jsonObject["resOriginalVidComplRes"]!!.jsonObject
                        videoChecksum = res["value"]!!.jsonObject["fileChecksum"]!!.jsonPrimitive.content
                        ok("""{"records":[{"recordName":"master-1","recordType":"CPLMaster"},{"recordName":"asset-1","recordType":"CPLAsset"}]}""")
                    }
                }
                else -> error("Unexpected request $path")
            }
        }

        private fun lookup(): String {
            val video = videoChecksum?.let {
                ""","resOriginalVidComplRes":{"value":{"fileChecksum":"$it","size":64,"downloadURL":"https://cvws.icloud-content.com/video"}}"""
            }.orEmpty()
            return """
                {"records":[
                  {"recordType":"CPLMaster","recordName":"master-1","recordChangeTag":"master-tag",
                   "fields":{"resOriginalRes":{"value":{"fileChecksum":"still","size":10}}$video}},
                  {"recordType":"CPLAsset","recordName":"asset-1","recordChangeTag":"asset-tag",
                   "fields":{"masterRef":{"value":{"recordName":"master-1"}}}}
                ]}
            """.trimIndent()
        }

        override suspend fun executeRawUpload(
            url: String,
            headers: Map<String, String>,
            contentLength: Long,
            openBodyOnce: () -> InputStream,
        ): AppleHttpResponse {
            val bytes = openBodyOnce().use { it.readBytes() }
            assertEquals(contentLength, bytes.size.toLong())
            val index = url.substringAfterLast('/')
            posts += url to bytes
            return ok(
                """{"singleFile":{"size":${bytes.size},"fileChecksum":"sum-$index","wrappingKey":"key-$index",""" +
                    """"referenceChecksum":"ref-$index","receipt":"receipt-$index"}}""",
            )
        }

        override suspend fun stream(url: String, output: OutputStream) = error("Not used")
        override fun snapshotCookies(): List<PersistedCookie> = emptyList()
        override fun restore(snapshot: AppleSessionSnapshot) = Unit
        override fun clear() = Unit

        private fun ok(body: String) = AppleHttpResponse(200, emptyMap(), body)
    }

    private companion object {
        val SESSION = AppleSessionSnapshot(
            accountName = "test@example.com", clientId = "client", accountCountryCode = "IT", sessionId = "session",
            sessionToken = "token", trustToken = "trust", dsid = "12345",
            webservices = mapOf("ckdatabasews" to "https://p01-ckdatabasews.icloud.com"), cookies = emptyList(),
        )
    }
}
