package me.lvca.alba.protocol.photos

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.BackupAttribute
import me.lvca.alba.engine.model.BackupDiagnostics
import me.lvca.alba.engine.model.BackupPhase
import me.lvca.alba.engine.model.BackupTelemetry.traced
import me.lvca.alba.engine.model.ICloudStorageFullException
import me.lvca.alba.engine.model.LivePhotoAttachRefusedException
import me.lvca.alba.engine.model.LivePhotoAttachment
import me.lvca.alba.engine.model.LivePhotoVideo
import me.lvca.alba.engine.model.UploadReceipt
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.array
import me.lvca.alba.protocol.auth.AppleProtocolError
import me.lvca.alba.protocol.auth.AppleProtocolException
import me.lvca.alba.protocol.fieldFlag
import me.lvca.alba.protocol.fieldObject
import me.lvca.alba.protocol.isTombstone
import me.lvca.alba.protocol.network.AppleClientProfile
import me.lvca.alba.protocol.network.AppleEndpointPolicy
import me.lvca.alba.protocol.network.AppleHttpRequest
import me.lvca.alba.protocol.network.AppleHttpTransport
import me.lvca.alba.protocol.photos.CloudKitErrors.serverErrorCode
import me.lvca.alba.protocol.text
import java.io.ByteArrayInputStream

/**
 * Attaches a motion photo's video to the still Alba uploaded, making it an iCloud Live Photo. The recipe was proven on a
 * live account (iCloud.com and Photos on a Mac both showed the LIVE badge and played the motion):
 *
 * 1. `assets/upload` asks CloudKit for one upload URL per video resource of the CPLMaster: the original
 *    (`resOriginalVidComplRes`) and the two smaller ones iCloud.com plays (`resVidMedRes`, `resVidSmallRes`). Without the
 *    smaller ones iCloud.com shows a warning badge and will not play. The video's bytes are POSTed to each URL, which
 *    answers with a receipt (`singleFile`). Alba sends the original video for all three, which played.
 * 2. One atomic `records/modify` updates both records at the versions just read: the master gets the three resources
 *    with their file type, fingerprint, size and dimensions; the asset gets the video's duration and the still's time
 *    in it (nanoseconds), visibility 0, `assetSubtypeV2` 2 (Live Photo) and the pairing id as `mediaGroupId`.
 *
 * Every step is saved in the upload journal under `live:<attachId>`. Calling it again resumes: saved receipts are
 * reused while fresh, and a master that already carries this video is recognised, so nothing is sent twice. The modify
 * itself only sets fields, so sending it again at fresh record versions is harmless.
 */
internal class LivePhotoAttach(
    private val transport: AppleHttpTransport,
    private val cloudKit: CloudKitClient,
    private val session: AppleSessionSnapshot,
    private val binding: AccountBinding,
    private val journal: UploadJournal?,
    private val requireCurrent: (AppleSessionSnapshot) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val diagnostics: BackupDiagnostics = BackupDiagnostics.None,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun attach(attachId: String, masterName: String, assetName: String, video: LivePhotoVideo): LivePhotoAttachment {
        require(attachId.isNotBlank() && masterName.isNotBlank() && assetName.isNotBlank())
        val key = journalKey(attachId)
        val previous = loadCheckpoint(key)
        var saved = previous?.let { json.parseToJsonElement(it.payload).jsonObject } ?: buildJsonObject {
            put("master", masterName); put("asset", assetName); put("size", video.byteCount); put("sha256", video.sha256Hex)
        }
        if (!saved.describes(masterName, assetName, video)) {
            throw LivePhotoAttachRefusedException("The saved Live Photo attach is for another video", "JOURNAL_MISMATCH")
        }
        suspend fun save(phase: String, additions: Map<String, JsonElement> = emptyMap()) {
            saved = JsonObject(saved + additions)
            journal?.save(binding, key, UploadCheckpoint(phase, saved.toString()))
        }
        if (previous?.phase == MODIFIED) return attachmentOf(saved)

        val (master, asset) = currentRecords(masterName, assetName)
        val savedReceipts = saved["receipts"] as? JsonObject
        // The master already has this video: an earlier modify went through before its response was saved.
        if (alreadyHolds(master, savedReceipts, video)) {
            save(MODIFIED)
            return attachmentOf(saved)
        }
        val fresh = (saved["receiptsAt"] as? JsonPrimitive)?.longOrNull?.let { now() - it < RECEIPT_LIFETIME_MILLIS } == true
        val receipts = savedReceipts?.takeIf { fresh } ?: upload(video, masterName) { phase, additions -> save(phase, additions) }

        val writeGroup = (saved["mediaGroupId"] as? JsonPrimitive)?.booleanOrNull != false
        save(MODIFYING)
        val written = try {
            modify(master, asset, receipts, video, withMediaGroup = writeGroup)
            writeGroup
        } catch (_: MediaGroupRefused) {
            // CloudKit would not take mediaGroupId: the attach goes on without it, and the journal remembers that.
            save(MODIFYING, mapOf("mediaGroupId" to JsonPrimitive(false)))
            val (masterAgain, assetAgain) = currentRecords(masterName, assetName)
            modify(masterAgain, assetAgain, receipts, video, withMediaGroup = false)
            false
        }
        save(MODIFIED, mapOf("mediaGroupId" to JsonPrimitive(written)))
        return attachmentOf(saved)
    }

    /** The saved checkpoint; a row this sign-in cannot read is no row, and the records read afresh decide. */
    private suspend fun loadCheckpoint(key: String): UploadCheckpoint? = try {
        journal?.load(binding, key)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.describes(masterName: String, assetName: String, video: LivePhotoVideo): Boolean =
        text("master") == masterName && text("asset") == assetName &&
            (this["size"] as? JsonPrimitive)?.longOrNull == video.byteCount && text("sha256") == video.sha256Hex

    /**
     * Whether [master] already holds the video these [receipts] are for. One holding any other video is refused: clients
     * that cached an item's video do not pick up a changed one, so Alba only ever adds a video to a new item.
     */
    private fun alreadyHolds(master: JsonObject, receipts: JsonObject?, video: LivePhotoVideo): Boolean {
        val existing = master.fieldObject(ORIGINAL_VIDEO_RES) ?: return false
        val ours = receipts?.get(ORIGINAL_VIDEO_RES) as? JsonObject
        val same = ours != null && existing.text("fileChecksum") == ours.text("fileChecksum") &&
            (existing["size"] as? JsonPrimitive)?.longOrNull == video.byteCount
        if (!same) throw LivePhotoAttachRefusedException("This iCloud photo already has a different video", "ALREADY_HAS_VIDEO")
        return true
    }

    /** Apple's size and checksum of the Live Photo video [masterName] holds now, or null when it holds none. */
    suspend fun videoResource(masterName: String): UploadReceipt? {
        val master = lookup(listOf(masterName), listOf(ORIGINAL_VIDEO_RES)).firstOrNull { it.text("recordName") == masterName }
            ?: return null
        if (master.isTombstone()) return null
        return PhotosUpload.receiptOf(master.fieldObject(ORIGINAL_VIDEO_RES))
    }

    /** Downloads the Live Photo video [masterName] holds into [output]; returns how many bytes were written. */
    suspend fun streamVideo(masterName: String, output: java.io.OutputStream): Long {
        val master = lookup(listOf(masterName), listOf(ORIGINAL_VIDEO_RES)).firstOrNull { it.text("recordName") == masterName }
            ?: throw AppleProtocolException(AppleProtocolError.PHOTOS_UNAVAILABLE, "The iCloud photo is unavailable")
        val url = master.fieldObject(ORIGINAL_VIDEO_RES)?.text("downloadURL")
            ?: throw AppleProtocolException(AppleProtocolError.PHOTOS_UNAVAILABLE, "The iCloud photo has no Live Photo video")
        AppleEndpointPolicy.requireAllowed(url)
        var count = 0L
        transport.stream(url, object : java.io.OutputStream() {
            override fun write(value: Int) { output.write(value); count += 1 }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { output.write(buffer, offset, length); count += length }
        })
        cloudKit.persist(session, null)
        return count
    }

    /** Both records at their current versions; refused when either is gone or no longer a pair. */
    private suspend fun currentRecords(masterName: String, assetName: String): Pair<JsonObject, JsonObject> {
        val records = lookup(listOf(masterName, assetName), null)
        val master = records.firstOrNull { it.text("recordName") == masterName && it.text("recordType") == "CPLMaster" }
            ?.takeIf { !it.isTombstone() }
        val asset = records.firstOrNull { it.text("recordName") == assetName && it.text("recordType") == "CPLAsset" }
            ?.takeIf { it.isInLibraryWith(masterName) }
        if (master == null || asset == null) throw LivePhotoAttachRefusedException("The iCloud photo is no longer in the library", "RECORD_GONE")
        if (master.text("recordChangeTag").isNullOrBlank() || asset.text("recordChangeTag").isNullOrBlank()) {
            malformed("Apple omitted a record version")
        }
        return master to asset
    }

    private fun JsonObject.isInLibraryWith(masterName: String): Boolean =
        !isTombstone() && !fieldFlag("isDeleted") && !fieldFlag("isExpunged") && fieldObject("masterRef")?.text("recordName") == masterName

    /** Sends the video once to each resource's upload URL and saves the three receipts, keyed by field name. */
    private suspend fun upload(video: LivePhotoVideo, masterName: String, save: suspend (String, Map<String, JsonElement>) -> Unit): JsonObject {
        save(UPLOADING, emptyMap())
        val response = diagnostics.traced(BackupPhase.LIVE_ASSETS_UPLOAD) {
            post(
                "assets/upload",
                buildJsonObject {
                    putJsonObject("zoneID") { put("zoneName", PrimarySyncZone.NAME) }
                    putJsonArray("tokens") {
                        RESOURCE_PREFIXES.forEach { prefix ->
                            add(buildJsonObject { put("recordType", "CPLMaster"); put("fieldName", "${prefix}Res"); put("recordName", masterName) })
                        }
                    }
                },
            )
        }
        val tokens = response.array("tokens").filterIsInstance<JsonObject>()
        val receipts = buildJsonObject {
            RESOURCE_PREFIXES.forEachIndexed { index, prefix ->
                val field = "${prefix}Res"
                val token = tokens.firstOrNull { it.text("fieldName") == field } ?: tokens.getOrNull(index)
                val url = token?.text("url") ?: malformed("Apple did not hand out an upload URL for the Live Photo video")
                put(field, sendVideo(url, RESOURCE_NAMES[index], video))
            }
        }
        save(RECEIPTS_SAVED, mapOf("receipts" to receipts, "receiptsAt" to JsonPrimitive(now())))
        return receipts
    }

    /** POSTs the whole video to one upload [url] and returns Apple's receipt for it. */
    private suspend fun sendVideo(url: String, resource: String, video: LivePhotoVideo): JsonObject {
        val host = AppleEndpointPolicy.requireAllowed(url).host
        require(CONTENT_HOSTS.any { host == it || host.endsWith(".$it") }) { "Apple returned an unexpected upload content host" }
        requireCurrent(session)
        val uploaded = diagnostics.traced(BackupPhase.LIVE_VIDEO_POST, mapOf(BackupAttribute.PHASE to resource)) {
            transport.executeRawUpload(url, emptyMap(), video.byteCount) { ByteArrayInputStream(video.bytes) }
        }
        requireCurrent(session)
        cloudKit.persist(session, uploaded)
        if (uploaded.code !in HTTP_SUCCESS) {
            if (uploaded.code == HTTP_UNAUTHORIZED || uploaded.code == HTTP_FORBIDDEN) cloudKit.reject(session)
            throw if (CloudKitErrors.quotaExceeded(uploaded.code, null)) {
                ICloudStorageFullException()
            } else {
                AppleProtocolException(AppleProtocolError.NETWORK, "Apple returned HTTP ${uploaded.code} for the Live Photo video.")
            }
        }
        val receipt = (runCatching { json.parseToJsonElement(uploaded.body) }.getOrNull() as? JsonObject)?.get("singleFile") as? JsonObject
        val complete = receipt != null && (receipt["size"] as? JsonPrimitive)?.longOrNull == video.byteCount &&
            RECEIPT_KEYS.none { receipt.text(it).isNullOrBlank() }
        if (!complete) malformed("Apple returned no receipt, or an incomplete one, for the Live Photo video")
        return JsonObject(receipt!!.filterKeys { it in RECEIPT_KEYS || it == "size" })
    }

    private suspend fun modify(master: JsonObject, asset: JsonObject, receipts: JsonObject, video: LivePhotoVideo, withMediaGroup: Boolean) {
        val masterFields = buildJsonObject {
            RESOURCE_PREFIXES.forEach { prefix ->
                val receipt = receipts["${prefix}Res"] as? JsonObject ?: malformed("A Live Photo video receipt is missing")
                put("${prefix}Res", field("ASSETID", JsonObject(receipt.filterKeys { it in RECEIPT_KEYS || it == "size" })))
                put("${prefix}FileType", field("STRING", JsonPrimitive(VIDEO_FILE_TYPE)))
                put("${prefix}Fingerprint", field("STRING", JsonPrimitive(receipt.text("fileChecksum")!!)))
                put("${prefix}FileSize", field("INT64", JsonPrimitive(video.byteCount)))
                put("${prefix}Width", field("INT64", JsonPrimitive(video.width)))
                put("${prefix}Height", field("INT64", JsonPrimitive(video.height)))
            }
        }
        val assetFields = buildJsonObject {
            put("vidComplDurValue", field("INT64", JsonPrimitive(video.durationNanos)))
            put("vidComplDurScale", field("INT64", JsonPrimitive(NANOS_PER_SECOND)))
            put("vidComplDispValue", field("INT64", JsonPrimitive(video.stillTimeNanos)))
            put("vidComplDispScale", field("INT64", JsonPrimitive(NANOS_PER_SECOND)))
            put("vidComplVisibilityState", field("INT64", JsonPrimitive(0)))
            put("assetSubtypeV2", field("INT64", JsonPrimitive(LIVE_PHOTO_SUBTYPE)))
            if (withMediaGroup) put("mediaGroupId", field("STRING", JsonPrimitive(video.contentIdentifier)))
        }
        val body = buildJsonObject {
            put("zoneID", PrimarySyncZone.zoneId())
            put("atomic", true)
            putJsonArray("operations") {
                add(update(master, "CPLMaster", masterFields))
                add(update(asset, "CPLAsset", assetFields))
            }
        }
        requireCurrent(session)
        val raw = diagnostics.traced(BackupPhase.LIVE_RECORDS_MODIFY) {
            cloudKit.exchange(session, request("records/modify", body.toString(), oneShot = true), requireCurrent)
        }
        val root = runCatching { json.parseToJsonElement(raw.body) as? JsonObject }.getOrNull()
        modifyFailure(raw.code, root, withMediaGroup)?.let { throw it }
        cloudKit.requireSuccess(session, raw)
        val confirmed = root?.array("records")?.filterIsInstance<JsonObject>()?.mapNotNull { it.text("recordName") }?.toSet()
        if (confirmed != setOf(master.text("recordName"), asset.text("recordName"))) {
            malformed("Apple did not confirm the Live Photo")
        }
    }

    /** Why a records/modify answered with [code] and [root] failed, or null when it may have gone through. */
    private fun modifyFailure(code: Int, root: JsonObject?, withMediaGroup: Boolean): Exception? {
        val codes = root?.let(CloudKitErrors::serverErrorCodes).orEmpty()
        val refusedField = code == HTTP_BAD_REQUEST || codes.any { it in FIELD_ERRORS }
        val retryable = codes.isNotEmpty() && codes.all { it in RETRYABLE_RECORD_ERRORS }
        return when {
            CloudKitErrors.quotaExceeded(code, root) -> ICloudStorageFullException()
            root != null && CloudKitErrors.throttle(root) != null -> CloudKitErrors.throttle(root)
            withMediaGroup && refusedField -> MediaGroupRefused()
            code == HTTP_BAD_REQUEST || code == HTTP_NOT_FOUND ->
                LivePhotoAttachRefusedException("Apple refused the Live Photo (HTTP $code)", "HTTP_$code")
            // Someone changed a record between the read and the write: read again and retry later.
            retryable -> AppleProtocolException(AppleProtocolError.NETWORK, "The iCloud photo changed while the Live Photo was attached.")
            codes.isNotEmpty() -> codes.first { it !in RETRYABLE_RECORD_ERRORS }
                .let { LivePhotoAttachRefusedException("Apple refused the Live Photo ($it)", it) }
            else -> null
        }
    }

    private fun update(record: JsonObject, type: String, fields: JsonObject) = buildJsonObject {
        put("operationType", "update")
        putJsonObject("record") {
            put("recordName", record.text("recordName")!!)
            put("recordType", type)
            put("recordChangeTag", record.text("recordChangeTag")!!)
            put("fields", fields)
        }
    }

    private fun field(type: String, value: JsonElement) = buildJsonObject { put("value", value); put("type", type) }

    private suspend fun lookup(names: List<String>, desiredKeys: List<String>?): List<JsonObject> {
        val root = post("records/lookup", PrimarySyncZone.lookupBody(names, desiredKeys))
        return root.array("records").filterIsInstance<JsonObject>()
    }

    private suspend fun post(path: String, body: JsonObject): JsonObject {
        requireCurrent(session)
        val response = cloudKit.send(session, request(path, body.toString(), oneShot = false), verify = requireCurrent)
        val root = runCatching { json.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
            ?: malformed("Apple returned an invalid Photos response")
        requestFailure(response.code, root)?.let { throw it }
        return root
    }

    /** Why a read or asset request CloudKit answered with [code] and [root] failed, or null when it did not. */
    private fun requestFailure(code: Int, root: JsonObject): Exception? = when {
        CloudKitErrors.quotaExceeded(code, root) -> ICloudStorageFullException()
        CloudKitErrors.throttle(root) != null -> CloudKitErrors.throttle(root)
        CloudKitErrors.rejectedRequest(root) -> {
            val error = root.serverErrorCode() ?: root.array("errors").filterIsInstance<JsonObject>().firstNotNullOfOrNull { it.serverErrorCode() }
            LivePhotoAttachRefusedException("Apple refused the Live Photo request ($error)", error)
        }
        else -> null
    }

    private fun request(path: String, body: String, oneShot: Boolean) = AppleHttpRequest(
        "POST",
        cloudKit.withSessionQuery(cloudKit.databaseUrl(session, "private", path), session),
        mapOf("Content-Type" to AppleClientProfile.WEB_SERVICE_CONTENT_TYPE),
        body,
        oneShot = oneShot,
    )

    private fun attachmentOf(saved: JsonObject): LivePhotoAttachment {
        val receipt = PhotosUpload.receiptOf((saved["receipts"] as? JsonObject)?.get(ORIGINAL_VIDEO_RES) as? JsonObject)
            ?: malformed("The saved Live Photo attach has no receipt")
        return LivePhotoAttachment(receipt, (saved["mediaGroupId"] as? JsonPrimitive)?.booleanOrNull != false)
    }

    private fun malformed(message: String): Nothing = throw AppleProtocolException(AppleProtocolError.MALFORMED_RESPONSE, message)

    /** CloudKit refused the record over a field; sent once more without mediaGroupId. */
    private class MediaGroupRefused : RuntimeException()

    companion object {
        const val ORIGINAL_VIDEO_RES = "resOriginalVidComplRes"

        /** The master's video resources: the original and the two smaller ones iCloud.com plays. */
        val RESOURCE_PREFIXES = listOf("resOriginalVidCompl", "resVidMed", "resVidSmall")

        /** The same resources as diagnostics name them. */
        private val RESOURCE_NAMES = listOf("original", "medium", "small")

        /**
         * The file type the proven attach used. The bytes are the motion photo's own MP4: MediaMuxer cannot write a
         * QuickTime container, and an MP4 is the same ISO base media format, but this pairing is unverified on a device.
         */
        const val VIDEO_FILE_TYPE = "com.apple.quicktime-movie"
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val LIVE_PHOTO_SUBTYPE = 2

        /** How long saved video receipts are reused; older ones are uploaded again, since Apple's lifetime is unknown. */
        const val RECEIPT_LIFETIME_MILLIS = 15 * 60 * 1_000L

        const val UPLOADING = "LIVE_UPLOADING"
        const val RECEIPTS_SAVED = "LIVE_RECEIPTS_SAVED"
        const val MODIFYING = "LIVE_MODIFYING"
        const val MODIFIED = "LIVE_MODIFIED"

        private val RECEIPT_KEYS = listOf("fileChecksum", "wrappingKey", "referenceChecksum", "receipt")
        private val FIELD_ERRORS = setOf("BAD_REQUEST", "INVALID_ARGUMENTS")
        private val RETRYABLE_RECORD_ERRORS = setOf("CONFLICT", "ATOMIC_ERROR", "ZONE_BUSY", "TRY_AGAIN_LATER", "THROTTLED")
        private val CONTENT_HOSTS = listOf("icloud-content.com", "icloud-content.com.cn")
        private val HTTP_SUCCESS = 200..299
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404

        fun journalKey(attachId: String) = "live:$attachId"
    }
}
