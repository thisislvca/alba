package dev.mela.protocol.photos

import dev.mela.engine.model.OneShotUploadSource
import dev.mela.engine.model.PreparedICloudUpload
import dev.mela.engine.model.UploadAcceptance
import dev.mela.protocol.account.AppleSessionSnapshot
import dev.mela.protocol.auth.AppleProtocolError
import dev.mela.protocol.auth.AppleProtocolException
import dev.mela.protocol.network.AppleEndpointPolicy
import dev.mela.protocol.network.AppleHttpRequest
import dev.mela.protocol.network.AppleHttpResponse
import dev.mela.protocol.network.AppleHttpTransport
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** pyicloud 2.7.0 / Apple's photosupload protocol. One invocation, no write retries. */
internal class PhotosUpload(
    private val transport: AppleHttpTransport,
    private val session: AppleSessionSnapshot,
    private val serviceRoot: String,
    private val fileName: String,
    private val source: OneShotUploadSource,
    private val persistSession: suspend () -> Unit,
    private val journal: UploadJournal? = null,
    private val binding: dev.mela.engine.model.AccountBinding? = null,
    private val checkAuthorized: () -> Unit = {},
    private val lastModifiedAtEpochMillis: Long? = null,
    private val now: () -> Instant = { Instant.now() },
    private val zoneName: String = "PrimarySync",
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
            val zone = ZoneId.systemDefault()
            saved = JsonObject(saved + mapOf(
                "fileName" to JsonPrimitive(fileName),
                "lastModDate" to JsonPrimitive(instant.toEpochMilli()),
                "localTimeZoneId" to JsonPrimitive(zone.id),
                // iCloud web uses the upload-time offset, even for a winter-dated source.
                "timeZoneOffset" to JsonPrimitive(-zone.rules.getOffset(uploadedAt).totalSeconds / 60),
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
        }).objectBody()
        val target = (reservation["uploadUrls"] as? JsonObject)?.string(clientId)
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
        persistSession()
        val receipt = uploaded.objectBody()["singleFile"] as? JsonObject
            ?: malformed("Apple returned no upload receipt")
        if ((receipt["size"] as? JsonPrimitive)?.longOrNull != source.byteCount ||
            listOf("referenceChecksum", "fileChecksum", "wrappingKey", "receipt").any { receipt.string(it).isNullOrBlank() }) {
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
        if (status != 200 && status != 409) {
            throw AppleProtocolException(AppleProtocolError.PHOTOS_UNAVAILABLE,
                "Apple Photos registration returned status ${status ?: "unknown"}.")
        }
        val acceptance = UploadAcceptance(
            requestUuid = null,
            masterRecordName = result.string("cplMaster")?.takeIf(String::isNotBlank)
                ?: malformed("Apple returned no master record name"),
            assetRecordName = result.string("cplAsset")?.takeIf(String::isNotBlank)
                ?: malformed("Apple returned no asset record name"),
            duplicateHint = status == 409,
            uploadJobId = result.string("uploadJobId")?.takeIf(String::isNotBlank),
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
            .addQueryParameter("clientBuildNumber", "2634Build25")
            .addQueryParameter("clientMasteringNumber", "2634Build25")
            .build().toString()
        val response = transport.execute(AppleHttpRequest("POST", url,
            mapOf("Content-Type" to "text/plain;charset=UTF-8"), body.toString(), oneShot = true))
        checkAuthorized()
        persistSession()
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

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun malformed(message: String): Nothing =
        throw AppleProtocolException(AppleProtocolError.MALFORMED_RESPONSE, message)
    companion object { val RESUMABLE = setOf("RESERVING", "RESERVED", "RECEIPT_SAVED", "ACCEPTED") }
}
