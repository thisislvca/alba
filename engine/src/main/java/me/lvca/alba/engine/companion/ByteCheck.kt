package me.lvca.alba.engine.companion

import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.RemotePairCandidate

/** What a byte check read from iCloud. */
internal data class ByteCheckResult(
    val byteCount: Long,
    val sha256Hex: String,
    /** Every byte of the iCloud original equals the expected bytes, and the record still names the same original. */
    val matches: Boolean,
)

/**
 * The byte check: downloads an iCloud original afresh and compares every byte, by count and SHA-256, with what the phone
 * holds. It is the strongest check Alba has and costs a full download, so it runs where a mistake could lose a photo:
 * right before Free up space deletes a phone copy, and for an upload with no receipt to match. A read that fails throws;
 * it says nothing about whether the copies match.
 */
internal class ByteCheck(private val protocol: ICloudPhotoProtocol) {
    suspend fun compare(binding: AccountBinding, pair: RemotePairCandidate, byteCount: Long, sha256Hex: String): ByteCheckResult {
        val sink = HashingOutputStream()
        val observation = protocol.streamFreshOriginal(binding, pair, sink)
        val hash = sink.sha256Hex()
        return ByteCheckResult(
            byteCount = sink.byteCount,
            sha256Hex = hash,
            matches = sink.byteCount == byteCount &&
                observation.byteCount == byteCount &&
                hash == sha256Hex &&
                observation.resourceIdentity == pair.originalResourceIdentity,
        )
    }
}
