package dev.mela.protocol.photos

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.mela.engine.database.LibraryDao
import dev.mela.engine.database.UploadCheckpointEntity
import dev.mela.engine.model.AccountBinding
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class UploadCheckpoint(val phase: String, val payload: String)
interface UploadJournal {
    suspend fun load(binding: AccountBinding, attempt: String): UploadCheckpoint?
    suspend fun save(binding: AccountBinding, attempt: String, checkpoint: UploadCheckpoint)
}

class EncryptedUploadJournal(private val dao: LibraryDao) : UploadJournal {
    private companion object { val keyLock = Any() }
    private fun aad(binding: AccountBinding, attempt: String, phase: String) =
        "v1|${binding.accountId}|${binding.authEpoch}|${binding.operationFence}|$attempt|$phase".toByteArray()
    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("mela_upload_checkpoints_v1", null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("mela_upload_checkpoints_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
        }.generateKey()
    }
    override suspend fun load(binding: AccountBinding, attempt: String): UploadCheckpoint? {
        val row = dao.checkpoint(attempt) ?: return null
        val blob = row.sealedPayload
        require(blob.size >= 29 && blob[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(1, 13)))
        cipher.updateAAD(aad(binding, attempt, row.phase))
        return UploadCheckpoint(row.phase, String(cipher.doFinal(blob.copyOfRange(13, blob.size)), Charsets.UTF_8))
    }
    override suspend fun save(binding: AccountBinding, attempt: String, checkpoint: UploadCheckpoint) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad(binding, attempt, checkpoint.phase))
        val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(checkpoint.payload.toByteArray())
        dao.putCheckpoint(UploadCheckpointEntity(attempt, checkpoint.phase, encrypted, System.currentTimeMillis()))
    }
}
