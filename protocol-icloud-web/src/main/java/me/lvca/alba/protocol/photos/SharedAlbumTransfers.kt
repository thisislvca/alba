package me.lvca.alba.protocol.photos

import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import me.lvca.alba.engine.model.OneShotUploadSource
import me.lvca.alba.engine.model.UploadAcceptance
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.network.AppleHttpTransport
import me.lvca.alba.protocol.photos.SharedAlbumJournal.Companion.data
import me.lvca.alba.protocol.text
import java.util.UUID

/**
 * Moves photos between the library and shared albums: server-side copies in both directions and
 * uploads of new files. Every step is journaled so a lost response resumes instead of repeating.
 */
internal class SharedAlbumTransfers(
    private val transport: AppleHttpTransport,
    private val client: CloudKitClient,
    private val connection: SharedAlbumSession,
    private val ids: SharedAlbumIds,
    private val journal: SharedAlbumJournal,
    private val account: () -> String,
    private val authorized: () -> Boolean,
    private val discover: suspend () -> List<SharedAlbumRecord>,
    private val fresh: suspend (String) -> SharedAlbumRecord,
    private val legacy: LegacySharedAlbums,
    private val modern: ModernSharedAlbums,
) {
    /** Server-side copy into a modern album, never a relation to a PrimarySync original or cleanup proof. */
    suspend fun contribute(id: String, mediaIds: List<String>, operationId: String) {
        UUID.fromString(operationId)
        require(mediaIds.size in 1..50)
        val prefix = "icloud:${account()}:"
        val names = mediaIds.distinct().map { require(it.startsWith(prefix)); it.removePrefix(prefix).also { n -> require(n.isNotBlank() && ':' !in n) } }
        val album = discover().firstOrNull { it.collection.id == id } ?: error("Shared album is no longer available.")
        require(album.collection.shared?.canContribute == true) { "You do not have permission to add photos." }
        require(!album.isLegacy) { "Use iCloud Photos to contribute to this legacy album." }
        check(authorized()) { "Sign in before adding shared photos." }
        val key = SharedAlbumJournal.key("shared-copy", "${account()}|$id|${names.sorted().joinToString(",")}")
        val old = journal.load(key)
        var data = old?.data()
        var phase = old?.phase ?: "READY"
        if (data == null || (phase == "DONE" && data.text("operationId") != operationId)) {
            data = buildJsonObject {
                put("operationId", operationId); put("batchId", UUID.randomUUID().toString()); put("target", id); put("timestamp", System.currentTimeMillis())
            }
            phase = "READY"; journal.save(key, phase, data)
        }
        check(data.text("target") == id)
        if (phase == "DONE") return
        var pending: JsonObject = data
        val batchId = requireNotNull(pending.text("batchId"))
        if (phase == "STARTING") error("The contribution result is uncertain. Check the album in iCloud before adding these photos again.")
        if (phase == "READY") {
            val source = primaryZoneId()
            journal.save(key, "STARTING", pending)
            val response = connection.ck("private", "records/copy/start", buildJsonObject {
                put("sourceZoneID", source); put("targetZoneID", album.zoneId()); put("targetShareName", "cloudkit.zoneshare")
                put("batchId", batchId); put("includeRecords", JsonArray(names.map(::JsonPrimitive)))
            }, write = true)
            pending = JsonObject(pending + ("jobID" to JsonPrimitive(requireNotNull(response.text("jobID")))))
            journal.save(key, "POLLING", pending); phase = "POLLING"
        }
        if (phase == "POLLING") {
            awaitCopy(requireNotNull(pending.text("jobID")),
                failed = "Apple has not confirmed this shared contribution. Check the album before retrying.",
                stillProcessing = "Shared photos are still processing. Retry to check the same job.")
            journal.save(key, "POSTING", pending)
        }
        // A stable post record name makes the last step recoverable after response loss.
        val existing = modern.lookup(album, batchId)
        if (existing.text("serverErrorCode") == "NOT_FOUND") {
            modern.modify(album, "create", post(batchId, pending.getValue("timestamp")))
        } else {
            check(existing.text("recordType") == "CPLPost" && existing["serverErrorCode"] == null) { "Could not confirm the shared post." }
        }
        journal.save(key, "DONE", pending)
    }

    /** Copies a modern album's [asset] into the personal library, journaled under the action [key]. */
    suspend fun copyToLibrary(album: SharedAlbumRecord, asset: String, key: String, data: JsonObject) {
        val old = journal.load(key)
        var pending = old?.data() ?: data
        if (old?.phase == "DONE") return
        if (old?.phase != "POLLING") {
            require(modern.readAlbum(album).any { it.assetRecordName == asset })
            val target = primaryZoneId()
            journal.save(key, "SENDING", pending)
            val result = connection.ck("private", "records/copy/start", buildJsonObject {
                put("sourceZoneID", album.zoneId()); put("targetZoneID", target); put("sourceShareName", "cloudkit.zoneshare")
                putJsonArray("includeRecords") { add(asset) }
            }, write = true)
            pending = JsonObject(pending + ("jobID" to JsonPrimitive(requireNotNull(result.text("jobID")))))
            journal.save(key, "POLLING", pending)
        }
        awaitCopy(requireNotNull(pending.text("jobID")),
            failed = "Apple could not finish the copy. Check the album before retrying.",
            stillProcessing = "The copy is still processing. Retry to check the same job.")
        journal.save(key, "DONE", pending)
    }

    /** Saves a legacy album's photo to the library by uploading its original again. */
    suspend fun saveLegacyPhoto(mediaId: String, fileName: String, source: OneShotUploadSource) {
        val album = fresh(ids.albumOfPhoto(mediaId))
        require(album.isLegacy)
        require(legacy.readAlbum(album).any { it.assetRecordName == ids.recordName(album, mediaId) })
        val current = connection.bound()
        val root = requireNotNull(current.webservices["photosupload"])
        val key = SharedAlbumJournal.key("shared-save", "${account()}|$mediaId|${source.sha256Hex}")
        val accepted = upload(current, root, fileName, source).startOnce(key)
        accepted.uploadJobId?.let { awaitUpload(root, it, failed = "Apple could not process this upload.") }
    }

    /** Uploads a new file into a modern album as its own post. */
    suspend fun uploadToModern(id: String, fileName: String, source: OneShotUploadSource) {
        val album = fresh(id)
        require(album.collection.shared?.canContribute == true)
        val current = connection.bound()
        val root = requireNotNull(current.webservices["photosupload"])
        val key = SharedAlbumJournal.key("shared-direct", "${account()}|$id|$fileName|${source.sha256Hex}")
        val batch = "CPLPost-" + UUID.nameUUIDFromBytes(key.toByteArray()).toString()
        val accepted: UploadAcceptance = upload(current, root, fileName, source, zoneName = album.zone, assetBatchId = batch).startOnce(key)
        accepted.uploadJobId?.let { awaitUpload(root, it, failed = "Apple could not process this shared upload.") }
        require(fresh(id).collection.shared?.canContribute == true)
        val existing = modern.lookup(album, batch)
        if (existing.text("serverErrorCode") == "NOT_FOUND") {
            modern.modify(album, "create", post(batch, JsonPrimitive(System.currentTimeMillis())))
        } else {
            check(existing.text("recordType") == "CPLPost")
        }
    }

    suspend fun uploadToLegacy(id: String, fileName: String, source: OneShotUploadSource, operationId: String) {
        LegacySharedUpload(transport, account, authorized, journal, connection, client::persist, discover, legacy::readAlbum)
            .upload(id, fileName, source, operationId)
    }

    private fun upload(
        session: AppleSessionSnapshot,
        root: String,
        fileName: String,
        source: OneShotUploadSource,
        zoneName: String = PrimarySyncZone.NAME,
        assetBatchId: String? = null,
    ) = PhotosUpload(transport, session, root.trimEnd('/'), fileName, source,
        { response -> connection.checkSession(session); client.persist(session, response) }, journal.store(), journal.binding(),
        checkAuthorized = { connection.checkSession(session); check(authorized()) }, zoneName = zoneName, assetBatchId = assetBatchId,
        onSessionRejected = client::reject)

    private suspend fun primaryZoneId(): JsonObject =
        connection.ck("private", "zones/list", buildJsonObject {})["zones"]!!.jsonArray
            .map { it.jsonObject["zoneID"]!!.jsonObject }.first { it.text("zoneName") == PrimarySyncZone.NAME }

    private fun post(recordName: String, timestamp: JsonElement) = buildJsonObject {
        put("recordType", "CPLPost"); put("recordName", recordName)
        putJsonObject("fields") { putJsonObject("postTimestamp") { put("value", timestamp) } }
    }

    private suspend fun awaitCopy(jobId: String, failed: String, stillProcessing: String) {
        repeat(POLL_ATTEMPTS) {
            when (connection.ck("private", "records/copy/status", buildJsonObject { put("jobID", jobId) }).text("status")) {
                "COMPLETED" -> return
                "SUBMITTED", "RUNNING", "PROCESSING" -> delay(POLL_INTERVAL_MILLIS)
                else -> error(failed)
            }
        }
        error(stillProcessing)
    }

    private suspend fun awaitUpload(root: String, jobId: String, failed: String) {
        repeat(POLL_ATTEMPTS) {
            val response = connection.request("${root.trimEnd('/')}/photosupload/uploadStatus", buildJsonObject { putJsonArray("uploadJobIds") { add(jobId) } })
            val status = response[jobId]?.jsonObject ?: error("Upload status unavailable. Retry to check again.")
            check(status["errorCode"] == null) { failed }
            if (status.text("progress") == "100") return
            delay(POLL_INTERVAL_MILLIS)
        }
        error("Upload is still processing. Retry to check the same upload.")
    }

    private companion object {
        const val POLL_ATTEMPTS = 20
        const val POLL_INTERVAL_MILLIS = 1000L
    }
}
