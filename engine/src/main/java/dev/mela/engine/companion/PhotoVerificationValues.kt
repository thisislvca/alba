package dev.mela.engine.companion

import dev.mela.engine.database.AccountEntity
import dev.mela.engine.database.RemoteAssetPairEntity
import dev.mela.engine.model.AccountBinding
import dev.mela.engine.model.RemotePairCandidate
import dev.mela.engine.model.RemoteRecordRef
import java.io.OutputStream
import java.security.MessageDigest

internal fun AccountEntity.toBinding() = AccountBinding(accountId, authEpoch, operationFence)

internal fun RemoteAssetPairEntity.toCandidate() = RemotePairCandidate(
    master = RemoteRecordRef(masterRecordName, masterChangeTag),
    asset = RemoteRecordRef(assetRecordName, assetChangeTag),
    relationIsCurrent = relationIsCurrent,
    masterDeleted = masterDeleted,
    assetDeleted = assetDeleted,
    originalResourceIdentity = originalResourceIdentity,
)

internal class HashingOutputStream : OutputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    var byteCount: Long = 0
        private set

    override fun write(value: Int) {
        digest.update(value.toByte())
        byteCount += 1
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        digest.update(buffer, offset, length)
        byteCount += length
    }

    fun sha256Hex(): String = digest.cloneDigestHex()

    private fun MessageDigest.cloneDigestHex(): String = (clone() as MessageDigest)
        .digest()
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

