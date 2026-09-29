package dev.mela.protocol.photos

import dev.mela.engine.model.AccountBinding
import dev.mela.engine.model.CandidatePage
import dev.mela.engine.model.ICloudDestination
import dev.mela.engine.model.ICloudPhotoProtocol
import dev.mela.engine.model.OneShotUploadSource
import dev.mela.engine.model.PreparedICloudUpload
import dev.mela.engine.model.RemoteOriginalObservation
import dev.mela.engine.model.RemotePairCandidate
import dev.mela.engine.model.RemoteRecordRef
import dev.mela.engine.model.UploadProcessingStatus
import dev.mela.engine.model.UploadAcceptance
import dev.mela.protocol.account.AppleSessionSnapshot
import dev.mela.protocol.auth.AppleProtocolError
import dev.mela.protocol.auth.AppleProtocolException
import dev.mela.protocol.network.AppleEndpointPolicy
import dev.mela.protocol.network.AppleHttpRequest
import dev.mela.protocol.network.AppleHttpResponse
import dev.mela.protocol.network.AppleHttpTransport
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

class LiveICloudPhotoProtocol(
    private val transport: AppleHttpTransport,
    private val sessionProvider: () -> AppleSessionSnapshot?,
    private val onSessionUpdated: suspend (AppleSessionSnapshot) -> Unit = {},
    private val uploadJournal: UploadJournal? = null,
    private val isAuthorized: () -> Boolean = { true },
    private val onSessionRejected: suspend (AppleSessionSnapshot) -> Unit = {},
) : ICloudPhotoProtocol {
    override fun isWriteAuthorized(): Boolean = isAuthorized()
    override suspend fun canResume(binding: AccountBinding, attemptId: String): Boolean = try {
        uploadJournal?.load(binding, attemptId)?.phase in PhotosUpload.RESUMABLE
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
      catch (_: Exception) { false }
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var boundAuthentication: String? = null

    override fun currentDestination(): ICloudDestination? = sessionProvider()?.let { session ->
        ICloudDestination(
            accountId = accountId(session),
            authSessionId = stableId(session.dsid, session.clientId),
            label = session.accountName,
        )
    }

    override suspend fun captureChangeToken(binding: AccountBinding): String {
        val session = requireBoundSession(binding)
        val response = executeRead(
            session,
            AppleHttpRequest(
                method = "POST",
                url = cloudKitUrl(session, "/zones/list"),
                headers = JSON_HEADERS,
                body = "{}",
            ),
        ).jsonObject("Apple returned an invalid Photos zone response")
        val zone = response.array("zones")
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.obj("zoneID")?.string("zoneName") == PRIMARY_ZONE_NAME }
            ?: throw unavailable("Apple did not return the Personal Library zone")
        return zone.string("syncToken")
            ?: throw unavailable("Apple did not return a Photos change token")
    }

    override suspend fun prepareOriginal(
        binding: AccountBinding,
        fileName: String,
        source: OneShotUploadSource,
        lastModifiedAtEpochMillis: Long?,
    ): PreparedICloudUpload {
        val session = requireBoundSession(binding)
        val root = session.webservices[UPLOAD_SERVICE]
            ?: throw unavailable("This iCloud session does not expose photo uploads")
        val rootUrl = AppleEndpointPolicy.requireAllowed(root)
        require(rootUrl.host.contains("photosupload", ignoreCase = true)) {
            "Apple returned an unexpected upload service host"
        }
        return PhotosUpload(
            transport = transport,
            session = session,
            serviceRoot = root.trimEnd('/'),
            fileName = safeFileName(fileName),
            source = source,
            lastModifiedAtEpochMillis = lastModifiedAtEpochMillis,
            persistSession = { persistTransportState(session) },
            journal = uploadJournal,
            binding = binding,
            onSessionRejected = onSessionRejected,
            checkAuthorized = {
                val current = sessionProvider()
                if (!isAuthorized() || current == null || current.dsid != session.dsid || current.clientId != session.clientId) {
                    throw AppleProtocolException(AppleProtocolError.SESSION_EXPIRED,
                        "The prepared upload requires its verified iCloud session.")
                }
            },
        )
    }

    override suspend fun uploadProcessingStatus(
        binding: AccountBinding,
        acceptance: UploadAcceptance,
    ): UploadProcessingStatus {
        val session = requireBoundSession(binding)
        val jobId = acceptance.uploadJobId?.takeIf(String::isNotBlank)
            ?: return UploadProcessingStatus.Unknown
        if (acceptance.duplicateHint == true) return UploadProcessingStatus.Unknown
        val root = session.webservices[UPLOAD_SERVICE]
            ?: throw unavailable("This iCloud session does not expose photo uploads")
        require(AppleEndpointPolicy.requireAllowed(root).host.contains("photosupload", ignoreCase = true)) {
            "Apple returned an unexpected upload service host"
        }
        val url = "${root.trimEnd('/')}/photosupload/uploadStatus".toHttpUrl().newBuilder()
            .addQueryParameter("dsid", session.dsid)
            .addQueryParameter("clientId", session.clientId)
            .addQueryParameter("clientBuildNumber", "2634Build25")
            .addQueryParameter("clientMasteringNumber", "2634Build25")
            .build().toString()
        val response = executeRead(session, AppleHttpRequest(
            method = "POST",
            url = url,
            headers = mapOf("Content-Type" to "text/plain;charset=UTF-8"),
            body = buildJsonObject {
                putJsonArray("uploadJobIds") { add(JsonPrimitive(jobId)) }
            }.toString(),
        )).jsonObject("Apple returned an invalid upload status response")
        val value = response[jobId] ?: return UploadProcessingStatus.Unknown
        fun malformed(): Nothing = throw AppleProtocolException(
            AppleProtocolError.MALFORMED_RESPONSE, "Apple returned an invalid upload job status",
        )
        val job = value as? JsonObject ?: malformed()
        job["errorCode"]?.let { error ->
            val primitive = error as? JsonPrimitive ?: malformed()
            if (primitive.isString) malformed()
            return UploadProcessingStatus.Failed(primitive.intOrNull ?: malformed())
        }
        val progress = job["progress"]?.let { value ->
            val primitive = value as? JsonPrimitive ?: malformed()
            if (primitive.isString) malformed()
            primitive.intOrNull?.takeIf { it in 0..100 } ?: malformed()
        } ?: malformed()
        return if (progress == 100) UploadProcessingStatus.Complete
        else UploadProcessingStatus.Processing(progress)
    }

    override suspend fun findUploadCandidates(
        binding: AccountBinding,
        afterChangeToken: String,
        acceptance: UploadAcceptance?,
        limit: Int,
    ): CandidatePage {
        require(limit in 1..500)
        val session = requireBoundSession(binding)
        val candidateNames = linkedSetOf<Pair<String, String>>()

        val acceptedMaster = acceptance?.masterRecordName
        val acceptedAsset = acceptance?.assetRecordName
        if (acceptedMaster != null && acceptedAsset != null) {
            candidateNames.add(acceptedMaster to acceptedAsset)
        }

        val response = executeRead(
            session,
            AppleHttpRequest(
                method = "POST",
                url = cloudKitUrl(session, "/changes/zone"),
                headers = JSON_HEADERS,
                body = changesBody(afterChangeToken, limit),
            ),
        ).jsonObject("Apple returned invalid Photos changes")
        val zone = response.array("zones").firstOrNull() as? JsonObject
            ?: throw unavailable("Apple returned no Personal Library changes")
        val changedRecords = zone.array("records").mapNotNull { it as? JsonObject }
        val changedAssets = changedRecords.filter {
            it.string("recordType") == ASSET_RECORD_TYPE && !it.recordDeleted()
        }
        val changedCandidateNames = linkedSetOf<Pair<String, String>>()
        val unresolvedAssetNames = changedAssets.mapNotNull { asset ->
            asset.string("recordName")
                ?.takeIf { asset.fieldObject("masterRef")?.string("recordName") == null }
        }
        val resolvedAssetsByName = lookupRecordsInBatches(session, unresolvedAssetNames)
            .asSequence()
            .filter { it.string("recordType") == ASSET_RECORD_TYPE }
            .mapNotNull { asset -> asset.string("recordName")?.let { it to asset } }
            .toMap()

        changedAssets.forEach { asset ->
            val assetName = asset.string("recordName") ?: return@forEach
            val masterName = asset.fieldObject("masterRef")?.string("recordName")
                ?: resolvedAssetsByName[assetName]
                    ?.fieldObject("masterRef")
                    ?.string("recordName")
            if (masterName != null) {
                val names = masterName to assetName
                candidateNames += names
                changedCandidateNames += names
            }
        }

        val namesToHydrate = buildList(candidateNames.size * 2) {
            candidateNames.forEach { (masterName, assetName) ->
                add(masterName)
                add(assetName)
            }
        }
        val hydratedRecords = lookupRecordsInBatches(session, namesToHydrate).associateBy { record ->
            RecordIdentity(record.string("recordType"), record.string("recordName"))
        }
        val candidates = candidateNames.mapNotNull { names ->
            val (masterName, assetName) = names
            val hydrated = parseHydratedPair(hydratedRecords, masterName, assetName)
            if (hydrated == null && names in changedCandidateNames) {
                throw unavailable("Apple returned an incomplete changed photo pair")
            }
            hydrated
        }
        return CandidatePage(
            candidates = candidates,
            nextChangeToken = zone.string("syncToken"),
            moreComing = zone.boolean("moreComing") ?: false,
        )
    }

    override suspend fun streamFreshOriginal(
        binding: AccountBinding,
        pair: RemotePairCandidate,
        output: OutputStream,
    ): RemoteOriginalObservation {
        val session = requireBoundSession(binding)
        val records = lookupRecords(session, listOf(pair.master.name, pair.asset.name))
        val hydrated = parseHydratedPair(records, pair.master.name, pair.asset.name)
            ?: throw unavailable("The complete iCloud photo pair is unavailable")
        require(hydrated.relationIsCurrent && !hydrated.masterDeleted && !hydrated.assetDeleted) {
            "The iCloud photo pair is no longer current"
        }
        require(hydrated.originalResourceIdentity == pair.originalResourceIdentity) {
            "The iCloud original changed before verification"
        }
        val master = records.first { it.string("recordType") == MASTER_RECORD_TYPE }
        val downloadUrl = master.fieldObject(ORIGINAL_RESOURCE_KEY)?.string("downloadURL")
            ?: throw unavailable("The iCloud original resource is unavailable")
        AppleEndpointPolicy.requireAllowed(downloadUrl)
        val counting = CountingOutputStream(output)
        transport.stream(downloadUrl, counting)
        persistTransportState(session)
        return RemoteOriginalObservation(hydrated.originalResourceIdentity, counting.byteCount)
    }

    private fun parseHydratedPair(
        records: List<JsonObject>,
        masterName: String,
        assetName: String,
    ): RemotePairCandidate? = parseHydratedPair(
        records.associateBy { record ->
            RecordIdentity(record.string("recordType"), record.string("recordName"))
        },
        masterName,
        assetName,
    )

    private fun parseHydratedPair(
        records: Map<RecordIdentity, JsonObject>,
        masterName: String,
        assetName: String,
    ): RemotePairCandidate? {
        val master = records[RecordIdentity(MASTER_RECORD_TYPE, masterName)] ?: return null
        val asset = records[RecordIdentity(ASSET_RECORD_TYPE, assetName)] ?: return null
        val masterTag = master.string("recordChangeTag") ?: return null
        val assetTag = asset.string("recordChangeTag") ?: return null
        val relation = asset.fieldObject("masterRef")?.string("recordName") == masterName
        val masterDeleted = master.recordDeleted()
        val assetDeleted = asset.recordDeleted()
        val resource = master.fieldObject(ORIGINAL_RESOURCE_KEY) ?: return null
        val resourceFingerprint = listOfNotNull(
            resource.string("fileChecksum"),
            resource.string("referenceChecksum"),
            resource.long("size")?.toString(),
            resource.string("wrappingKey"),
        ).joinToString(":")
        val resourceIdentity = stableId(
            masterName,
            masterTag,
            assetName,
            assetTag,
            resourceFingerprint,
        )
        return RemotePairCandidate(
            master = RemoteRecordRef(masterName, masterTag),
            asset = RemoteRecordRef(assetName, assetTag),
            relationIsCurrent = relation,
            masterDeleted = masterDeleted,
            assetDeleted = assetDeleted,
            originalResourceIdentity = resourceIdentity,
        )
    }

    private suspend fun lookupRecords(
        session: AppleSessionSnapshot,
        recordNames: List<String>,
    ): List<JsonObject> {
        val response = executeRead(
            session,
            AppleHttpRequest(
                method = "POST",
                url = cloudKitUrl(session, "/records/lookup"),
                headers = JSON_HEADERS,
                body = buildJsonObject {
                    putJsonArray("records") {
                        recordNames.distinct().forEach { name ->
                            add(buildJsonObject { put("recordName", name) })
                        }
                    }
                    putJsonObject("zoneID") {
                        put("zoneName", PRIMARY_ZONE_NAME)
                        put("zoneType", PRIMARY_ZONE_TYPE)
                    }
                    putJsonArray("desiredKeys") {
                        LOOKUP_KEYS.forEach { add(JsonPrimitive(it)) }
                    }
                }.toString(),
            ),
        ).jsonObject("Apple returned an invalid Photos record lookup")
        return response.array("records").mapNotNull { it as? JsonObject }
    }

    private suspend fun lookupRecordsInBatches(
        session: AppleSessionSnapshot,
        recordNames: List<String>,
    ): List<JsonObject> = buildList {
        recordNames.distinct().chunked(RECORD_LOOKUP_BATCH_SIZE).forEach { names ->
            addAll(lookupRecords(session, names))
        }
    }

    private fun changesBody(token: String, limit: Int): String = buildJsonObject {
        putJsonArray("zones") {
            add(buildJsonObject {
                putJsonObject("zoneID") {
                    put("zoneName", PRIMARY_ZONE_NAME)
                    put("zoneType", PRIMARY_ZONE_TYPE)
                }
                put("syncToken", token)
                put("reverse", false)
            })
        }
        put("resultsLimit", limit)
    }.toString()

    private suspend fun executeRead(
        session: AppleSessionSnapshot,
        request: AppleHttpRequest,
    ): AppleHttpResponse {
        val response = transport.execute(request)
        requireCurrentSession(session)
        persistTransportState(session)
        if (response.code !in 200..299) {
            if (response.code == 401 || response.code == 403) onSessionRejected(session)
            throw AppleProtocolException(
                if (response.code == 401 || response.code == 403) {
                    AppleProtocolError.SESSION_EXPIRED
                } else {
                    AppleProtocolError.NETWORK
                },
                "Apple Photos returned HTTP ${response.code}.",
            )
        }
        return response
    }

    private fun requireBoundSession(binding: AccountBinding): AppleSessionSnapshot {
        if (!isAuthorized()) throw AppleProtocolException(
            AppleProtocolError.SESSION_EXPIRED,
            "Reconnect and verify the iCloud session before changing or verifying photos.",
        )
        val session = sessionProvider()
            ?: throw AppleProtocolException(AppleProtocolError.SESSION_EXPIRED, "Sign in to iCloud first.")
        require(accountId(session) == binding.accountId) { "The iCloud destination account changed" }
        val authentication = "${session.dsid}:${session.clientId}"
        if (boundAuthentication != authentication) {
            transport.restore(session)
            boundAuthentication = authentication
        }
        return session
    }

    private fun requireCurrentSession(session: AppleSessionSnapshot) {
        val current = sessionProvider()
        if (!isAuthorized() || current == null || current.dsid != session.dsid || current.clientId != session.clientId) {
            throw AppleProtocolException(AppleProtocolError.SESSION_EXPIRED,
                "The operation requires its verified iCloud session.")
        }
    }

    private suspend fun persistTransportState(session: AppleSessionSnapshot) {
        val headers = transport.sessionHeaders
        onSessionUpdated(
            session.copy(
                accountCountryCode = headers.accountCountryCode ?: session.accountCountryCode,
                sessionId = headers.sessionId ?: session.sessionId,
                sessionToken = headers.sessionToken ?: session.sessionToken,
                trustToken = headers.trustToken ?: session.trustToken,
                cookies = transport.snapshotCookies(),
            ),
        )
    }

    private fun cloudKitUrl(session: AppleSessionSnapshot, path: String): String {
        val root = session.webservices[CLOUDKIT_SERVICE]
            ?: throw unavailable("iCloud Photos is unavailable for this session")
        val rootUrl = AppleEndpointPolicy.requireAllowed(root)
        require(rootUrl.host.contains("ckdatabasews", ignoreCase = true)) {
            "Apple returned an unexpected Photos database host"
        }
        return "$root/database/1/com.apple.photos.cloud/production/private$path"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("clientBuildNumber", CLIENT_BUILD)
            .addQueryParameter("clientMasteringNumber", CLIENT_MASTERING)
            .addQueryParameter("clientId", session.clientId)
            .addQueryParameter("dsid", session.dsid)
            .addQueryParameter("remapEnums", "true")
            .build()
            .toString()
    }

    private fun AppleHttpResponse.jsonObject(message: String): JsonObject = runCatching {
        json.parseToJsonElement(body) as? JsonObject
    }.getOrNull() ?: throw AppleProtocolException(AppleProtocolError.MALFORMED_RESPONSE, message)

    private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.fieldObject(name: String): JsonObject? =
        (((this["fields"] as? JsonObject)?.get(name) as? JsonObject)?.get("value") as? JsonObject)

    private fun JsonObject.recordDeleted(): Boolean = boolean("deleted") == true ||
        string("deleted") == "true" ||
        runCatching { this["deleted"]?.jsonPrimitive?.longOrNull == 1L }.getOrDefault(false)

    private fun safeFileName(value: String): String {
        val cleaned = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .ifBlank { "original" }
        if (cleaned.length <= 160) return cleaned
        val extension = cleaned.substringAfterLast('.', "").takeIf { it.length in 1..12 }
        return if (extension == null) cleaned.take(160)
        else cleaned.substringBeforeLast('.').take(159 - extension.length) + "." + extension
    }

    private fun accountId(session: AppleSessionSnapshot): String = stableId("icloud-account", session.dsid)

    private fun stableId(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun unavailable(message: String) =
        AppleProtocolException(AppleProtocolError.PHOTOS_UNAVAILABLE, message)

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var byteCount: Long = 0
            private set

        override fun write(value: Int) {
            delegate.write(value)
            byteCount += 1
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            byteCount += length
        }

        override fun flush() = delegate.flush()
    }

    private companion object {
        const val CLOUDKIT_SERVICE = "ckdatabasews"
        const val UPLOAD_SERVICE = "photosupload"
        const val CLIENT_BUILD = "2534Project66"
        const val CLIENT_MASTERING = "2534B22"
        const val PRIMARY_ZONE_NAME = "PrimarySync"
        const val PRIMARY_ZONE_TYPE = "REGULAR_CUSTOM_ZONE"
        const val MASTER_RECORD_TYPE = "CPLMaster"
        const val ASSET_RECORD_TYPE = "CPLAsset"
        const val ORIGINAL_RESOURCE_KEY = "resOriginalRes"
        const val RECORD_LOOKUP_BATCH_SIZE = 100
        val JSON_HEADERS = mapOf("Content-Type" to "plain/text")
        val LOOKUP_KEYS = listOf(
            "filenameEnc",
            "itemType",
            "resOriginalFileType",
            "resOriginalWidth",
            "resOriginalHeight",
            ORIGINAL_RESOURCE_KEY,
            "masterRef",
            "assetDate",
        )
    }

    private data class RecordIdentity(
        val recordType: String?,
        val recordName: String?,
    )
}
