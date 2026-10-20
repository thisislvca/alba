package me.lvca.alba.protocol.photos

import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.SealedUploadCheckpoint
import me.lvca.alba.engine.model.UploadCheckpointStore
import me.lvca.alba.protocol.crypto.KeystoreAesGcm

data class UploadCheckpoint(val phase: String, val payload: String)
interface UploadJournal {
    suspend fun load(binding: AccountBinding, attempt: String): UploadCheckpoint?
    suspend fun save(binding: AccountBinding, attempt: String, checkpoint: UploadCheckpoint)
}

class EncryptedUploadJournal(private val store: UploadCheckpointStore) : UploadJournal {
    // Named before the Alba rename and stored on devices; keep it.
    private val cipher = KeystoreAesGcm("mela_upload_checkpoints_v1")

    private fun aad(binding: AccountBinding, attempt: String, phase: String) =
        "v1|${binding.accountId}|${binding.authEpoch}|${binding.operationFence}|$attempt|$phase".toByteArray()

    override suspend fun load(binding: AccountBinding, attempt: String): UploadCheckpoint? {
        val row = store.load(attempt) ?: return null
        val blob = row.sealedPayload
        require(blob.size >= 29 && blob[0] == FORMAT_V1)
        val payload = cipher.decrypt(blob.copyOfRange(1, 13), blob.copyOfRange(13, blob.size), aad(binding, attempt, row.phase))
        return UploadCheckpoint(row.phase, String(payload, Charsets.UTF_8))
    }

    override suspend fun save(binding: AccountBinding, attempt: String, checkpoint: UploadCheckpoint) {
        val sealed = cipher.encrypt(checkpoint.payload.toByteArray(), aad(binding, attempt, checkpoint.phase))
        val encrypted = byteArrayOf(FORMAT_V1) + sealed.iv + sealed.ciphertext
        store.save(SealedUploadCheckpoint(attempt, checkpoint.phase, encrypted, System.currentTimeMillis()))
    }

    private companion object {
        const val FORMAT_V1: Byte = 1
    }
}
