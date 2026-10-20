package me.lvca.alba.protocol.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM under a non-exportable Android Keystore key named [alias]. The key is created on first
 * use. Creation is serialized across the process, so two concurrent first uses cannot each create a
 * key and leave data sealed under one of them unreadable.
 */
internal class KeystoreAesGcm(private val alias: String) {
    class Sealed(val iv: ByteArray, val ciphertext: ByteArray)

    /** Seals [plaintext] under a fresh Keystore-chosen IV, binding [aad] when given. */
    fun encrypt(plaintext: ByteArray, aad: ByteArray? = null): Sealed {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        aad?.let(cipher::updateAAD)
        val ciphertext = cipher.doFinal(plaintext)
        return Sealed(cipher.iv, ciphertext)
    }

    /** Opens what [encrypt] sealed; fails when the data, [iv] or [aad] were changed. */
    fun decrypt(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(ciphertext)
    }

    fun key(): SecretKey = synchronized(KEY_CREATION) {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return@synchronized it.secretKey }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        val KEY_CREATION = Any()
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
