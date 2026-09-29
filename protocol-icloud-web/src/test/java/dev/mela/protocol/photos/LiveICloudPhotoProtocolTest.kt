package dev.mela.protocol.photos

import dev.mela.engine.model.AccountBinding
import dev.mela.engine.model.OneShotUploadSource
import dev.mela.engine.model.UploadAcceptance
import dev.mela.engine.model.UploadProcessingStatus
import dev.mela.protocol.auth.AppleProtocolException
import dev.mela.protocol.auth.AppleProtocolError
import dev.mela.protocol.account.AppleSessionSnapshot
import dev.mela.protocol.account.PersistedCookie
import dev.mela.protocol.network.AppleHttpRequest
import dev.mela.protocol.network.AppleHttpResponse
import dev.mela.protocol.network.AppleHttpTransport
import dev.mela.protocol.network.AppleSessionHeaders
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveICloudPhotoProtocolTest {
    @Test
    fun `read rejection expires the exact photo session while server failure does not`() = runBlocking {
        for (code in listOf(401, 500)) {
            var rejected: AppleSessionSnapshot? = null
            val transport = ScriptedTransport(processingHttpCode = code)
            val protocol = LiveICloudPhotoProtocol(
                transport,
                { SESSION },
                onSessionRejected = { rejected = it },
            )
            val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)

            val error = runCatching {
                protocol.uploadProcessingStatus(binding, acceptance())
            }.exceptionOrNull()

            assertTrue(error is AppleProtocolException)
            assertEquals(if (code == 401) SESSION else null, rejected)
        }
    }

    @Test
    fun `upload rejection expires the exact prepared session before bytes are sent`() = runBlocking {
        var rejected: AppleSessionSnapshot? = null
        val transport = ScriptedTransport(reservationHttpCode = 403)
        val protocol = LiveICloudPhotoProtocol(
            transport,
            { SESSION },
            onSessionRejected = { rejected = it },
        )
        val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)

        val error = runCatching {
            protocol.prepareOriginal(binding, "photo.jpg", CountingSource(JPEG)).startOnce("expired")
        }.exceptionOrNull()

        assertTrue(error is AppleProtocolException)
        assertEquals(AppleProtocolError.SESSION_EXPIRED, (error as AppleProtocolException).error)
        assertEquals(SESSION, rejected)
        assertEquals(0, transport.rawUploadCalls)
    }

    @Test
    fun `authorization loss between phases retains safe journal and resumes without duplicate writes`() = runBlocking {
        for (stopAfter in listOf("RESERVING", "RESERVED", "RECEIPT_SAVED")) {
            var authorized = true
            var checkpoint: UploadCheckpoint? = null
            var stopped = false
            val journal = object : UploadJournal {
                override suspend fun load(binding: AccountBinding, attempt: String) = checkpoint
                override suspend fun save(binding: AccountBinding, attempt: String, value: UploadCheckpoint) {
                    checkpoint = value
                    if (value.phase == stopAfter && !stopped) {
                        stopped = true
                        authorized = false
                    }
                }
            }
            val transport = ScriptedTransport()
            val protocol = LiveICloudPhotoProtocol(transport, { SESSION }, uploadJournal = journal,
                isAuthorized = { authorized })
            val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
            val source = CountingSource(JPEG)
            val prepared = protocol.prepareOriginal(binding, "photo.jpg", source)
            assertTrue(runCatching { prepared.startOnce("auth-pause") }.isFailure)
            assertEquals(stopAfter, checkpoint?.phase)
            assertTrue(protocol.canResume(binding, "auth-pause"))
            assertEquals(if (stopAfter == "RECEIPT_SAVED") 1 else 0, transport.rawUploadCalls)
            assertEquals(if (stopAfter == "RESERVING") 0 else 1,
                transport.requests.count { it.url.contains("createUploadUrl") })
            assertTrue(transport.requests.none { it.url.contains("putAsset") })
            authorized = true
            val acceptance = protocol.prepareOriginal(binding, "photo.jpg", source).startOnce("auth-pause")
            assertEquals("asset-1", acceptance.assetRecordName)
            assertEquals(1, transport.rawUploadCalls)
            assertEquals(1, source.opens.get())
            assertEquals(1, transport.requests.count { it.url.contains("createUploadUrl") })
            assertEquals(1, transport.requests.count { it.url.contains("putAsset") })
        }
    }

    @Test
    fun `prepared upload refuses a replaced authentication session`() = runBlocking {
        var session = SESSION
        val transport = ScriptedTransport()
        val protocol = LiveICloudPhotoProtocol(transport, { session })
        val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
        val prepared = protocol.prepareOriginal(binding, "photo.jpg", CountingSource(JPEG))
        session = SESSION.copy(clientId = "replacement")
        assertTrue(runCatching { prepared.startOnce("stale-session") }.isFailure)
        assertTrue(transport.requests.isEmpty())
        assertEquals(0, transport.rawUploadCalls)
    }

    @Test
    fun `unverified session preserves destination identity but blocks remote work`() = runBlocking {
        val transport = ScriptedTransport()
        var authorized = true
        val protocol = LiveICloudPhotoProtocol(transport, { SESSION }, isAuthorized = { authorized })
        val destination = requireNotNull(protocol.currentDestination())
        val binding = AccountBinding(destination.accountId, 1, 1)
        authorized = false
        assertEquals(destination, protocol.currentDestination())
        assertTrue(runCatching { protocol.captureChangeToken(binding) }.isFailure)
        assertTrue(runCatching { protocol.prepareOriginal(binding, "photo.jpg", CountingSource(JPEG)) }.isFailure)
        assertTrue(transport.requests.isEmpty())
        assertEquals(0, transport.rawUploadCalls)
    }

    @Test
    fun `saved phases resume without replaying bytes or registration`() = runBlocking {
        for (phase in listOf("RESERVING", "RESERVED", "RECEIPT_SAVED", "ACCEPTED")) {
            val transport = ScriptedTransport()
            val journal = CrashJournal(phase)
            val protocol = LiveICloudPhotoProtocol(transport, { SESSION }, uploadJournal = journal)
            val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
            val source = CountingSource(JPEG)
            assertTrue(runCatching { protocol.prepareOriginal(binding, "photo.jpg", source).startOnce("resume") }.isFailure)
            assertTrue(protocol.canResume(binding, "resume"))
            val result = protocol.prepareOriginal(binding, "photo.jpg", source).startOnce("resume")
            assertEquals("asset-1", result.assetRecordName)
            assertEquals(1, transport.rawUploadCalls)
            assertEquals(1, source.opens.get())
            assertEquals(1, transport.requests.count { it.url.contains("putAsset") })
        }
    }

    @Test
    fun `inflight byte and registration phases cannot be resumed`() = runBlocking {
        for (phase in listOf("BYTES_SENDING", "REGISTERING")) {
            val transport = ScriptedTransport()
            val journal = CrashJournal(phase)
            val protocol = LiveICloudPhotoProtocol(transport, { SESSION }, uploadJournal = journal)
            val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
            val source = CountingSource(JPEG)
            assertTrue(runCatching { protocol.prepareOriginal(binding, "photo.jpg", source).startOnce("uncertain") }.isFailure)
            val calls = transport.requests.size
            assertTrue(!protocol.canResume(binding, "uncertain"))
            assertTrue(runCatching { protocol.prepareOriginal(binding, "photo.jpg", source).startOnce("uncertain") }.isFailure)
            assertEquals(calls, transport.requests.size)
            assertEquals(if (phase == "BYTES_SENDING") 0 else 1, transport.rawUploadCalls)
        }
    }

    private class CrashJournal(private val crashPhase: String) : UploadJournal {
        var checkpoint: UploadCheckpoint? = null
        var crashed = false
        override suspend fun load(binding: AccountBinding, attempt: String) = checkpoint
        override suspend fun save(binding: AccountBinding, attempt: String, checkpoint: UploadCheckpoint) {
            this.checkpoint = checkpoint
            if (checkpoint.phase == crashPhase && !crashed) { crashed = true; throw java.io.IOException("Simulated process interruption") }
        }
    }

    @Test
    fun `mismatched receipt never registers photo`() = runBlocking {
        val transport = ScriptedTransport(receiptSize = 7)
        val protocol = LiveICloudPhotoProtocol(transport, { SESSION })
        val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
        assertTrue(runCatching {
            protocol.prepareOriginal(binding, "photo.jpg", CountingSource(JPEG)).startOnce("mismatch")
        }.isFailure)
        assertEquals(1, transport.rawUploadCalls)
        assertTrue(transport.requests.none { it.url.contains("putAsset") })
    }

    @Test
    fun `cancelled prepared upload makes no requests`() = runBlocking {
        val transport = ScriptedTransport()
        val protocol = LiveICloudPhotoProtocol(transport, { SESSION })
        val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
        val source = CountingSource(JPEG)
        val prepared = protocol.prepareOriginal(binding, "photo.jpg", source)
        prepared.cancel()
        assertTrue(runCatching { prepared.startOnce("cancelled") }.isFailure)
        assertEquals(0, source.opens.get())
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `duplicate registration retains existing record identities`() = runBlocking {
        val transport = ScriptedTransport(registrationStatus = 409)
        val protocol = LiveICloudPhotoProtocol(transport, { SESSION })
        val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
        val accepted = protocol.prepareOriginal(binding, "duplicate.jpg", CountingSource(JPEG)).startOnce("duplicate")
        assertEquals(true, accepted.duplicateHint)
        assertEquals("master-1", accepted.masterRecordName)
        assertEquals("asset-1", accepted.assetRecordName)
    }

    @Test
    fun `lost registration response never replays stored bytes`() = runBlocking {
        val transport = ScriptedTransport(failRegistration = true)
        val protocol = LiveICloudPhotoProtocol(transport, { SESSION })
        val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
        val source = CountingSource(JPEG)
        val prepared = protocol.prepareOriginal(binding, "photo.jpg", source)
        assertTrue(runCatching { prepared.startOnce("lost") }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { prepared.startOnce("lost") }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, transport.rawUploadCalls)
        assertEquals(1, source.opens.get())
    }

    @Test
    fun `untrusted reservation cannot receive photo bytes`() = runBlocking {
        val transport = ScriptedTransport(uploadTarget = "https://example.com/upload")
        val protocol = LiveICloudPhotoProtocol(transport, { SESSION })
        val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
        val source = CountingSource(JPEG)
        val prepared = protocol.prepareOriginal(binding, "photo.jpg", source)
        assertTrue(runCatching { prepared.startOnce("untrusted") }.isFailure)
        assertEquals(0, source.opens.get())
        assertEquals(0, transport.rawUploadCalls)
    }

    @Test
    fun `unsupported file registration is not accepted`() = runBlocking {
        val transport = ScriptedTransport(registrationStatus = 415)
        val protocol = LiveICloudPhotoProtocol(transport, { SESSION })
        val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
        assertTrue(runCatching {
            protocol.prepareOriginal(binding, "photo.jpg", CountingSource(JPEG)).startOnce("rejected")
        }.isFailure)
        assertEquals(1, transport.rawUploadCalls)
    }

    @Test
    fun `prepared upload opens body and crosses transport only once`() = runBlocking {
        val transport = ScriptedTransport()
        val protocol = LiveICloudPhotoProtocol(transport, { SESSION })
        val destination = requireNotNull(protocol.currentDestination())
        val binding = AccountBinding(destination.accountId, 1, 1)
        val source = CountingSource(JPEG)
        val prepared = protocol.prepareOriginal(binding, "camera shot.jpg", source)

        val accepted = prepared.startOnce("attempt-1")

        assertEquals("master-1", accepted.masterRecordName)
        assertEquals("asset-1", accepted.assetRecordName)
        assertEquals(1, transport.rawUploadCalls)
        assertEquals(1, source.opens.get())
        assertEquals(JPEG.toList(), transport.uploadedBytes.toList())
        assertEquals("https://cws.icloud-content.com/singleFileUpload?tk=test", transport.lastUploadUrl)
        val registration = Json.parseToJsonElement(transport.requests.last().body!!).jsonObject
        assertEquals("camera shot.jpg", registration["files"]!!.jsonArray.single().jsonObject["fileName"]!!.jsonPrimitive.content)
        assertTrue(transport.requests.all { it.oneShot })

        assertTrue(runCatching { prepared.startOnce("attempt-1") }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, transport.rawUploadCalls)
        assertEquals(1, source.opens.get())
    }

    @Test
    fun `candidate is freshly hydrated and original streams without exposing signed url`() = runBlocking {
        val transport = ScriptedTransport()
        val protocol = LiveICloudPhotoProtocol(transport, { SESSION })
        val destination = requireNotNull(protocol.currentDestination())
        val binding = AccountBinding(destination.accountId, 4, 9)

        assertEquals("before-token", protocol.captureChangeToken(binding))
        val page = protocol.findUploadCandidates(binding, "before-token", null, 50)

        assertEquals(1, page.candidates.size)
        assertEquals("after-token", page.nextChangeToken)
        assertEquals(
            1,
            transport.requests.count { URI(it.url).path.endsWith("/records/lookup") },
        )
        val output = ByteArrayOutputStream()
        val observation = protocol.streamFreshOriginal(binding, page.candidates.single(), output)
        assertEquals(JPEG.size.toLong(), observation.byteCount)
        assertEquals(JPEG.toList(), output.toByteArray().toList())
        assertTrue(transport.requests.none { it.body.orEmpty().contains("download.example") })
    }

    @Test
    fun `candidate hydration batches large change pages`() = runBlocking {
        val transport = BatchingTransport(candidateCount = 120)
        val protocol = LiveICloudPhotoProtocol(transport, { SESSION })
        val destination = requireNotNull(protocol.currentDestination())

        val page = protocol.findUploadCandidates(
            binding = AccountBinding(destination.accountId, 1, 1),
            afterChangeToken = "before-token",
            acceptance = null,
            limit = 500,
        )

        assertEquals(120, page.candidates.size)
        assertEquals(3, transport.lookupCalls)
    }

    @Test
    fun `original format suffix and source date survive every resumable phase`() = runBlocking {
        val sourceDate = 1_790_154_600_123L
        for (extension in listOf("png", "heic", "webp", "mp4", "mov")) {
            for (phase in listOf("RESERVING", "RESERVED", "RECEIPT_SAVED", "ACCEPTED")) {
                val transport = ScriptedTransport()
                val journal = CrashJournal(phase)
                val protocol = LiveICloudPhotoProtocol(transport, { SESSION }, uploadJournal = journal)
                val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
                val source = CountingSource(JPEG)
                val name = "camera.$extension"
                assertTrue(runCatching {
                    protocol.prepareOriginal(binding, name, source, sourceDate).startOnce("format")
                }.isFailure)
                val saved = Json.parseToJsonElement(journal.checkpoint!!.payload).jsonObject
                assertEquals(name, saved["fileName"]!!.jsonPrimitive.content)
                assertEquals(sourceDate, saved["lastModDate"]!!.jsonPrimitive.long)
                val accepted = protocol.prepareOriginal(binding, name, source, sourceDate).startOnce("format")
                assertEquals("job-1", accepted.uploadJobId)
                val writes = transport.requests.filter { it.url.contains("putAsset") }
                assertEquals(1, writes.size)
                val file = Json.parseToJsonElement(writes.single().body!!).jsonObject["files"]!!.jsonArray.single().jsonObject
                assertEquals(name, file["fileName"]!!.jsonPrimitive.content)
                assertEquals(sourceDate, file["lastModDate"]!!.jsonPrimitive.long)
                assertEquals(1, source.opens.get())
                assertEquals(JPEG.toList(), transport.uploadedBytes.toList())
                assertTrue(transport.requests.all { it.oneShot && it.headers["Content-Type"] == "text/plain;charset=UTF-8" })
            }
        }
    }

    @Test
    fun `shared upload freezes its destination and registers a post batch instead of personal import`() = runBlocking {
        val transport = ScriptedTransport()
        val journal = CrashJournal("RECEIPT_SAVED")
        val binding = AccountBinding("account", 1, 1)
        val source = CountingSource(JPEG)
        fun prepared(zone: String) = PhotosUpload(transport, SESSION, "https://p99-photosupload.icloud.com", "shared.jpg", source,
            persistSession = {}, journal = journal, binding = binding, zoneName = zone, assetBatchId = "CPLPost-test")
        assertTrue(runCatching { prepared("SharedCollection-test").startOnce("shared-upload") }.isFailure)
        assertTrue(runCatching { prepared("PrimarySync").startOnce("shared-upload") }.isFailure)
        prepared("SharedCollection-test").startOnce("shared-upload")
        val reserve = Json.parseToJsonElement(transport.requests.first { it.url.contains("createUploadUrl") }.body!!).jsonObject
        val registration = Json.parseToJsonElement(transport.requests.last { it.url.contains("putAsset") }.body!!).jsonObject
        assertEquals("SharedCollection-test", reserve["zoneName"]!!.jsonPrimitive.content)
        assertEquals("SharedCollection-test", registration["zoneName"]!!.jsonPrimitive.content)
        assertEquals("CPLPost-test", registration["assetBatchId"]!!.jsonPrimitive.content)
        assertTrue(!registration.containsKey("importGroup"))
        assertEquals(1, source.opens.get())
    }

    @Test
    fun `browser source date uses upload time offset and freezes it across timezone changes`() = runBlocking {
        val originalZone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Rome"))
            val transport = ScriptedTransport()
            val journal = CrashJournal("RECEIPT_SAVED")
            val binding = AccountBinding("account", 1, 1)
            val source = CountingSource(JPEG)
            val sourceDate = 1_768_651_200_000L // January 17, 2026, 12:00 UTC
            fun prepared() = PhotosUpload(transport, SESSION, "https://p99-photosupload.icloud.com", "winter.mov", source,
                persistSession = {}, journal = journal, binding = binding, lastModifiedAtEpochMillis = sourceDate,
                now = { java.time.Instant.parse("2026-09-23T12:00:00Z") })
            assertTrue(runCatching { prepared().startOnce("winter") }.isFailure)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            prepared().startOnce("winter")
            val registration = Json.parseToJsonElement(transport.requests.last().body!!).jsonObject
            val file = registration["files"]!!.jsonArray.single().jsonObject
            assertEquals(sourceDate, file["lastModDate"]!!.jsonPrimitive.long)
            assertEquals(-120, file["timeZoneOffset"]!!.jsonPrimitive.int)
            assertEquals("Europe/Rome", registration["localTimeZoneId"]!!.jsonPrimitive.content)
            assertEquals(1, source.opens.get())
        } finally { java.util.TimeZone.setDefault(originalZone) }
    }

    @Test
    fun `fallback date stays frozen and changed supplied metadata is rejected`() = runBlocking {
        for (changed in listOf("none", "filename", "date")) {
            val transport = ScriptedTransport()
            val journal = CrashJournal("RECEIPT_SAVED")
            val protocol = LiveICloudPhotoProtocol(transport, { SESSION }, uploadJournal = journal)
            val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
            val source = CountingSource(JPEG)
            assertTrue(runCatching { protocol.prepareOriginal(binding, "photo.png", source).startOnce("date") }.isFailure)
            val savedDate = Json.parseToJsonElement(journal.checkpoint!!.payload).jsonObject["lastModDate"]!!.jsonPrimitive.long
            val result = runCatching {
                protocol.prepareOriginal(binding, if (changed == "filename") "other.png" else "photo.png", source,
                    if (changed == "date") savedDate + 1 else null).startOnce("date")
            }
            if (changed == "none") {
                assertTrue(result.isSuccess)
                val registration = Json.parseToJsonElement(transport.requests.last().body!!).jsonObject
                assertEquals(savedDate, registration["files"]!!.jsonArray.single().jsonObject["lastModDate"]!!.jsonPrimitive.long)
            } else {
                assertTrue(result.isFailure)
                assertTrue(transport.requests.none { it.url.contains("putAsset") })
            }
            assertEquals(1, source.opens.get())
        }
    }

    @Test
    fun `legacy safe checkpoint receives metadata before registration`() = runBlocking {
        val transport = ScriptedTransport()
        val journal = CrashJournal("RECEIPT_SAVED")
        val protocol = LiveICloudPhotoProtocol(transport, { SESSION }, uploadJournal = journal)
        val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
        val source = CountingSource(JPEG)
        assertTrue(runCatching { protocol.prepareOriginal(binding, "photo.png", source).startOnce("legacy") }.isFailure)
        val checkpoint = journal.checkpoint!!
        val payload = Json.parseToJsonElement(checkpoint.payload).jsonObject
        journal.checkpoint = checkpoint.copy(payload = JsonObject(payload - setOf("fileName", "lastModDate", "localTimeZoneId", "timeZoneOffset")).toString())
        protocol.prepareOriginal(binding, "photo.png", source, 1234L).startOnce("legacy")
        val registration = Json.parseToJsonElement(transport.requests.last().body!!).jsonObject
        assertEquals(1234L, registration["files"]!!.jsonArray.single().jsonObject["lastModDate"]!!.jsonPrimitive.long)
        assertEquals(1, source.opens.get())
    }

    @Test
    fun `processing job distinguishes progress completion failure and missing evidence`() = runBlocking {
        val cases = listOf(
            """{"job-1":{"progress":95}}""" to UploadProcessingStatus.Processing(95),
            """{"job-1":{"progress":100}}""" to UploadProcessingStatus.Complete,
            """{"job-1":{"errorCode":500}}""" to UploadProcessingStatus.Failed(500),
            """{}""" to UploadProcessingStatus.Unknown,
            """{"other-job":{"progress":100}}""" to UploadProcessingStatus.Unknown,
        )
        for ((body, expected) in cases) {
            val transport = ScriptedTransport(processingBody = body)
            val protocol = LiveICloudPhotoProtocol(transport, { SESSION })
            val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
            assertEquals(expected, protocol.uploadProcessingStatus(binding, acceptance()))
            val read = transport.requests.single()
            assertTrue(read.url.contains("/photosupload/uploadStatus"))
            assertEquals("POST", read.method)
            assertEquals("text/plain;charset=UTF-8", read.headers["Content-Type"])
            assertEquals("""{"uploadJobIds":["job-1"]}""", read.body)
            assertTrue(!read.oneShot)
            assertEquals(0, transport.rawUploadCalls)
        }
    }

    @Test
    fun `duplicate and legacy acceptance do not invent a processing job`() = runBlocking {
        val transport = ScriptedTransport()
        val protocol = LiveICloudPhotoProtocol(transport, { SESSION })
        val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
        assertEquals(UploadProcessingStatus.Unknown, protocol.uploadProcessingStatus(binding, acceptance().copy(uploadJobId = null)))
        assertEquals(UploadProcessingStatus.Unknown, protocol.uploadProcessingStatus(binding, acceptance().copy(duplicateHint = true)))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `malformed status and read failures never become processing success`() = runBlocking {
        for (body in listOf("[]", "bad-json", """{"job-1":{}}""", """{"job-1":null}""",
            """{"job-1":{"progress":101}}""", """{"job-1":{"progress":"100"}}""",
            """{"job-1":{"errorCode":"500"}}""")) {
            val protocol = LiveICloudPhotoProtocol(ScriptedTransport(processingBody = body), { SESSION })
            val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
            val error = runCatching { protocol.uploadProcessingStatus(binding, acceptance()) }.exceptionOrNull()
            assertTrue(error is AppleProtocolException)
            assertEquals(AppleProtocolError.MALFORMED_RESPONSE, (error as AppleProtocolException).error)
        }
        for (httpCode in listOf(401, 500)) {
            val protocol = LiveICloudPhotoProtocol(ScriptedTransport(processingHttpCode = httpCode), { SESSION })
            val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
            assertTrue(runCatching { protocol.uploadProcessingStatus(binding, acceptance()) }.exceptionOrNull() is AppleProtocolException)
        }
        for (failure in listOf(java.io.IOException("offline"), kotlinx.coroutines.CancellationException("cancelled"))) {
            val transport = ScriptedTransport(onProcessing = { throw failure })
            val protocol = LiveICloudPhotoProtocol(transport, { SESSION })
            val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
            assertTrue(runCatching { protocol.uploadProcessingStatus(binding, acceptance()) }.exceptionOrNull() === failure)
            assertEquals(1, transport.requests.size)
            assertEquals(0, transport.rawUploadCalls)
        }
    }

    @Test
    fun `status read fences changed account and authentication before persisting`() = runBlocking {
        for (change in listOf("account-before", "session-during", "authorization-during")) {
            var session = SESSION
            var authorized = true
            var persisted = false
            val transport = ScriptedTransport(onProcessing = {
                if (change == "session-during") session = SESSION.copy(clientId = "replacement")
                if (change == "authorization-during") authorized = false
            })
            val protocol = LiveICloudPhotoProtocol(transport, { session }, onSessionUpdated = { persisted = true },
                isAuthorized = { authorized })
            val binding = AccountBinding(protocol.currentDestination()!!.accountId, 1, 1)
            if (change == "account-before") session = SESSION.copy(dsid = "different-account")
            assertTrue(runCatching { protocol.uploadProcessingStatus(binding, acceptance()) }.isFailure)
            assertTrue(!persisted)
            assertEquals(if (change == "account-before") 0 else 1, transport.requests.size)
        }
    }

    private fun acceptance() = UploadAcceptance(null, "master-1", "asset-1", false, "job-1")

    private class CountingSource(private val bytes: ByteArray) : OneShotUploadSource {
        val opens = AtomicInteger(0)
        override val byteCount: Long = bytes.size.toLong()
        override val sha256Hex: String = sha256(bytes)

        override fun openOnce(): InputStream {
            check(opens.incrementAndGet() == 1)
            return ByteArrayInputStream(bytes)
        }
    }

    private class ScriptedTransport(
        val registrationStatus: Int = 200,
        val failRegistration: Boolean = false,
        val uploadTarget: String = "https://cws.icloud-content.com/singleFileUpload?tk=test",
        val receiptSize: Long = 6,
        val processingBody: String = """{"job-1":{"progress":100}}""",
        val processingHttpCode: Int = 200,
        val reservationHttpCode: Int = 200,
        val onProcessing: () -> Unit = {},
    ) : AppleHttpTransport {
        val requests = mutableListOf<AppleHttpRequest>()
        var rawUploadCalls = 0
        var uploadedBytes = byteArrayOf()
        var lastUploadUrl: String? = null
        override val sessionHeaders = AppleSessionHeaders()

        override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse {
            requests += request
            return when (URI(request.url).path) {
                "/photosupload/uploadStatus" -> {
                    onProcessing()
                    AppleHttpResponse(processingHttpCode, emptyMap(), processingBody)
                }
                "/photosupload/createUploadUrl" -> {
                    val payload = Json.parseToJsonElement(request.body!!).jsonObject
                    val assets = payload["assets"]!!.jsonObject
                    assertEquals(JPEG.size.toLong(), assets.values.single().jsonPrimitive.long)
                    AppleHttpResponse(reservationHttpCode, emptyMap(), buildJsonObject {
                        putJsonObject("uploadUrls") { put(assets.keys.single(), uploadTarget) }
                    }.toString())
                }
                "/photosupload/putAsset" -> {
                    if (failRegistration) throw java.io.IOException("Response lost")
                    val file = Json.parseToJsonElement(request.body!!).jsonObject["files"]!!.jsonArray.single().jsonObject
                    assertEquals("opaque-receipt", file["singleFileUploadRequest"]!!.jsonObject["receipt"]!!.jsonPrimitive.content)
                    response("""[{"uploadJobId":"job-1","cplMaster":"master-1","cplAsset":"asset-1","response":{"status":$registrationStatus}}]""")
                }
                "/database/1/com.apple.photos.cloud/production/private/zones/list" -> response(
                    """{"zones":[{"zoneID":{"zoneName":"PrimarySync"},"syncToken":"before-token"}]}""",
                )
                "/database/1/com.apple.photos.cloud/production/private/changes/zone" -> response(
                    """
                    {
                      "zones":[{
                        "syncToken":"after-token",
                        "moreComing":false,
                        "records":[{
                          "recordType":"CPLAsset",
                          "recordName":"asset-1",
                          "fields":{"masterRef":{"value":{"recordName":"master-1"}}}
                        }]
                      }]
                    }
                    """.trimIndent(),
                )
                "/database/1/com.apple.photos.cloud/production/private/records/lookup" -> response(LOOKUP_RESPONSE)
                else -> error("Unexpected request ${request.url}")
            }
        }

        override suspend fun executeRawUpload(
            url: String,
            headers: Map<String, String>,
            contentLength: Long,
            openBodyOnce: () -> InputStream,
        ): AppleHttpResponse {
            rawUploadCalls += 1
            lastUploadUrl = url
            uploadedBytes = openBodyOnce().use { it.readBytes() }
            assertEquals(contentLength, uploadedBytes.size.toLong())
            return response(
                """
                {
                  "singleFile":{"size":$receiptSize,"referenceChecksum":"ref","fileChecksum":"sum","wrappingKey":"key","receipt":"opaque-receipt"}
                }
                """.trimIndent(),
            )
        }

        override suspend fun stream(url: String, output: OutputStream) {
            assertEquals("https://download.example.icloud-content.com/original?token=secret", url)
            output.write(JPEG)
        }

        override fun snapshotCookies(): List<PersistedCookie> = emptyList()
        override fun restore(snapshot: AppleSessionSnapshot) = Unit
        override fun clear() = Unit

        private fun response(body: String) = AppleHttpResponse(200, emptyMap(), body)
    }

    private class BatchingTransport(candidateCount: Int) : AppleHttpTransport {
        private val changedAssets = (0 until candidateCount).joinToString(",") { index ->
            """{"recordType":"CPLAsset","recordName":"asset-$index","fields":{"masterRef":{"value":{"recordName":"master-$index"}}}}"""
        }
        private val hydratedRecords = (0 until candidateCount).flatMap { index ->
            listOf(
                """
                {"recordType":"CPLMaster","recordName":"master-$index","recordChangeTag":"m-$index",
                 "fields":{"resOriginalRes":{"value":{"downloadURL":"https://download.example.icloud-content.com/$index","fileChecksum":"sum-$index","size":6}}}}
                """.trimIndent(),
                """
                {"recordType":"CPLAsset","recordName":"asset-$index","recordChangeTag":"a-$index",
                 "fields":{"masterRef":{"value":{"recordName":"master-$index"}}}}
                """.trimIndent(),
            )
        }.joinToString(",")

        var lookupCalls = 0
        override val sessionHeaders = AppleSessionHeaders()

        override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse = when (URI(request.url).path) {
            "/database/1/com.apple.photos.cloud/production/private/changes/zone" -> response(
                """{"zones":[{"syncToken":"after-token","moreComing":false,"records":[$changedAssets]}]}""",
            )
            "/database/1/com.apple.photos.cloud/production/private/records/lookup" -> {
                lookupCalls += 1
                response("""{"records":[$hydratedRecords]}""")
            }
            else -> error("Unexpected request ${request.url}")
        }

        override suspend fun executeRawUpload(
            url: String,
            headers: Map<String, String>,
            contentLength: Long,
            openBodyOnce: () -> InputStream,
        ): AppleHttpResponse = error("Not used")

        override suspend fun stream(url: String, output: OutputStream) = error("Not used")

        override fun snapshotCookies(): List<PersistedCookie> = emptyList()
        override fun restore(snapshot: AppleSessionSnapshot) = Unit
        override fun clear() = Unit

        private fun response(body: String) = AppleHttpResponse(200, emptyMap(), body)
    }

    private companion object {
        val JPEG = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3)
        val SESSION = AppleSessionSnapshot(
            accountName = "test@example.com",
            clientId = "client",
            accountCountryCode = "IT",
            sessionId = "session",
            sessionToken = "token",
            trustToken = "trust",
            dsid = "12345",
            webservices = mapOf(
                "ckdatabasews" to "https://p01-ckdatabasews.icloud.com",
                "photosupload" to "https://p99-photosupload.icloud.com",
            ),
            cookies = emptyList(),
        )
        val LOOKUP_RESPONSE = """
            {
              "records":[
                {
                  "recordType":"CPLMaster",
                  "recordName":"master-1",
                  "recordChangeTag":"master-tag",
                  "fields":{
                    "resOriginalRes":{"value":{
                      "downloadURL":"https://download.example.icloud-content.com/original?token=secret",
                      "fileChecksum":"checksum",
                      "size":6
                    }}
                  }
                },
                {
                  "recordType":"CPLAsset",
                  "recordName":"asset-1",
                  "recordChangeTag":"asset-tag",
                  "fields":{"masterRef":{"value":{"recordName":"master-1"}}}
                }
              ]
            }
        """.trimIndent()

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
