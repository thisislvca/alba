package me.lvca.alba.engine.companion

import me.lvca.alba.engine.database.AssetLinkEntity
import me.lvca.alba.engine.database.StagedSourceEntity
import me.lvca.alba.engine.database.UploadAcceptanceEntity
import me.lvca.alba.engine.database.UploadAttemptEntity
import me.lvca.alba.engine.database.VerificationDao
import me.lvca.alba.engine.database.VerificationProofEntity
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.RemotePairCandidate
import me.lvca.alba.engine.model.UploadAcceptance
import me.lvca.alba.engine.model.UploadReceipt
import java.util.UUID

/**
 * Records that [attempt]'s upload of [source] is the iCloud item [pair]: the pair, a current link and the proof, made by
 * [method] at [verifiedAt]. Run it in the transaction that marks the transfer verified.
 */
internal suspend fun VerificationDao.recordVerifiedLink(
    binding: AccountBinding,
    mediaId: String?,
    source: StagedSourceEntity,
    attempt: UploadAttemptEntity,
    pair: RemotePairCandidate,
    method: String,
    verifiedAt: Long,
) {
    val pairId = remotePairId(binding, pair)
    val linkId = UUID.randomUUID().toString()
    insertRemotePair(pair.toPairEntity(binding, verifiedAt))
    insertAssetLink(AssetLinkEntity(linkId, binding.accountId, mediaId, source.stagedSourceId, pairId, attempt.attemptId, verifiedAt, "CURRENT"))
    insertVerificationProof(
        VerificationProofEntity(
            proofId = UUID.randomUUID().toString(),
            accountId = binding.accountId,
            verificationAuthEpoch = binding.authEpoch,
            invocationAuthEpoch = attempt.authEpoch,
            linkId = linkId,
            mediaId = mediaId,
            stagedSourceId = source.stagedSourceId,
            sourceRevision = source.sourceRevision,
            remotePairId = pairId,
            masterChangeTag = pair.master.changeTag,
            assetChangeTag = pair.asset.changeTag,
            originalResourceIdentity = pair.originalResourceIdentity,
            byteCount = source.byteCount,
            sha256Hex = source.sha256Hex,
            verifiedAtEpochMillis = verifiedAt,
            invalidatedAtEpochMillis = null,
            invalidationReason = null,
            method = method,
            byteCheckedAtEpochMillis = verifiedAt.takeIf { method == VerificationProofEntity.METHOD_BYTES },
        ),
    )
}

/** Apple's acceptance as the protocol gave it, with the receipt for the sent bytes when one was kept. */
internal fun UploadAcceptanceEntity.toModel() = UploadAcceptance(
    requestUuid, masterRecordName, assetRecordName, duplicateHint, uploadJobId,
    receipt = receiptByteCount?.let { size -> receiptFileChecksum?.let { UploadReceipt(size, it) } },
)
