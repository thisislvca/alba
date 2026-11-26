package me.lvca.alba.engine.companion

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
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
import me.lvca.alba.engine.model.UploadReceipt
import me.lvca.alba.engine.model.WakeupScheduler
import me.lvca.alba.engine.source.DeviceMediaRecord
import me.lvca.alba.engine.source.ExactOriginalEvidence
import me.lvca.alba.engine.source.ExactOriginalSource
import me.lvca.alba.engine.source.InPlaceDescription
import me.lvca.alba.engine.source.InPlaceOriginal
import me.lvca.alba.engine.source.LocalDiscoveryCheckpoint
import me.lvca.alba.engine.source.LocalDiscoveryPage
import me.lvca.alba.engine.source.LocalTrashState
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/** A phone whose MediaStore items and bytes the test sets. */
internal open class TestPhone(private val context: Context) : ExactOriginalSource {
    val items = mutableListOf<DeviceMediaRecord>()

    /** Each item's bytes, by content URI. */
    val bytes = mutableMapOf<String, ByteArray>()

    /** Each item's MediaStore revision (generation and size), by content URI. */
    val revisions = mutableMapOf<String, String>()

    /** How many times a whole file was read to hash it, without a copy. */
    var hashReads = 0

    fun add(id: Long, name: String = "IMG_$id.jpg", content: ByteArray = jpeg(id)): DeviceMediaRecord {
        val record = DeviceMediaRecord(
            id = "device:external:$id", fileName = name, capturedAtEpochMillis = 1_000L + id, width = 10, height = 10,
            contentUri = "content://media/external/images/media/$id", sourceRevision = "rev:$id", volumeName = "external",
            volumeVersion = "v1", mediaStoreId = id, generationModified = id, mimeType = "image/jpeg",
            byteCount = content.size.toLong(),
        )
        items += record
        bytes[record.contentUri] = content
        revisions[record.contentUri] = record.sourceRevision
        return record
    }

    override fun permissionFingerprint() = "full:unredacted:test"
    override fun hasAutomaticBackupAccess() = true
    override suspend fun captureEnrollmentBaseline() = LocalDiscoveryCheckpoint("external", "v1", 0, Long.MAX_VALUE)

    override suspend fun discoverEligibleMedia(
        checkpoint: LocalDiscoveryCheckpoint?, limit: Int, scope: me.lvca.alba.engine.model.BackupScope, folders: Set<String>,
        addedAfterEpochMillis: Long, enrollmentBaseline: LocalDiscoveryCheckpoint?,
    ): LocalDiscoveryPage {
        val after = checkpoint?.generationModified ?: -1L
        val remaining = items.filter { it.generationModified > after }
        val page = remaining.take(limit)
        return LocalDiscoveryPage(
            page,
            LocalDiscoveryCheckpoint("external", "v1", page.lastOrNull()?.generationModified ?: after, page.lastOrNull()?.mediaStoreId ?: -1),
            remaining.size > limit,
        )
    }

    override suspend fun eligibleMediaIds(
        scope: me.lvca.alba.engine.model.BackupScope, folders: Set<String>, addedAfterEpochMillis: Long,
        enrollmentBaseline: LocalDiscoveryCheckpoint?, includeExisting: Boolean,
    ) = items.map { it.id }.toSet()

    override suspend fun copySelected(contentUri: String, displayNameHint: String?, output: OutputStream) =
        evidence(contentUri, displayNameHint, output)

    override suspend fun copyMediaStoreItem(contentUri: String, expectedRevision: String, output: OutputStream): ExactOriginalEvidence {
        check(expectedRevision.isBlank() || expectedRevision == revisions[contentUri]) { "changed before staging" }
        return evidence(contentUri, null, output)
    }

    override suspend fun hashMediaStoreItem(contentUri: String, expectedRevision: String): ExactOriginalEvidence {
        hashReads += 1
        return evidence(contentUri, null, null)
    }

    override suspend fun currentRevision(contentUri: String): String? = revisions[contentUri]

    /** Whether items can be read in place, as MediaStore files on a phone can; otherwise each is staged as a copy. */
    var readsInPlace = false

    /** How many times each item was opened in place. */
    val inPlaceOpens = mutableMapOf<String, Int>()

    /** Runs right after an item is opened in place for the nth time, with the content URI and n. */
    var onOpenedInPlace: (String, Int) -> Unit = { _, _ -> }

    /** Edits an item as a photo editor would: new bytes, a new MediaStore revision. */
    fun edit(contentUri: String, content: ByteArray) {
        bytes[contentUri] = content
        revisions[contentUri] = "edited:" + sha256(content).take(8)
    }

    override suspend fun openInPlace(contentUri: String, expectedRevision: String): InPlaceOriginal? {
        if (!readsInPlace) return null
        val revision = requireNotNull(revisions[contentUri])
        require(expectedRevision.isBlank() || expectedRevision == revision) { "changed before staging" }
        val content = requireNotNull(bytes[contentUri])
        // Like MediaStore's: an open descriptor on a file that has no path the app could open again.
        val view = File.createTempFile("in-place", ".jpg").apply { writeBytes(content) }
        val open = java.io.RandomAccessFile(view, "r")
        check(view.delete()) { "The test file keeps no path" }
        return InPlaceOriginal(
            InPlaceDescription(
                contentUri = contentUri, displayName = items.first { it.contentUri == contentUri }.fileName,
                lineageKey = "lineage:$contentUri", sourceRevision = revision, acquisitionMethod = "TEST_IN_PLACE",
                originalFormatRequested = true, unredacted = true, lastModifiedAtEpochMillis = MODIFIED_AT,
                dateTakenEpochMillis = null,
            ),
            byteCount = content.size.toLong(), descriptor = open.fd,
            stream = {
                val opens = (inPlaceOpens[contentUri] ?: 0) + 1
                inPlaceOpens[contentUri] = opens
                // What the open file holds now; an edit after this point shows in the size and modification time.
                ByteArrayInputStream(requireNotNull(bytes[contentUri])).also { onOpenedInPlace(contentUri, opens) }
            },
            unchanged = { revisions[contentUri] == revision },
            release = { open.close() },
        )
    }

    override fun createTrashRequest(contentUris: List<String>): IntentSender = PendingIntent.getActivity(
        context, 45, Intent("me.lvca.alba.TEST_TRASH").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE,
    ).intentSender

    override suspend fun readTrashState(contentUri: String): LocalTrashState? = LocalTrashState(true, null)

    private fun evidence(contentUri: String, name: String?, output: OutputStream?): ExactOriginalEvidence {
        val content = requireNotNull(bytes[contentUri]) { "No such item" }
        output?.write(content)
        return ExactOriginalEvidence(
            contentUri = contentUri,
            displayName = name ?: items.firstOrNull { it.contentUri == contentUri }?.fileName ?: "selected.jpg",
            mimeType = "image/jpeg", lineageKey = "lineage:$contentUri", sourceRevision = revisions[contentUri] ?: "rev",
            acquisitionMethod = "TEST", originalFormatRequested = true, unredacted = true, formatValidated = true,
            byteCount = content.size.toLong(), sha256Hex = sha256(content), lastModifiedAtEpochMillis = MODIFIED_AT,
        )
    }

    companion object {
        const val MODIFIED_AT = 1_700_000_000_000L

        /** A minimal JPEG-signed original, distinct per [id]. */
        fun jpeg(id: Long, size: Int = 7) = ByteArray(size) { index ->
            when (index) {
                0, 2 -> -1
                1 -> -40
                else -> (id + index).toByte()
            }
        }
    }
}

/**
 * iCloud as the upload protocol sees it. Each upload goes through the protocol's journal phases; Apple's content store
 * answers with a receipt for the bytes it read, and the registered CPLMaster carries the receipt's size and checksum in
 * its original resource and fingerprint, as Apple's own records do. Tests can change what iCloud records, hold uploads
 * in flight, freeze them at a phase as a process death would, and lose a registration's response.
 */
internal class TestICloud : ICloudPhotoProtocol {
    /** Each attempt's journal phase, as the real protocol saves it. */
    val phases = mutableMapOf<String, String>()
    private val receipts = mutableMapOf<String, UploadReceipt>()
    private val sentBytes = mutableMapOf<String, ByteArray>()

    /** The library's change feed since the upload started: every registered pair, plus [extraPairs]. */
    val registered = mutableListOf<RemotePairCandidate>()
    val extraPairs = mutableListOf<RemotePairCandidate>()

    /** What iCloud stores for each master, for downloads. */
    val stored = mutableMapOf<String, ByteArray>()

    /** Originals downloaded afresh (byte checks). */
    var downloads = 0
    var registrations = 0
    val uploadedNames = mutableListOf<String>()

    /** Apple's receipt for the bytes it read; returning null models a protocol without receipts. */
    var receiptFor: (ByteArray) -> UploadReceipt? = { UploadReceipt(it.size.toLong(), checksum(it)) }

    /** Changes the CPLMaster iCloud records for a registration, to model a difference on Apple's side. */
    var recordAs: (RemotePairCandidate) -> RemotePairCandidate = { it }

    /** Changes the bytes iCloud serves for a master, to model an original that differs from what was received. */
    var serveAs: (ByteArray) -> ByteArray = { it }

    /** The next registrations reach iCloud but their response is lost. */
    var lostResponses = 0

    /** Phases at which the next uploads freeze, one per upload, as a process death there would leave them. */
    val freezeAt = ArrayDeque<String>()
    var frozen = 0
    var onFrozen: (Int) -> Unit = {}

    /** While set, each upload waits here after its bytes are read, so several can be in flight at once. */
    var hold: CompletableDeferred<Unit>? = null
    var inFlight = 0
    var maxInFlight = 0
    var onInFlight: (Int) -> Unit = {}

    override suspend fun canResume(binding: AccountBinding, attemptId: String) = phases[attemptId] in RESUMABLE
    override suspend fun registrationNeverSent(binding: AccountBinding, attemptId: String) =
        phases[attemptId].let { it == null || it in BEFORE_REGISTRATION }
    override suspend fun uploadReceipt(binding: AccountBinding, attemptId: String) = receipts[attemptId]
    override fun currentDestination() = ICloudDestination(ACCOUNT, "session", "test@example.com")
    override suspend fun captureChangeToken(binding: AccountBinding) = "before"

    override suspend fun prepareOriginal(
        binding: AccountBinding, fileName: String, source: OneShotUploadSource, lastModifiedAtEpochMillis: Long?,
        captureOffsetMinutes: Int?,
    ): PreparedICloudUpload = object : PreparedICloudUpload {
        override suspend fun startOnce(attemptId: String): UploadAcceptance {
            val freeze = freezeAt.removeFirstOrNull()
            if (phases[attemptId] == null) {
                phases[attemptId] = "RESERVED"
                if (freeze == "RESERVED") freeze()
            }
            if (phases[attemptId] == "RESERVED") {
                phases[attemptId] = "BYTES_SENDING"
                val read = source.openOnce().use { it.readBytes() }
                if (freeze == "BYTES_SENDING") freeze()
                inFlight += 1
                maxInFlight = maxOf(maxInFlight, inFlight)
                onInFlight(inFlight)
                try {
                    hold?.await()
                } finally {
                    inFlight -= 1
                }
                sentBytes[attemptId] = read
                receiptFor(read)?.let { receipts[attemptId] = it }
                phases[attemptId] = "RECEIPT_SAVED"
                if (freeze == "RECEIPT_SAVED") freeze()
            }
            check(phases[attemptId] == "RECEIPT_SAVED") { "An uncertain write cannot be replayed" }
            phases[attemptId] = "REGISTERING"
            val bytes = requireNotNull(sentBytes[attemptId])
            val receipt = receipts[attemptId]
            registrations += 1
            uploadedNames += fileName
            val master = "master:$attemptId"
            stored[master] = bytes
            registered += recordAs(
                RemotePairCandidate(
                    RemoteRecordRef(master, "tag"), RemoteRecordRef("asset:$attemptId", "tag"),
                    relationIsCurrent = true, masterDeleted = false, assetDeleted = false,
                    originalResourceIdentity = "resource:$master",
                    originalByteCount = bytes.size.toLong(),
                    originalFileChecksum = checksum(bytes),
                    originalFingerprint = checksum(bytes),
                ),
            )
            if (lostResponses > 0) {
                lostResponses -= 1
                throw IOException("connection reset after registration")
            }
            phases[attemptId] = "ACCEPTED"
            return UploadAcceptance("request", master, "asset:$attemptId", false, receipt = receipt)
        }

        override fun cancel() = Unit
    }

    private suspend fun freeze(): Nothing {
        frozen += 1
        onFrozen(frozen)
        awaitCancellation()
    }

    override suspend fun findUploadCandidates(
        binding: AccountBinding, afterChangeToken: String, acceptance: UploadAcceptance?, limit: Int,
    ) = CandidatePage(registered + extraPairs, nextChangeToken = "after", moreComing = false)

    override suspend fun streamFreshOriginal(
        binding: AccountBinding, pair: RemotePairCandidate, output: OutputStream,
    ): RemoteOriginalObservation {
        downloads += 1
        val bytes = serveAs(requireNotNull(stored[pair.master.name]) { "Nothing stored for ${pair.master.name}" })
        output.write(bytes)
        return RemoteOriginalObservation(pair.originalResourceIdentity, bytes.size.toLong())
    }

    override suspend fun libraryPair(binding: AccountBinding, masterRecordName: String, assetRecordName: String) =
        (registered + extraPairs).firstOrNull { it.master.name == masterRecordName && it.asset.name == assetRecordName }

    companion object {
        const val ACCOUNT = "account"
        val RESUMABLE = setOf("RESERVING", "RESERVED", "RECEIPT_SAVED", "ACCEPTED")
        val BEFORE_REGISTRATION = setOf("RESERVING", "RESERVED", "BYTES_SENDING", "RECEIPT_SAVED")

        /** Apple's checksum of an original, as its receipt and CPLMaster give it: opaque, and the same for the same bytes. */
        fun checksum(bytes: ByteArray) = "apple:" + sha256(bytes).take(24)
    }
}

internal object NoWakeups : WakeupScheduler {
    override suspend fun scheduleSoon() = Unit
    override suspend fun setAutomaticBackupEnabled(enabled: Boolean) = Unit
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString(separator = "") { "%02x".format(it) }
