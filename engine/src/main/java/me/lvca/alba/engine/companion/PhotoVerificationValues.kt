package me.lvca.alba.engine.companion

import me.lvca.alba.engine.database.AccountEntity
import me.lvca.alba.engine.database.RemoteAssetPairEntity
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.RemotePairCandidate
import me.lvca.alba.engine.model.RemoteRecordRef
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

