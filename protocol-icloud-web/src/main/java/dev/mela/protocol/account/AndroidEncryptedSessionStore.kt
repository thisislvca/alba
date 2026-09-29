package dev.mela.protocol.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidEncryptedSessionStore(
    context: Context,
) : AppleSessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override suspend fun load(): AppleSessionSnapshot? = withContext(Dispatchers.IO) {
        val encrypted = preferences.getString(KEY_CIPHERTEXT, null) ?: return@withContext null
        val iv = checkNotNull(preferences.getString(KEY_IV, null)) { "Saved Apple session is incomplete" }
        run {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                encryptionKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val plaintext = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
            AppleSessionCodec.decode(plaintext.toString(Charsets.UTF_8))
        }
    }

    override suspend fun save(snapshot: AppleSessionSnapshot) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val ciphertext = cipher.doFinal(AppleSessionCodec.encode(snapshot).toByteArray(Charsets.UTF_8))
        check(
            preferences.edit()
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit(),
        ) { "Could not persist the encrypted Apple session" }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        check(preferences.edit().clear().commit()) { "Could not clear the Apple session" }
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        internal const val PREFERENCES_NAME = "mela_icloud_session"
        private const val KEY_ALIAS = "mela_icloud_session_v1"
        private const val KEY_IV = "iv"
        private const val KEY_CIPHERTEXT = "ciphertext"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
