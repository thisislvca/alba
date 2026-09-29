package dev.mela.protocol.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidEncryptedSessionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = AndroidEncryptedSessionStore(context)

    @Before
    fun clearStoredState() = runBlocking {
        store.clear()
    }

    @Test
    fun unreadableStoredSessionRequiresExplicitRemoval() = runBlocking {
        val preferences = context.getSharedPreferences(AndroidEncryptedSessionStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().putString("ciphertext", "unreadable").putString("iv", "unreadable").commit()
        assertTrue(runCatching { store.load() }.isFailure)
        assertEquals("unreadable", preferences.getString("ciphertext", null))
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun encryptedStoreRoundTripsAndClearsSession() = runBlocking {
        val snapshot = AppleSessionSnapshot(
            accountName = "person@example.com",
            clientId = "client",
            accountCountryCode = "IT",
            sessionId = "session-id",
            sessionToken = "very-secret-session-token",
            trustToken = "trust",
            dsid = "123",
            webservices = mapOf("ckdatabasews" to "https://p01-ckdatabasews.icloud.com"),
            cookies = emptyList(),
        )

        store.save(snapshot)

        assertEquals(snapshot, store.load())
        val rawPreferences = context.getSharedPreferences(
            AndroidEncryptedSessionStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ).all.values.joinToString()
        assertFalse(rawPreferences.contains(snapshot.sessionToken))

        store.clear()
        assertNull(store.load())
    }
}
