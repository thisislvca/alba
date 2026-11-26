package me.lvca.alba.engine.companion

import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.MotionPhotoPartEntity
import me.lvca.alba.engine.database.MotionPhotoPartEntity.Companion.ATTACHED
import me.lvca.alba.engine.database.MotionPhotoPartEntity.Companion.ATTACHING
import me.lvca.alba.engine.database.MotionPhotoPartEntity.Companion.DETECTING
import me.lvca.alba.engine.database.MotionPhotoPartEntity.Companion.FAILED
import me.lvca.alba.engine.database.MotionPhotoPartEntity.Companion.NOT_MOTION
import me.lvca.alba.engine.database.MotionPhotoPartEntity.Companion.WAITING_VIDEO
import me.lvca.alba.engine.database.RemoteAssetPairEntity
import me.lvca.alba.engine.database.StagedSourceEntity
import me.lvca.alba.engine.database.TransferEntity
import me.lvca.alba.engine.database.TransferState
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.BackupAttribute
import me.lvca.alba.engine.model.BackupBreadcrumb
import me.lvca.alba.engine.model.BackupDiagnostics
import me.lvca.alba.engine.model.BackupEvent
import me.lvca.alba.engine.model.BackupPhase
import me.lvca.alba.engine.model.BackupTelemetry
import me.lvca.alba.engine.model.BackupTelemetry.traced
import me.lvca.alba.engine.model.DiagnosticSeverity
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.LivePhotoAttachRefusedException
import me.lvca.alba.engine.model.LivePhotoAttachment
import me.lvca.alba.engine.model.LivePhotoVideo
import me.lvca.alba.engine.model.MotionPhotoMode
import me.lvca.alba.engine.model.PhotoCompanionException
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.UploadReceipt
import me.lvca.alba.engine.source.ExactOriginalSource
import me.lvca.alba.engine.source.MotionPhotoReader
import me.lvca.alba.engine.source.MotionPhotoVideo
import me.lvca.alba.engine.source.UnsupportedUploadException
import java.util.UUID

/**
 * The video half of motion photos, after their still is backed up. Each still queued while "Motion photos" was "Photo
 * and video separately" or "As Live Photo" has a part ([MotionPhotoPartEntity]). Once the still is verified, its phone
 * file is read once more and its video is found from the XMP ([MotionPhotoReader]); a file without one is done. Then:
 *
 * - **Photo and video separately**: the video is queued as its own MP4 item and backs up through the normal path.
 * - **As Live Photo**: the video is attached to the still's iCloud records ([ICloudPhotoProtocol.attachLivePhotoVideo]),
 *   then the master is read again: the item is a Live Photo only once that read shows the original video resource with
 *   the receipt's checksum and the video's size. Only a still this upload created is changed; a still iCloud already had
 *   is left as it is, since clients that cached it would not pick up a video added later.
 *
 * Failures that may pass are tried again with the uploads' backoff; one iCloud refuses, or after [MAX_TRIES], leaves
 * the part FAILED: the still stays backed up as a plain photo and the item says the Live Photo part was not attached,
 * with Retry. The attach is journalled by the protocol under the part's id, so a crash mid-attach resumes there.
 */
internal class MotionPhotoParts(
    private val database: AlbaDatabase,
    private val originals: ExactOriginalSource,
    private val protocol: ICloudPhotoProtocol,
    private val now: () -> Long,
    /** Queues a PHOTO_AND_VIDEO video as its own item; returns its transfer id. */
    private val queueVideo: suspend (AccountBinding, TransferEntity, StagedSourceEntity, MotionPhotoVideo) -> String,
    private val diagnostics: BackupDiagnostics = BackupDiagnostics.None,
) {
    private val parts = database.motionPhotoDao()
    private val transferDao = database.transferDao()
    private val verificationDao = database.verificationDao()

    /**
     * Advances one part whose still is backed up: reads its video, or attaches it when [allowAttach] (the network and
     * the user's settings allow a video upload and uploads are not paused). Returns 1 when it did something.
     */
    suspend fun advanceOne(binding: AccountBinding, allowAttach: Boolean): Int {
        parts.markVideoItemsBackedUp(binding.accountId, now())
        val part = parts.findNextDue(binding.accountId, now(), allowAttach) ?: return 0
        when (part.state) {
            DETECTING -> detect(binding, part)
            ATTACHING -> attach(binding, part)
        }
        return 1
    }

    /** When the next part is due, for scheduling the next wake; null when none waits. */
    suspend fun earliestDue(binding: AccountBinding, allowAttach: Boolean): Long? = parts.earliestDue(binding.accountId, allowAttach)

    private suspend fun detect(binding: AccountBinding, part: MotionPhotoPartEntity) {
        val (transfer, source) = stillOf(part) ?: return finish(part, FAILED, StoredMessage.THIS_FILE_IS_NO_LONGER_ON_THE_PHONE)
        val read = runSuspendCatching { readVideo(source) }.getOrElse { error ->
            return retryLater(part, error, StoredMessage.COULD_NOT_READ_THIS_FILE_ON_THE_PHONE)
        }
        when (read) {
            is Read.Changed -> finish(part, FAILED, StoredMessage.THIS_PHOTO_CHANGED_WHILE_IT_WAS_BEING_UPLOADED)
            is Read.None -> finish(part, NOT_MOTION, null)
            is Read.Found -> {
                val described = describe(part, read.video)
                if (part.mode == MotionPhotoMode.LIVE_PHOTO.name) {
                    update(described.copy(state = ATTACHING, message = StoredMessage.ADDING_THE_LIVE_PHOTO_MOTION.code))
                } else {
                    queueSeparately(binding, described, transfer, source, read.video)
                }
            }
        }
    }

    private fun describe(part: MotionPhotoPartEntity, video: MotionPhotoVideo) = part.copy(
        format = video.format.id, videoOffset = video.offset, videoByteCount = video.byteCount, videoSha256Hex = video.sha256Hex,
        durationNanos = video.info.durationUs * NANOS_PER_MICRO, stillTimeNanos = video.stillTimeUs * NANOS_PER_MICRO,
        width = video.info.width, height = video.info.height, tries = 0, nextTryAtEpochMillis = null,
    )

    /** PHOTO_AND_VIDEO: the video becomes its own backup item; the part follows it until it is backed up. */
    private suspend fun queueSeparately(
        binding: AccountBinding,
        part: MotionPhotoPartEntity,
        transfer: TransferEntity,
        source: StagedSourceEntity,
        video: MotionPhotoVideo,
    ) {
        val queued = runSuspendCatching { queueVideo(binding, transfer, source, video) }
        val error = queued.exceptionOrNull()
        val videoTransfer = when {
            error == null -> queued.getOrThrow()
            error is UnsupportedUploadException -> return finish(part, FAILED, null, error.message)
            // The same video is already queued or backed up: follow that item.
            error is PhotoCompanionException.Refused -> transferDao.findExistingTransferForBytes(binding.accountId, video.sha256Hex)
            else -> null
        } ?: return retryLater(part, requireNotNull(error), StoredMessage.COULD_NOT_READ_THIS_FILE_ON_THE_PHONE)
        update(part.copy(state = WAITING_VIDEO, videoTransferId = videoTransfer, message = null))
        parts.markVideoItemsBackedUp(binding.accountId, now())
    }

    private suspend fun attach(binding: AccountBinding, part: MotionPhotoPartEntity) {
        val (transfer, source) = stillOf(part) ?: return finish(part, FAILED, StoredMessage.LIVE_PHOTO_PART_NOT_ATTACHED)
        val pair = newStill(binding, transfer) ?: return refused(part, "NOT_A_NEW_ITEM")
        val read = runSuspendCatching { readVideo(source) }.getOrElse { return retryLater(part, it, null) }
        val found = (read as? Read.Found)?.video?.takeIf { it.sha256Hex == part.videoSha256Hex && it.offset == part.videoOffset }
            ?: return finish(part, FAILED, StoredMessage.LIVE_PHOTO_PART_NOT_ATTACHED)
        val video = LivePhotoVideo(
            bytes = found.bytes, sha256Hex = found.sha256Hex,
            durationNanos = requireNotNull(part.durationNanos), stillTimeNanos = requireNotNull(part.stillTimeNanos),
            width = part.width ?: 0, height = part.height ?: 0, contentIdentifier = part.contentIdentifier,
        )
        val sized = mapOf(
            BackupAttribute.MEDIA_KIND to "video", BackupAttribute.SIZE_BUCKET to BackupTelemetry.sizeBucket(video.byteCount),
            BackupAttribute.MOTION_FORMAT to (part.format ?: "unknown"),
        )
        val sent = runSuspendCatching {
            diagnostics.traced(BackupPhase.LIVE_ATTACH, sized) {
                protocol.attachLivePhotoVideo(binding, "${part.transferId}:${part.attachRound}", pair.masterRecordName, pair.assetRecordName, video)
            }
        }
        val attached = sent.getOrElse { error ->
            return if (error is LivePhotoAttachRefusedException) {
                refused(part, error.code ?: "REFUSED")
            } else {
                retryLater(part, error, StoredMessage.ADDING_THE_LIVE_PHOTO_MOTION)
            }
        }
        confirm(binding, part, pair, attached, video, sized)
    }

    /**
     * The verified pair of [transfer] when its upload created it: Apple accepted it as new (not a 409 duplicate), and the
     * verified pair is the one Apple named. A still iCloud already had, or one found already there, gets no video.
     */
    private suspend fun newStill(binding: AccountBinding, transfer: TransferEntity): RemoteAssetPairEntity? {
        val acceptance = transfer.activeAttemptId?.let { transferDao.findAcceptance(it) }?.takeIf { it.duplicateHint == false }
            ?: return null
        val pair = transfer.mediaId?.let { verificationDao.findCurrentProof(it, binding.accountId) }
            ?.let { verificationDao.findRemotePair(it.remotePairId) }
        return pair?.takeIf { it.masterRecordName == acceptance.masterRecordName && it.assetRecordName == acceptance.assetRecordName }
    }

    /** Backed up only once iCloud, read afresh, holds this video: the receipt's checksum, and the video's size. */
    private suspend fun confirm(
        binding: AccountBinding,
        part: MotionPhotoPartEntity,
        pair: RemoteAssetPairEntity,
        attached: LivePhotoAttachment,
        video: LivePhotoVideo,
        sized: Map<BackupAttribute, Any>,
    ) {
        val observed = runSuspendCatching {
            diagnostics.traced(BackupPhase.LIVE_REREAD, sized) { protocol.livePhotoVideoResource(binding, pair.masterRecordName) }
        }.getOrElse { return retryLater(part, it, StoredMessage.ADDING_THE_LIVE_PHOTO_MOTION) }
        val expected = UploadReceipt(video.byteCount, attached.receipt.fileChecksum)
        if (observed != expected || attached.receipt != expected) {
            diagnostics.capture(BackupEvent.RECEIPT_MISMATCH, DiagnosticSeverity.WARNING, sized + (BackupAttribute.PHASE to "live_reread"))
            return retryLater(part, LiveVideoNotReadBack(), StoredMessage.ADDING_THE_LIVE_PHOTO_MOTION)
        }
        update(
            part.copy(
                state = ATTACHED, message = null, masterRecordName = pair.masterRecordName, assetRecordName = pair.assetRecordName,
                receiptFileChecksum = expected.fileChecksum, mediaGroupIdWritten = attached.mediaGroupIdWritten,
                attachedAtEpochMillis = now(), nextTryAtEpochMillis = null,
            ),
        )
    }

    /** The attach went through, but a fresh read of the still does not show the video yet. */
    private class LiveVideoNotReadBack : RuntimeException("The Live Photo video was not read back")

    /** iCloud refused the attach for good: the still stays a plain photo, flagged, with Retry. */
    private suspend fun refused(part: MotionPhotoPartEntity, code: String) {
        diagnostics.capture(
            BackupEvent.LIVE_ATTACH_FAILED, DiagnosticSeverity.ERROR,
            mapOf(BackupAttribute.ERROR to code, BackupAttribute.OUTCOME to "refused", BackupAttribute.MOTION_FORMAT to (part.format ?: "unknown")),
        )
        finish(part, FAILED, StoredMessage.LIVE_PHOTO_PART_NOT_ATTACHED)
    }

    private suspend fun retryLater(part: MotionPhotoPartEntity, error: Throwable, message: StoredMessage?) {
        val tries = part.tries + 1
        if (tries >= MAX_TRIES) {
            if (part.state == ATTACHING) {
                diagnostics.capture(
                    BackupEvent.LIVE_ATTACH_FAILED, DiagnosticSeverity.ERROR,
                    mapOf(BackupAttribute.ERROR to BackupTelemetry.errorClass(error), BackupAttribute.OUTCOME to "gave_up", BackupAttribute.COUNT to tries),
                )
            }
            return finish(part, FAILED, if (part.state == ATTACHING) StoredMessage.LIVE_PHOTO_PART_NOT_ATTACHED else message)
        }
        diagnostics.breadcrumb(
            BackupBreadcrumb.LIVE_ATTACH_RETRY,
            mapOf(BackupAttribute.ERROR to BackupTelemetry.errorClass(error), BackupAttribute.COUNT to tries),
        )
        update(part.copy(tries = tries, nextTryAtEpochMillis = now() + Reconciler.retryBackoffMillis(tries - 1), message = message?.code ?: part.message))
    }

    private suspend fun finish(part: MotionPhotoPartEntity, state: String, message: StoredMessage?, rawMessage: String? = null) {
        update(part.copy(state = state, message = message?.code ?: rawMessage, nextTryAtEpochMillis = null))
    }

    private suspend fun update(part: MotionPhotoPartEntity) {
        parts.update(part.copy(updatedAtEpochMillis = now()))
    }

    private suspend fun stillOf(part: MotionPhotoPartEntity): Pair<TransferEntity, StagedSourceEntity>? {
        val transfer = transferDao.findTransfer(part.transferId)?.takeIf { it.state == TransferState.VERIFIED } ?: return null
        val source = transferDao.findStagedSource(transfer.stagedSourceId) ?: return null
        return transfer to source
    }

    private sealed interface Read {
        class Found(val video: MotionPhotoVideo) : Read
        data object None : Read
        data object Changed : Read
    }

    /** Reads the still's phone file once more, as it was backed up, and finds its video. */
    private suspend fun readVideo(source: StagedSourceEntity): Read {
        val reader = MotionPhotoReader(source.byteCount)
        val evidence = diagnostics.traced(BackupPhase.MOTION_READ, mapOf(BackupAttribute.SIZE_BUCKET to BackupTelemetry.sizeBucket(source.byteCount))) {
            originals.copyMediaStoreItem(source.contentUri, source.sourceRevision, reader)
        }
        if (evidence.byteCount != source.byteCount || evidence.sha256Hex != source.sha256Hex) return Read.Changed
        val video = reader.finish()
        if (video == null) {
            reader.failure?.let { failure ->
                diagnostics.capture(
                    BackupEvent.MOTION_PARSE_FAILED, DiagnosticSeverity.WARNING,
                    mapOf(BackupAttribute.MOTION_FORMAT to (reader.declaredFormat?.id ?: "unknown"), BackupAttribute.ERROR to failure.name),
                )
            }
            return Read.None
        }
        return Read.Found(video)
    }

    /** The user's Retry for a part that failed: it is read and attached afresh, under a new journal row. */
    suspend fun retry(transferId: String): Boolean {
        val part = parts.find(transferId)?.takeIf { it.state == FAILED } ?: return false
        diagnostics.breadcrumb(BackupBreadcrumb.RETRY_NOW)
        update(
            part.copy(
                state = DETECTING, attachRound = part.attachRound + 1, tries = 0, nextTryAtEpochMillis = null,
                message = null, videoTransferId = null,
            ),
        )
        return true
    }

    /** Retries every failed part of [binding]'s account; returns how many. */
    suspend fun retryAll(binding: AccountBinding): Int = parts.findFailed(binding.accountId).count { retry(it.transferId) }

    /**
     * The byte check of a motion photo's video before Free up space removes its phone copy, after its still passed its
     * own. Null when the item may go: it is no motion photo, or its video in iCloud has every byte of the phone's.
     * Otherwise the reason it stays: its video is not backed up (yet), differs, or could not be read.
     */
    suspend fun checkBeforeTrash(binding: AccountBinding, mediaId: String): String? {
        val part = parts.findForMedia(binding.accountId, mediaId)?.takeIf { it.state != NOT_MOTION } ?: return null
        val byteCount = part.videoByteCount?.takeIf { part.state == ATTACHED }
            ?: return StoredMessage.MOTION_PHOTO_VIDEO_NOT_BACKED_UP_YET.code
        val sink = HashingOutputStream()
        val checked = mapOf(BackupAttribute.MEDIA_KIND to "video", BackupAttribute.SIZE_BUCKET to BackupTelemetry.sizeBucket(byteCount))
        val read = runSuspendCatching { diagnostics.traced(BackupPhase.BYTE_CHECK, checked) { downloadVideo(binding, part, sink) } }
            .getOrElse { return StoredMessage.COULD_NOT_REACH_ICLOUD_TO_CHECK.code }
        if (read != byteCount || sink.byteCount != byteCount || sink.sha256Hex() != part.videoSha256Hex) {
            diagnostics.capture(BackupEvent.BYTE_CHECK_FAILED, DiagnosticSeverity.FATAL, checked + (BackupAttribute.PHASE to "motion_video"))
            return StoredMessage.THE_CURRENT_LOCAL_AND_ICLOUD_ORIGINALS_NO_LONGER_MATCH.code
        }
        update(part.copy(byteCheckedAtEpochMillis = now()))
        return null
    }

    /** Downloads [part]'s video from iCloud afresh: the Live Photo's video resource, or the separate video item's original. */
    private suspend fun downloadVideo(binding: AccountBinding, part: MotionPhotoPartEntity, sink: HashingOutputStream): Long {
        if (part.mode == MotionPhotoMode.LIVE_PHOTO.name) {
            return protocol.streamLivePhotoVideo(binding, requireNotNull(part.masterRecordName), sink)
        }
        val attemptId = requireNotNull(part.videoTransferId?.let { transferDao.findTransfer(it) }?.activeAttemptId)
        val pair = verificationDao.findReconciliationMatches(attemptId).singleOrNull()
            ?: transferDao.findAcceptance(attemptId)?.let { accepted ->
                protocol.libraryPair(binding, requireNotNull(accepted.masterRecordName), requireNotNull(accepted.assetRecordName))
                    ?.toPairEntity(binding, now())
            }
        return protocol.streamFreshOriginal(binding, requireNotNull(pair).toCandidate(), sink).byteCount
    }

    /** Phone items Free up space must keep because their motion video is not backed up yet, or failed. */
    suspend fun keptOnPhone(binding: AccountBinding): Set<String> = parts.findMediaKeptOnPhone(binding.accountId).toSet()

    /** Phone items whose Live Photo is still being made, so they are not counted as backed up yet. */
    suspend fun livePending(binding: AccountBinding): Set<String> = parts.findLivePending(binding.accountId).toSet()

    companion object {
        /** Tries of a part's read or attach before it is left FAILED for the user's Retry. */
        const val MAX_TRIES = 5
        private const val NANOS_PER_MICRO = 1_000L

        /** A new part for a still queued under [mode]; null when [mode] keeps the still only or it is no photo. */
        fun newPart(transferId: String, accountId: String, mediaId: String?, mimeType: String, mode: MotionPhotoMode, at: Long): MotionPhotoPartEntity? {
            if (mode == MotionPhotoMode.PHOTO_ONLY || mimeType !in MOTION_PHOTO_TYPES) return null
            return MotionPhotoPartEntity(
                transferId = transferId, accountId = accountId, mediaId = mediaId, mode = mode.name, state = DETECTING,
                contentIdentifier = UUID.randomUUID().toString().uppercase(), createdAtEpochMillis = at, updatedAtEpochMillis = at,
            )
        }

        /** Motion photos are JPEGs (Pixel, Samsung, Xiaomi) or HEICs (newer Pixel and Samsung). */
        private val MOTION_PHOTO_TYPES = setOf("image/jpeg", "image/heic", "image/heif")
    }
}
