package me.lvca.alba.protocol.photos

import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.OneShotUploadSource
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.sha256Hex
import me.lvca.alba.protocol.network.AppleEndpointPolicy
import me.lvca.alba.protocol.network.AppleHttpResponse
import me.lvca.alba.protocol.network.AppleHttpTransport
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Content-bound retry journal for the legacy shared-streams upload protocol. */
internal class LegacySharedUpload(
    private val transport: AppleHttpTransport,
    private val account: () -> String,
    private val authorized: () -> Boolean,
    private val journal: UploadJournal?,
    private val connection: SharedAlbumSession,
    private val persist: suspend (AppleSessionSnapshot, AppleHttpResponse?) -> Unit,
    private val discover: suspend () -> List<SharedAlbumRecord>,
    private val readAlbum: suspend (SharedAlbumRecord) -> List<CloudPhotoAsset>,
    private val journalBinding: () -> AccountBinding,
    private val save: suspend (String, String, JsonObject) -> Unit,
) {
    suspend fun upload(id: String, fileName: String, source: OneShotUploadSource, operationId: String) {
        require(source.byteCount > 0 && fileName.isNotBlank())
        check(authorized()) { "Sign in before contributing photos." }
        val current = connection.bound()
        val album = discover().firstOrNull { it.collection.id == id } ?: error("Shared album is no longer available.")
        require(album.scope == "legacy" && album.collection.shared?.canContribute == true) { "You cannot add photos to this album." }
        // A force-stop may lose the UI operation ID. Bind retries to the actual content instead.
        java.util.UUID.fromString(operationId)
        val key = "shared-upload:" + sha256Hex("${account()}|$id|$fileName|${source.sha256Hex}")
        val old = requireNotNull(journal).load(journalBinding(), key)
        var phase = old?.phase ?: "READY"
        fun newUpload() = buildJsonObject {
            put("target", id); put("size", source.byteCount); put("sha256", source.sha256Hex); put("fileName", fileName)
            put("assetGuid", java.util.UUID.randomUUID().toString()); put("batchGuid", java.util.UUID.randomUUID().toString())
            put("date", java.time.Instant.now().toString()); put("timezone", java.time.ZoneId.systemDefault().id)
        }
        var saved = old?.payload?.let { Json.parseToJsonElement(it).jsonObject } ?: newUpload()
        check(saved.text("target") == id && saved.text("fileName") == fileName && saved.text("sha256") == source.sha256Hex && saved.text("size")?.toLong() == source.byteCount) { "Shared upload source or destination changed." }
        if (phase == "DONE") {
            if (readAlbum(album).any { it.assetRecordName == saved.text("assetGuid") }) return
            // A confirmed completed copy was subsequently removed. An explicit new add is safe.
            saved = newUpload(); phase = "READY"
        }
        suspend fun savePhase(next: String, additions: Map<String, JsonElement> = emptyMap()) {
            saved = JsonObject(saved + additions); save(key, next, saved); phase = next
        }
        if (phase == "REGISTERING") {
            // The client-assigned asset GUID survives response loss. Do not register the same bytes twice.
            if (readAlbum(album).any { it.assetRecordName == saved.text("assetGuid") }) { savePhase("DONE"); return }
            error("The shared upload result is uncertain. Check the album before adding it again.")
        }
        check(phase != "BYTES_SENDING") { "The shared upload was interrupted. Its bytes will not be replayed automatically." }
        if (phase in setOf("READY", "RESERVING")) {
            savePhase("RESERVING")
            val reserved = connection.request("${album.location!!.trimEnd('/')}/webgetuploadurl", buildJsonObject {
                put("albumguid", album.zone); put("filesize", source.byteCount)
            }, write = true)
            val target = requireNotNull(reserved.text("uploadurl")).toHttpUrl().newBuilder().addQueryParameter("chunker", "FIXED").build().toString()
            val allowed = AppleEndpointPolicy.requireAllowed(target)
            require(allowed.host.endsWith(".icloud-content.com") || allowed.host.endsWith(".icloud-content.com.cn"))
            savePhase("RESERVED", mapOf("uploadUrl" to JsonPrimitive(target)))
        }
        if (phase == "RESERVED") {
            connection.checkSession(current); check(authorized())
            savePhase("BYTES_SENDING")
            val raw = transport.executeRawUpload(requireNotNull(saved.text("uploadUrl")), emptyMap(), source.byteCount, source::openOnce)
            connection.checkSession(current); persist(current, raw)
            check(raw.code in 200..299) { "Shared upload bytes were not accepted." }
            val receipt = requireNotNull(Json.parseToJsonElement(raw.body).jsonObject["singleFile"] as? JsonObject)
            check(receipt.text("size")?.toLong() == source.byteCount && listOf("referenceChecksum", "fileChecksum", "wrappingKey", "receipt").all { !receipt.text(it).isNullOrBlank() })
            savePhase("RECEIPT_SAVED", mapOf("receipt" to receipt))
        }
        if (phase == "RECEIPT_SAVED") {
            // Re-read permission before the publishing step; UI capabilities can be stale.
            check(discover().firstOrNull { it.collection.id == id }?.collection?.shared?.canContribute == true)
            savePhase("REGISTERING")
            val registered = connection.request("${album.location!!.trimEnd('/')}/webputasset", buildJsonObject {
                put("albumguid", album.zone); put("assetguid", saved.getValue("assetGuid")); put("filename", fileName)
                putJsonObject("collectionmetadata") {
                    put("batchDateCreated", saved.getValue("date")); put("dateCreated", saved.getValue("date")); put("batchGUID", saved.getValue("batchGuid"))
                }
                put("singlefileupload", saved.getValue("receipt")); put("localtimezoneid", saved.getValue("timezone"))
            }, write = true)
            check(registered.text("albumguid") == album.zone && registered.text("assetguid") == saved.text("assetGuid"))
            savePhase("POLLING", mapOf("jobId" to JsonPrimitive(requireNotNull(registered.text("uploadjobid")))))
        }
        if (phase == "POLLING") {
            var complete = false
            repeat(20) {
                if (!complete) {
                    val result = connection.requestElement("${album.location!!.trimEnd('/')}/webuploadstatus", buildJsonObject {
                        putJsonArray("uploadjobids") { add(requireNotNull(saved.text("jobId"))) }
                    }).jsonArray.single().jsonObject
                    check(result.text("uploadjobid") == saved.text("jobId"))
                    when (result.text("status")) {
                        "COMPLETED" -> complete = true
                        "IN_PROGRESS" -> kotlinx.coroutines.delay(1000)
                        else -> error("Apple could not finish the shared upload. Check the album before retrying.")
                    }
                }
            }
            check(complete) { "Shared upload is still processing. Retry to check the same job." }
            savePhase("DONE")
        }
    }

    private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull
}
