package me.lvca.alba.protocol.account

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lvca.alba.protocol.crypto.KeystoreAesGcm

class AndroidEncryptedSessionStore(
    context: Context,
) : AppleSessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val cipher = KeystoreAesGcm(KEY_ALIAS)

    override suspend fun load(): AppleSessionSnapshot? = withContext(Dispatchers.IO) {
        val encrypted = preferences.getString(KEY_CIPHERTEXT, null) ?: return@withContext null
        val iv = checkNotNull(preferences.getString(KEY_IV, null)) { "Saved Apple session is incomplete" }
        val plaintext = cipher.decrypt(Base64.decode(iv, Base64.NO_WRAP), Base64.decode(encrypted, Base64.NO_WRAP))
        AppleSessionCodec.decode(plaintext.toString(Charsets.UTF_8))
    }

    override suspend fun save(snapshot: AppleSessionSnapshot) = withContext(Dispatchers.IO) {
        val sealed = cipher.encrypt(AppleSessionCodec.encode(snapshot).toByteArray(Charsets.UTF_8))
        check(
            preferences.edit()
                .putString(KEY_IV, Base64.encodeToString(sealed.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(sealed.ciphertext, Base64.NO_WRAP))
                .commit(),
        ) { "Could not persist the encrypted Apple session" }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        check(preferences.edit().clear().commit()) { "Could not clear the Apple session" }
    }

    companion object {
        // Named before the Alba rename and stored on devices; keep it.
        internal const val PREFERENCES_NAME = "mela_icloud_session"
        private const val KEY_ALIAS = "mela_icloud_session_v1"
        private const val KEY_IV = "iv"
        private const val KEY_CIPHERTEXT = "ciphertext"
    }
}
