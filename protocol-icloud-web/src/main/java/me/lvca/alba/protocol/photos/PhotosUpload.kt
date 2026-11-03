package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.*
import me.lvca.alba.engine.model.ICloudStorageFullException
import me.lvca.alba.engine.model.OneShotUploadSource
import me.lvca.alba.engine.model.PreparedICloudUpload
import me.lvca.alba.engine.model.UploadAcceptance
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.auth.AppleProtocolError
import me.lvca.alba.protocol.auth.AppleProtocolException
import me.lvca.alba.protocol.network.AppleClientProfile
import me.lvca.alba.protocol.network.AppleClientProfile.addUploadClientBuild
import me.lvca.alba.protocol.network.AppleEndpointPolicy
import me.lvca.alba.protocol.network.AppleHttpRequest
import me.lvca.alba.protocol.network.AppleHttpResponse
import me.lvca.alba.protocol.network.AppleHttpTransport
import me.lvca.alba.protocol.text
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** pyicloud 2.7.0 / Apple's photosupload protocol. One invocation, no write retries. */
internal class PhotosUpload(
    private val transport: AppleHttpTransport,
    private val session: AppleSessionSnapshot,
    private val serviceRoot: String,
    private val fileName: String,
    private val source: OneShotUploadSource,
    private val persistSession: suspend (AppleHttpResponse) -> Unit,
    private val journal: UploadJournal? = null,
    private val binding: me.lvca.alba.engine.model.AccountBinding? = null,
    private val checkAuthorized: () -> Unit = {},
    private val lastModifiedAtEpochMillis: Long? = null,
    private val captureOffsetMinutes: Int? = null,
    private val now: () -> Instant = { Instant.now() },
    private val phoneZone: () -> ZoneId = { ZoneId.systemDefault() },
    private val zoneName: String = PrimarySyncZone.NAME,
    private val assetBatchId: String? = null,
    private val onSessionRejected: suspend (AppleSessionSnapshot) -> Unit = {},
) : PreparedICloudUpload {
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun startOnce(attemptId: String): UploadAcceptance {
        require(attemptId.isNotBlank())
        check(started.compareAndSet(false, true)) { "Prepared upload may be started only once" }
        check(!cancelled.get()) { "Upload cancelled" }
        checkAuthorized()
        val previous = binding?.let { journal?.load(it, attemptId) }
        check(previous == null || previous.phase in RESUMABLE) { "An uncertain write cannot be replayed" }
        var saved = previous?.let { json.parseToJsonElement(it.payload).jsonObject } ?: buildJsonObject {
            put("clientId", UUID.randomUUID().toString())
            put("size", source.byteCount); put("sha256", source.sha256Hex)
        }
        check(saved["size"]?.jsonPrimitive?.long == source.byteCount && saved["sha256"]?.jsonPrimitive?.content == source.sha256Hex)
        // Freeze registration metadata before the first write, including a fallback date when
        // the source has no date. Legacy safe checkpoints acquire metadata at their next resume.
        saved["fileName"]?.let { check(it.jsonPrimitive.content == fileName) { "Upload filename changed" } }
        if (lastModifiedAtEpochMillis != null && saved["lastModDate"] != null) {
            check(saved["lastModDate"]!!.jsonPrimitive.long == lastModifiedAtEpochMillis) { "Upload source date changed" }
        }
        if (saved["fileName"] == null || saved["lastModDate"] == null || saved["localTimeZoneId"] == null || saved["timeZoneOffset"] == null) {
            val uploadedAt = now()
            val instant = Instant.ofEpochMilli(saved["lastModDate"]?.jsonPrimitive?.long
                ?: lastModifiedAtEpochMillis ?: uploadedAt.toEpochMilli())
            val (zoneId, offsetMinutes) = uploadZone(phoneZone(), uploadedAt, instant, captureOffsetMinutes)
            saved = JsonObject(saved + mapOf(
                "fileName" to JsonPrimitive(fileName),
                "lastModDate" to JsonPrimitive(instant.toEpochMilli()),
                "localTimeZoneId" to JsonPrimitive(zoneId),
                // Minutes west of UTC, as JavaScript's getTimezoneOffset() gives them to iCloud web.
                "timeZoneOffset" to JsonPrimitive(-offsetMinutes),
            ))
            if (previous != null) binding?.let { journal?.save(it, attemptId, UploadCheckpoint(previous.phase, saved.toString())) }
        }
        suspend fun save(phase: String, additions: Map<String, JsonElement> = emptyMap()) {
            saved = JsonObject(saved + additions)
            binding?.let { journal?.save(it, attemptId, UploadCheckpoint(phase, saved.toString())) }
        }
        saved["zoneName"]?.let { check(it.jsonPrimitive.content == zoneName) { "Upload destination changed" } }
        saved = JsonObject(saved + ("zoneName" to JsonPrimitive(zoneName)))
        val clientId = saved["clientId"]!!.jsonPrimitive.content
        if (previous?.phase !in setOf("RESERVED", "RECEIPT_SAVED", "ACCEPTED")) {
        save("RESERVING")
        checkAuthorized()
        val reservation = post("createUploadUrl", buildJsonObject {
            put("zoneName", zoneName)
            putJsonObject("assets") { put(clientId, source.byteCount) }
        }).also { response ->
            // Nothing has been sent yet, so the attempt stays resumable once there is space.
            if (response.refusesForStorage()) throw ICloudStorageFullException()
        }.objectBody()
        val target = (reservation["uploadUrls"] as? JsonObject)?.text(clientId)
            ?: malformed("Apple did not reserve the requested upload")
        save("RESERVED", mapOf("target" to JsonPrimitive(target)))
        }
        if (previous?.phase !in setOf("RECEIPT_SAVED", "ACCEPTED")) {
        val target = saved["target"]!!.jsonPrimitive.content
        val allowed = AppleEndpointPolicy.requireAllowed(target)
        require(allowed.host == "icloud-content.com" || allowed.host.endsWith(".icloud-content.com") ||
            allowed.host == "icloud-content.com.cn" || allowed.host.endsWith(".icloud-content.com.cn")) {
            "Apple returned an unexpected upload content host"
        }
        check(!cancelled.get()) { "Upload cancelled" }
        checkAuthorized()
        save("BYTES_SENDING")
        check(!cancelled.get()) { "Upload cancelled" }
        checkAuthorized()
        val uploaded = transport.executeRawUpload(target, emptyMap(), source.byteCount, source::openOnce)
        checkAuthorized()
        persistSession(uploaded)
        val receipt = uploaded.objectBody()["singleFile"] as? JsonObject
            ?: malformed("Apple returned no upload receipt")
        if ((receipt["size"] as? JsonPrimitive)?.longOrNull != source.byteCount ||
            listOf("referenceChecksum", "fileChecksum", "wrappingKey", "receipt").any { receipt.text(it).isNullOrBlank() }) {
            malformed("Apple returned an incomplete or mismatched upload receipt")
        }
        save("RECEIPT_SAVED", mapOf("receipt" to receipt))
        }
        check(!cancelled.get()) { "Upload cancelled" }
        val result = if (previous?.phase == "ACCEPTED") saved["result"]!!.jsonObject else {
        val receipt = saved["receipt"]!!.jsonObject
        checkAuthorized()
        save("REGISTERING")
        val registration = post("putAsset", buildJsonObject {
            put("zoneName", zoneName)
            if (assetBatchId == null) put("importGroup", clientId) else put("assetBatchId", assetBatchId)
            put("localTimeZoneId", saved.getValue("localTimeZoneId"))
            putJsonArray("files") {
                add(buildJsonObject {
                    put("fileName", saved.getValue("fileName"))
                    put("lastModDate", saved.getValue("lastModDate"))
                    put("timeZoneOffset", saved.getValue("timeZoneOffset"))
                    put("singleFileUploadRequest", receipt)
                })
            }
        })
        val results = registration.elementBody() as? JsonArray
            ?: malformed("Apple returned an invalid registration response")
        val result = results.singleOrNull() as? JsonObject
            ?: malformed("Apple returned an unexpected number of registered files")
        result
        }
        val status = ((result["response"] as? JsonObject)?.get("status") as? JsonPrimitive)?.intOrNull
        if (CloudKitErrors.quotaExceeded(status ?: 0, result)) throw ICloudStorageFullException()
        if (status != 200 && status != 409) {
            throw AppleProtocolException(AppleProtocolError.PHOTOS_UNAVAILABLE,
                "Apple Photos registration returned status ${status ?: "unknown"}.")
        }
        val acceptance = UploadAcceptance(
            requestUuid = null,
            masterRecordName = result.text("cplMaster")?.takeIf(String::isNotBlank)
                ?: malformed("Apple returned no master record name"),
            assetRecordName = result.text("cplAsset")?.takeIf(String::isNotBlank)
                ?: malformed("Apple returned no asset record name"),
            duplicateHint = status == 409,
            uploadJobId = result.text("uploadJobId")?.takeIf(String::isNotBlank),
        )
        save("ACCEPTED", mapOf("result" to result))
        return acceptance
    }

    override fun cancel() { cancelled.set(true) }

    private suspend fun post(path: String, body: JsonObject): AppleHttpResponse {
        check(!cancelled.get()) { "Upload cancelled" }
        checkAuthorized()
        val url = "$serviceRoot/photosupload/$path".toHttpUrl().newBuilder()
            .addQueryParameter("dsid", session.dsid)
            .addQueryParameter("clientId", session.clientId)
            .addUploadClientBuild()
            .build().toString()
        val response = transport.execute(AppleHttpRequest("POST", url,
            mapOf("Content-Type" to AppleClientProfile.WEB_SERVICE_CONTENT_TYPE), body.toString(), oneShot = true))
        checkAuthorized()
        persistSession(response)
        return response
    }

    private suspend fun AppleHttpResponse.elementBody(): JsonElement {
        if (code !in 200..299) {
            if (code == 401 || code == 403) onSessionRejected(session)
            throw AppleProtocolException(
                if (code == 401 || code == 403) AppleProtocolError.SESSION_EXPIRED else AppleProtocolError.PHOTOS_UNAVAILABLE,
                "Apple Photos upload returned HTTP $code.",
            )
        }
        return runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: malformed("Apple returned invalid upload JSON")
    }

    private suspend fun AppleHttpResponse.objectBody() = elementBody() as? JsonObject
        ?: malformed("Apple returned an invalid upload response")

    private fun AppleHttpResponse.refusesForStorage(): Boolean = CloudKitErrors.quotaExceeded(
        code,
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull(),
    )

    private fun malformed(message: String): Nothing =
        throw AppleProtocolException(AppleProtocolError.MALFORMED_RESPONSE, message)
    companion object {
        /**
         * The zone and offset (minutes east of UTC) registered with an upload. iCloud web sends the browser's zone and
         * its offset at upload time, even for a winter-dated file; without a recorded capture offset Alba does the same
         * with the phone's zone. With one, the capture's own offset is sent, named by the phone's zone when that zone
         * had the same offset then, else by the matching fixed-offset IANA zone (Etc/GMT-9 is UTC+9). An offset no IANA
         * id names that way, such as UTC+5:30 away from that zone, falls back to what iCloud web sends.
         */
        internal fun uploadZone(phone: ZoneId, uploadedAt: Instant, capturedAt: Instant, captureOffsetMinutes: Int?): Pair<String, Int> {
            val asICloudWeb = phone.id to phone.rules.getOffset(uploadedAt).totalSeconds / SECONDS_PER_MINUTE
            if (captureOffsetMinutes == null) return asICloudWeb
            val hours = captureOffsetMinutes / MINUTES_PER_HOUR
            val zone = when {
                phone.rules.getOffset(capturedAt).totalSeconds == captureOffsetMinutes * SECONDS_PER_MINUTE -> phone.id
                captureOffsetMinutes == 0 -> "Etc/UTC"
                captureOffsetMinutes % MINUTES_PER_HOUR == 0 && hours in FIXED_ZONE_HOURS ->
                    // IANA's Etc/GMT zones invert the sign: Etc/GMT-9 is UTC+9.
                    "Etc/GMT" + (if (hours > 0) "-" else "+") + kotlin.math.abs(hours)
                else -> return asICloudWeb
            }
            return zone to captureOffsetMinutes
        }

        private const val SECONDS_PER_MINUTE = 60
        private const val MINUTES_PER_HOUR = 60

        /** The whole-hour offsets the Etc/GMT zones name, UTC-12 to UTC+14. */
        private val FIXED_ZONE_HOURS = -12..14

        val RESUMABLE = setOf("RESERVING", "RESERVED", "RECEIPT_SAVED", "ACCEPTED")

        /**
         * Phases saved before "REGISTERING", which is saved before putAsset is sent. In these the photo was never
         * registered, so it is not in the library: at most its bytes were sent to content storage, unreferenced.
         */
        val BEFORE_REGISTRATION = setOf("RESERVING", "RESERVED", "BYTES_SENDING", "RECEIPT_SAVED")
    }
}
