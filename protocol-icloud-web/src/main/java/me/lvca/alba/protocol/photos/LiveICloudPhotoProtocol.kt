package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.CandidatePage
import me.lvca.alba.engine.model.ICloudDestination
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.OneShotUploadSource
import me.lvca.alba.engine.model.PreparedICloudUpload
import me.lvca.alba.engine.model.RemoteOriginalObservation
import me.lvca.alba.engine.model.RemotePairCandidate
import me.lvca.alba.engine.model.RemoteRecordRef
import me.lvca.alba.engine.model.UploadAcceptance
import me.lvca.alba.engine.model.UploadProcessingStatus
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.array
import me.lvca.alba.protocol.auth.AppleProtocolError
import me.lvca.alba.protocol.auth.AppleProtocolException
import me.lvca.alba.protocol.boolean
import me.lvca.alba.protocol.fieldObject
import me.lvca.alba.protocol.isTombstone
import me.lvca.alba.protocol.long
import me.lvca.alba.protocol.network.AppleClientProfile
import me.lvca.alba.protocol.network.AppleClientProfile.addUploadClientBuild
import me.lvca.alba.protocol.network.AppleEndpointPolicy
import me.lvca.alba.protocol.network.AppleHttpRequest
import me.lvca.alba.protocol.network.AppleHttpResponse
import me.lvca.alba.protocol.network.AppleHttpTransport
import me.lvca.alba.protocol.obj
import me.lvca.alba.protocol.text
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.OutputStream

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

    private val cloudKit = CloudKitClient(transport, sessionProvider, onSessionUpdated, onSessionRejected)

    override fun currentDestination(): ICloudDestination? = sessionProvider()?.let { session ->
        ICloudDestination(
            accountId = ICloudAccountIdentity.accountId(session),
            authSessionId = ICloudAccountIdentity.stableId(session.dsid, session.clientId),
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
            .firstOrNull { it.obj("zoneID")?.text("zoneName") == PrimarySyncZone.NAME }
            ?: throw unavailable("Apple did not return the Personal Library zone")
        return zone.text("syncToken")
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
            persistSession = { cloudKit.persist(session, it) },
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
            .addUploadClientBuild()
            .build().toString()
        val response = executeRead(session, AppleHttpRequest(
            method = "POST",
            url = url,
            headers = mapOf("Content-Type" to AppleClientProfile.WEB_SERVICE_CONTENT_TYPE),
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
                body = PrimarySyncZone.changesBody(afterChangeToken, limit).toString(),
            ),
        ).jsonObject("Apple returned invalid Photos changes")
        val zone = response.array("zones").firstOrNull() as? JsonObject
            ?: throw unavailable("Apple returned no Personal Library changes")
        val changedRecords = zone.array("records").mapNotNull { it as? JsonObject }
        val changedAssets = changedRecords.filter {
            it.text("recordType") == ASSET_RECORD_TYPE && !it.isTombstone()
        }
        val changedCandidateNames = linkedSetOf<Pair<String, String>>()
        val unresolvedAssetNames = changedAssets.mapNotNull { asset ->
            asset.text("recordName")
                ?.takeIf { asset.fieldObject("masterRef")?.text("recordName") == null }
        }
        val resolvedAssetsByName = lookupRecordsInBatches(session, unresolvedAssetNames)
            .asSequence()
            .filter { it.text("recordType") == ASSET_RECORD_TYPE }
            .mapNotNull { asset -> asset.text("recordName")?.let { it to asset } }
            .toMap()

        changedAssets.forEach { asset ->
            val assetName = asset.text("recordName") ?: return@forEach
            val masterName = asset.fieldObject("masterRef")?.text("recordName")
                ?: resolvedAssetsByName[assetName]
                    ?.fieldObject("masterRef")
                    ?.text("recordName")
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
            RecordIdentity(record.text("recordType"), record.text("recordName"))
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
            nextChangeToken = zone.text("syncToken"),
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
        val master = records.first { it.text("recordType") == MASTER_RECORD_TYPE }
        val downloadUrl = master.fieldObject(ORIGINAL_RESOURCE_KEY)?.text("downloadURL")
            ?: throw unavailable("The iCloud original resource is unavailable")
        AppleEndpointPolicy.requireAllowed(downloadUrl)
        val counting = CountingOutputStream(output)
        transport.stream(downloadUrl, counting)
        cloudKit.persist(session, null)
        return RemoteOriginalObservation(hydrated.originalResourceIdentity, counting.byteCount)
    }

    private fun parseHydratedPair(
        records: List<JsonObject>,
        masterName: String,
        assetName: String,
    ): RemotePairCandidate? = parseHydratedPair(
        records.associateBy { record ->
            RecordIdentity(record.text("recordType"), record.text("recordName"))
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
        val masterTag = master.text("recordChangeTag") ?: return null
        val assetTag = asset.text("recordChangeTag") ?: return null
        val relation = asset.fieldObject("masterRef")?.text("recordName") == masterName
        val masterDeleted = master.isTombstone()
        val assetDeleted = asset.isTombstone()
        val resource = master.fieldObject(ORIGINAL_RESOURCE_KEY) ?: return null
        val resourceFingerprint = listOfNotNull(
            resource.text("fileChecksum"),
            resource.text("referenceChecksum"),
            resource.long("size")?.toString(),
            resource.text("wrappingKey"),
        ).joinToString(":")
        val resourceIdentity = ICloudAccountIdentity.stableId(
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
                body = PrimarySyncZone.lookupBody(recordNames.distinct(), LOOKUP_KEYS).toString(),
            ),
        ).jsonObject("Apple returned an invalid Photos record lookup")
        return response.array("records").mapNotNull { it as? JsonObject }
    }

    private suspend fun lookupRecordsInBatches(
        session: AppleSessionSnapshot,
        recordNames: List<String>,
    ): List<JsonObject> = PrimarySyncZone.lookupInBatches(recordNames, RECORD_LOOKUP_BATCH_SIZE) { lookupRecords(session, it) }

    private suspend fun executeRead(session: AppleSessionSnapshot, request: AppleHttpRequest): AppleHttpResponse =
        cloudKit.send(session, request, verify = ::requireCurrentSession)

    private fun requireBoundSession(binding: AccountBinding): AppleSessionSnapshot {
        if (!isAuthorized()) throw AppleProtocolException(
            AppleProtocolError.SESSION_EXPIRED,
            "Reconnect and verify the iCloud session before changing or verifying photos.",
        )
        val session = sessionProvider()
            ?: throw AppleProtocolException(AppleProtocolError.SESSION_EXPIRED, "Sign in to iCloud first.")
        require(ICloudAccountIdentity.accountId(session) == binding.accountId) { "The iCloud destination account changed" }
        cloudKit.bind(session)
        return session
    }

    private fun requireCurrentSession(session: AppleSessionSnapshot) {
        val current = sessionProvider()
        if (!isAuthorized() || current == null || current.dsid != session.dsid || current.clientId != session.clientId) {
            throw AppleProtocolException(AppleProtocolError.SESSION_EXPIRED,
                "The operation requires its verified iCloud session.")
        }
    }

    private fun cloudKitUrl(session: AppleSessionSnapshot, path: String): String =
        cloudKit.withSessionQuery(cloudKit.databaseUrl(session, "private", path), session)

    private fun AppleHttpResponse.jsonObject(message: String): JsonObject = runCatching {
        json.parseToJsonElement(body) as? JsonObject
    }.getOrNull() ?: throw AppleProtocolException(AppleProtocolError.MALFORMED_RESPONSE, message)

    private fun safeFileName(value: String): String {
        val cleaned = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .ifBlank { "original" }
        if (cleaned.length <= 160) return cleaned
        val extension = cleaned.substringAfterLast('.', "").takeIf { it.length in 1..12 }
        return if (extension == null) cleaned.take(160)
        else cleaned.substringBeforeLast('.').take(159 - extension.length) + "." + extension
    }

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
        const val UPLOAD_SERVICE = "photosupload"
        const val MASTER_RECORD_TYPE = "CPLMaster"
        const val ASSET_RECORD_TYPE = "CPLAsset"
        const val ORIGINAL_RESOURCE_KEY = "resOriginalRes"
        const val RECORD_LOOKUP_BATCH_SIZE = 100
        val JSON_HEADERS = mapOf("Content-Type" to AppleClientProfile.WEB_SERVICE_CONTENT_TYPE)
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
