package me.lvca.alba.protocol.livecontract

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.source.KnownCloudRecords
import me.lvca.alba.engine.source.RemoteMediaRecord
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.auth.AppleAuthenticationService
import me.lvca.alba.protocol.field
import me.lvca.alba.protocol.fieldObject
import me.lvca.alba.protocol.fieldText
import me.lvca.alba.protocol.network.AppleEndpointPolicy
import me.lvca.alba.protocol.network.OkHttpAppleTransport
import me.lvca.alba.protocol.photos.CloudKitClient
import me.lvca.alba.protocol.photos.ICloudAccountIdentity
import me.lvca.alba.protocol.photos.LiveICloudCatalogSource
import me.lvca.alba.protocol.photos.LiveICloudPhotoProtocol
import me.lvca.alba.protocol.photos.PersonalLibraryDatabase
import me.lvca.alba.protocol.photos.PrimarySyncZone
import me.lvca.alba.protocol.recordName
import me.lvca.alba.protocol.recordType
import me.lvca.alba.protocol.text
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.OutputStream

/**
 * Checks, against a real iCloud account and read-only, the assumptions Alba's iCloud web protocol code makes: that the
 * saved session validates, that the web services, CloudKit zone, record types and fields Alba reads are still there and
 * parse, and that downloads, verification reads, Shared Albums and account reads still work. Each check names the
 * assumption it tests, so a failure says which one broke.
 *
 * It never writes: no uploads, edits, comments or invitations. Upload reservation (`photosupload/createUploadUrl`) is
 * left out on purpose: it is a POST that reserves space for a file on Apple's side, and nothing shows that an unused
 * reservation has no effect. Only the upload service's URL is checked.
 *
 * Opt-in only: it runs through `./gradlew :protocol-icloud-web:liveContractTest`, never in `testDebugUnitTest` or CI's
 * normal jobs, and skips when no session is configured (see [LiveSession]).
 */
class LiveContractTest {
    @Test
    fun iCloudStillMatchesWhatAlbaAssumes() = runBlocking {
        val loaded = LiveSession.load()
        assumeTrue(
            "No live iCloud session. Run ./gradlew :protocol-icloud-web:liveSignIn or set ${LiveSession.SESSION_VARIABLE}.",
            loaded != null,
        )
        val contract = LiveContract(requireNotNull(loaded).snapshot)
        val results = contract.run()
        loaded.file?.let { file -> runCatching { LiveSession.save(file, contract.latestSession) } }
        val report = render(results)
        println(report)
        val broken = results.filter { it.outcome == Outcome.FAILED }
        if (broken.isNotEmpty()) {
            fail("${broken.size} iCloud protocol assumption(s) broke:\n" +
                broken.joinToString("\n") { "- ${it.id}: ${it.assumption}\n    ${it.detail}" } + "\n\nFull report:\n$report")
        }
    }

    private fun render(results: List<CheckResult>): String = buildString {
        appendLine("Alba live iCloud contract")
        results.forEach { appendLine("${it.outcome.label} ${it.id}: ${it.assumption}\n       ${it.detail}") }
        append("${results.count { it.outcome == Outcome.PASSED }} passed, ${results.count { it.outcome == Outcome.FAILED }} failed, " +
            "${results.count { it.outcome == Outcome.SKIPPED }} skipped")
    }
}

internal enum class Outcome(val label: String) { PASSED("PASS"), FAILED("FAIL"), SKIPPED("SKIP") }

internal data class CheckResult(val id: String, val assumption: String, val outcome: Outcome, val detail: String)

/** A check that could not decide, such as a field check on an empty library. It is reported, never failed. */
private class Inconclusive(message: String) : Exception(message)

/** A broken assumption, with what was observed instead. */
private class Broken(message: String) : Exception(message)

private fun broken(message: String): Nothing = throw Broken(message)

/** Runs the read-only checks in order; a check whose prerequisite failed is skipped, not run. */
internal class LiveContract(initial: AppleSessionSnapshot) {
    @Volatile var latestSession: AppleSessionSnapshot = initial
        private set

    @Volatile private var rejected = false
    private val transport = OkHttpAppleTransport()
    private val results = mutableListOf<CheckResult>()
    private val catalog = LiveICloudCatalogSource(
        transport = transport,
        sessionProvider = { latestSession },
        isAuthorized = { true },
        onSessionUpdated = { latestSession = it },
        onSessionRejected = { rejected = true },
    )
    private val photos = LiveICloudPhotoProtocol(
        transport = transport,
        sessionProvider = { latestSession },
        onSessionUpdated = { latestSession = it },
        isAuthorized = { true },
        onSessionRejected = { rejected = true },
    )
    private val database = PersonalLibraryDatabase(
        CloudKitClient(transport, { latestSession }, { latestSession = it }, { rejected = true }),
        { latestSession },
        { true },
    )
    private val binding get() = AccountBinding(ICloudAccountIdentity.accountId(latestSession), authEpoch = 1, operationFence = 1)
    private var syncToken: String? = null
    private var firstPage: List<RemoteMediaRecord> = emptyList()

    suspend fun run(): List<CheckResult> {
        check("session.validate", "setup.icloud.com validate (or Apple's token login) accepts the saved session") {
            val restored = AppleAuthenticationService(transport).restore(latestSession)
                ?: broken("Apple no longer accepts this session. Usually it expired rather than the protocol changed: " +
                    "run liveSignIn again, then rerun.")
            latestSession = restored
            "accepted; ${restored.webservices.size} web services listed"
        }
        check("session.services", "The session lists ckdatabasews and photosupload on allowed Apple hosts", "session.validate") {
            val services = latestSession.webservices
            fun service(name: String): String {
                val url = services[name] ?: broken("$name is missing; the session lists: ${services.keys.sorted().joinToString()}")
                val host = runCatching { AppleEndpointPolicy.requireAllowed(url).host }.getOrElse { broken("$name: ${it.message}") }
                if (!host.contains(name, ignoreCase = true)) broken("$name points at an unexpected host")
                return host
            }
            service("ckdatabasews")
            service("photosupload")
            if (services["sharedstreams"] == null) "ok; no sharedstreams service, so Alba lists no legacy Shared Albums" else "ok"
        }
        check("library.zone", "zones/list in the private com.apple.photos.cloud database lists ${PrimarySyncZone.NAME} with a syncToken",
            "session.services") {
            val token = catalog.captureSyncToken()
            if (token.isBlank()) broken("the PrimarySync zone has an empty syncToken")
            val uploadToken = photos.captureChangeToken(binding)
            if (uploadToken.isBlank()) broken("the upload path read an empty change token")
            syncToken = token
            "ok"
        }
        check("library.firstPage", "records/query CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted, after the web-access and " +
            "CheckIndexingState checks, returns a page Alba's parser reads", "library.zone") {
            firstPage = catalog.fetchPage(null, FIRST_PAGE).records
            if (firstPage.isEmpty()) throw Inconclusive("the library returned no photos, so the field checks cannot decide")
            "${firstPage.size} photos parsed"
        }
        check("library.fields", "CPLAsset and CPLMaster records carry the fields Alba parses", "library.firstPage") { checkFields() }
        check("library.count", "HyperionIndexCountLookup returns an itemCount for the library feed", "library.zone") {
            val count = catalog.libraryItemCount() ?: broken("no itemCount in the HyperionIndexCountLookup response")
            "$count items"
        }
        check("library.changes", "changes/zone accepts a fresh syncToken and returns a next token", "library.zone") {
            val changes = catalog.changes(requireNotNull(syncToken), KnownCloudRecords { emptyList() })
            if (changes.nextToken.isBlank()) broken("changes/zone returned no syncToken")
            "ok; ${changes.upserts.size} changed since the token was read"
        }
        check("library.lookup", "records/lookup finds a photo of the first page by its CPLAsset record name", "library.firstPage") {
            val asset = requireNotNull(firstPage.first().assetRecordName)
            val found = requireNotNull(catalog.libraryRecords(listOf(asset)))
            if (found.present.none { it.assetRecordName == asset }) broken("the asset was not found; gone: ${found.gone.size}")
            "ok"
        }
        check("library.download", "A photo's preview downloadURL streams bytes from an allowed host", "library.firstPage") {
            val counter = CountingStream()
            catalog.writePreview(firstPage.first().id, counter)
            if (counter.count == 0L) broken("the preview download was empty")
            "${counter.count} bytes"
        }
        check("library.albums", "CPLAlbumByPositionLive, CPLContainerRelationLiveByAssetDate and the smart-album queries parse",
            "library.zone") {
            val snapshot = catalog.collections()
            "${snapshot.collections.count { !it.id.startsWith("smart:") }} albums and folders"
        }
        check("library.hidden", "CPLAssetAndMasterHiddenByAssetDate reads the Hidden album completely", "library.zone") {
            "${catalog.hiddenMedia().size} hidden"
        }
        check("library.recentlyDeleted", "CPLAssetAndMasterDeletedByExpungedDate reads Recently Deleted completely", "library.zone") {
            "${catalog.recentlyDeleted().size} recently deleted"
        }
        check("upload.verification", "The read that verifies uploads (changes/zone after a token) parses", "library.zone") {
            val page = photos.findUploadCandidates(binding, requireNotNull(syncToken), acceptance = null, limit = 1)
            "ok; ${page.candidates.size} candidates"
        }
        results += CheckResult("upload.reservation", "photosupload/createUploadUrl reserves an upload URL", Outcome.SKIPPED,
            "not checked: the reservation is a write on Apple's side; session.services checks the upload service URL")
        check("account.storage", "setup.icloud.com storageUsageInfo parses", "session.validate") {
            val usage = catalog.storageUsage()
            if (usage.totalBytes <= 0) broken("storage total is ${usage.totalBytes}")
            "ok"
        }
        check("account.profile", "The account profile (name and photo) loads", "session.validate") {
            catalog.accountProfile() ?: broken("no profile")
            "ok"
        }
        check("shared.albums", "Shared Albums (legacy sharedstreams and CloudKit shared zones) and invitations read and parse",
            "session.services") {
            val snapshot = catalog.sharedAlbums()
            val invitations = catalog.pendingSharedInvitations()
            "${snapshot.collections.collections.size} albums, ${snapshot.records.size} items, ${invitations.size} invitations"
        }
        if (rejected) {
            results += CheckResult("session.kept", "Apple keeps accepting the session during the run", Outcome.FAILED,
                "a request was refused with 401/403 after validation")
        }
        return results
    }

    /** The first page again, raw: which fields Apple returns, by name, for the records Alba's parser reads. */
    private suspend fun checkFields(): String {
        val root = database.call("records/query", buildJsonObject {
            putJsonObject("query") {
                put("recordType", "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted")
                putJsonArray("filterBy") {
                    add(buildJsonObject {
                        put("fieldName", "direction"); put("comparator", "EQUALS")
                        putJsonObject("fieldValue") { put("type", "STRING"); put("value", "ASCENDING") }
                    })
                    add(buildJsonObject {
                        put("fieldName", "startRank"); put("comparator", "EQUALS")
                        putJsonObject("fieldValue") { put("type", "INT64"); put("value", 0) }
                    })
                }
            }
            put("zoneID", PrimarySyncZone.zoneId())
            put("resultsLimit", FIRST_PAGE * 2)
        })
        val records = root["records"]?.jsonArray?.map { it.jsonObject } ?: broken("the response has no records list")
        val masters = records.filter { it.recordType() == "CPLMaster" }
        val assets = records.filter { it.recordType() == "CPLAsset" }
        if (masters.isEmpty() || assets.isEmpty()) {
            broken("expected CPLAsset and CPLMaster records; got types ${records.mapNotNull { it.recordType() }.toSet()}")
        }
        val missing = sortedMapOf<String, Int>()
        fun require(present: Boolean, field: String) { if (!present) missing[field] = (missing[field] ?: 0) + 1 }
        val masterNames = masters.mapNotNull { it.recordName() }.toSet()
        for (asset in assets) {
            val masterRef = asset.fieldObject("masterRef")?.text("recordName")
            require(masterRef != null, "CPLAsset.masterRef.recordName")
            require(masterRef == null || masterRef in masterNames, "CPLAsset.masterRef pointing at a CPLMaster of the same page")
            require((asset.field("assetDate") as? JsonPrimitive)?.longOrNull != null, "CPLAsset.assetDate (number)")
            require(asset.text("recordChangeTag") != null, "CPLAsset.recordChangeTag")
        }
        for (master in masters) {
            val original = master.fieldObject("resOriginalRes")
            val url = original?.text("downloadURL")
            require(url != null, "CPLMaster.resOriginalRes.downloadURL")
            require(url == null || runCatching { AppleEndpointPolicy.requireAllowed(url) }.isSuccess,
                "CPLMaster.resOriginalRes.downloadURL on an allowed host")
            require(original?.get("size")?.let { it as? JsonPrimitive }?.longOrNull != null ||
                master.field("resOriginalFileSize") != null, "CPLMaster.resOriginalRes.size")
            require(master.fieldText("filenameEnc") != null, "CPLMaster.filenameEnc")
            require(master.fieldText("itemType") != null || master.fieldText("resOriginalFileType") != null,
                "CPLMaster.itemType or resOriginalFileType")
            require(PREVIEWS.any { master.fieldObject(it)?.text("downloadURL") != null }, "CPLMaster preview (${PREVIEWS.joinToString()})")
            require(master.text("recordChangeTag") != null, "CPLMaster.recordChangeTag")
        }
        if (missing.isNotEmpty()) {
            broken("missing on some records (count): " + missing.entries.joinToString { "${it.key} (${it.value})" } +
                ". Alba's parser skips such records, so photos would silently drop out of the library.")
        }
        return "${assets.size} assets and ${masters.size} masters carry every field checked"
    }

    private suspend fun check(id: String, assumption: String, vararg requires: String, block: suspend () -> String) {
        val unmet = requires.filter { required -> results.none { it.id == required && it.outcome == Outcome.PASSED } }
        if (unmet.isNotEmpty()) {
            results += CheckResult(id, assumption, Outcome.SKIPPED, "not run: needs ${unmet.joinToString()}")
            return
        }
        results += try {
            CheckResult(id, assumption, Outcome.PASSED, block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (inconclusive: Inconclusive) {
            CheckResult(id, assumption, Outcome.SKIPPED, inconclusive.message.orEmpty())
        } catch (error: Throwable) {
            CheckResult(id, assumption, Outcome.FAILED, describe(error))
        }
    }

    /** What went wrong, without tokens, identifiers or signed URL queries, so the report is safe to paste or log. */
    private fun describe(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(3).map { cause ->
            if (cause is Broken) cause.message.orEmpty() else "${cause::class.java.simpleName}: ${cause.message.orEmpty()}"
        }.joinToString(" <- ")
        var text = Regex("""(https?://[^\s?"]+)\?\S*""").replace(chain) { "${it.groupValues[1]}?…" }
        listOf(latestSession.dsid, latestSession.clientId, latestSession.accountName, latestSession.sessionToken)
            .filter { it.length >= MIN_REDACTED }
            .forEach { text = text.replace(it, "<redacted>") }
        return text.take(MAX_DETAIL)
    }

    private class CountingStream : OutputStream() {
        var count = 0L
        override fun write(b: Int) { count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { count += len }
    }

    private companion object {
        const val FIRST_PAGE = 5
        const val MAX_DETAIL = 600
        const val MIN_REDACTED = 4
        val PREVIEWS = listOf("resJPEGThumbRes", "resJPEGMedRes", "resJPEGLargeRes")
    }
}
