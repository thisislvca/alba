package me.lvca.alba.app.account

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.AlbaDependencies
import me.lvca.alba.app.AlbaViewModel
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.runBlockingWithMainLooper
import me.lvca.alba.app.testing.testDependencies
import me.lvca.alba.engine.model.CloudAccountProfile
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.account.AppleSessionStore
import me.lvca.alba.protocol.account.ICloudAccountManager
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.PersistedCookie
import me.lvca.alba.protocol.account.SessionStatus
import me.lvca.alba.protocol.auth.AppleAuthenticationService
import me.lvca.alba.protocol.network.AppleHttpRequest
import me.lvca.alba.protocol.network.AppleHttpResponse
import me.lvca.alba.protocol.network.AppleHttpTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * The account's photo, saved on the phone: it shows from a cold start before Apple answers, belongs to one account
 * only, and goes when the account signs out.
 */
@RunWith(AndroidJUnit4::class)
class AccountPhotoTest {
    private val app get() = ApplicationProvider.getApplicationContext<AlbaApplication>()
    private val directory get() = File(app.noBackupFilesDir, "account-photo-test")

    /** Counts profile reads, which go to Apple. */
    private class CountingProfiles(delegate: GalleryRepository) : GalleryRepository by delegate {
        val reads = AtomicInteger()
        override suspend fun accountProfile(): CloudAccountProfile? {
            reads.incrementAndGet()
            return null
        }
    }

    /** Says when the controller has looked for a saved photo, so a test can check what it found. */
    private class WatchedStore(directory: File) : AccountPhotoStore(directory) {
        val loaded = CompletableDeferred<String>()
        override fun load(dsid: String): SavedAccountProfile? = super.load(dsid).also { loaded.complete(dsid) }
    }

    private fun <T> withViewModel(dependencies: AlbaDependencies, block: suspend CoroutineScope.(AlbaViewModel) -> T): T =
        runBlockingWithMainLooper {
            val store = ViewModelStore()
            val vm = withContext(Dispatchers.Main) { AlbaViewModel(dependencies, SavedStateHandle()).also { store.put("vm", it) } }
            val observing = launch { vm.account.state.collect() }
            try { block(vm) } finally {
                observing.cancelAndJoin()
                withContext(Dispatchers.Main) { store.clear() }
            }
        }

    @Test fun aColdStartShowsTheSavedPhotoBeforeAppleAnswers() {
        val store = AccountPhotoStore(directory).apply { save(SAVED.dsid, PHOTO, "Alex Example") }
        val network = CompletableDeferred<Unit>()
        val profiles = CountingProfiles(app.graph.galleryRepository)
        val accounts = savedAccount { network.await(); throw java.io.IOException("offline") }
        withViewModel(testDependencies(app.graph).copy(repository = profiles, accountManager = accounts, accountPhotos = store)) { vm ->
            try {
                val state = withTimeout(SHORT_WAIT) { vm.account.state.first { it.accountPhoto != null } }
                assertEquals("Apple has not answered yet", ICloudAccountState.Restoring, accounts.state.value)
                assertArrayEquals(PHOTO, state.accountPhoto)
                assertEquals("Alex Example", state.accountName)
                assertEquals("No profile read for a photo already saved", 0, profiles.reads.get())
            } finally { network.complete(Unit) }
        }
    }

    @Test fun signingOutDeletesTheSavedPhoto() {
        val store = AccountPhotoStore(directory).apply { save(SAVED.dsid, PHOTO, "Alex Example") }
        val accounts = savedAccount { throw java.io.IOException("offline") }
        withViewModel(testDependencies(app.graph).copy(accountManager = accounts, accountPhotos = store)) { vm ->
            withTimeout(SHORT_WAIT) { vm.account.state.first { it.accountState is ICloudAccountState.SignedIn && it.accountPhoto != null } }
            val removed = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) { vm.account.signOut { removed.complete(Unit) } }
            withTimeout(SHORT_WAIT) { removed.await() }
            val signedOut = vm.account.state.first { it.accountState == ICloudAccountState.SignedOut }
            assertNull(signedOut.accountPhoto)
            assertNull(signedOut.savedAccountPhoto)
            assertNull(store.load(SAVED.dsid))
            assertFalse(directory.exists())
        }
    }

    @Test fun anotherAccountNeverShowsThePreviousAccountsPhoto() {
        val store = WatchedStore(directory).apply { save("previous-account", PHOTO, "Previous Owner") }
        val accounts = savedAccount { throw java.io.IOException("offline") }
        withViewModel(testDependencies(app.graph).copy(accountManager = accounts, accountPhotos = store)) { vm ->
            assertEquals(SAVED.dsid, withTimeout(SHORT_WAIT) { store.loaded.await() })
            val state = withTimeout(SHORT_WAIT) {
                vm.account.state.first { (it.accountState as? ICloudAccountState.SignedIn)?.status == SessionStatus.OFFLINE }
            }
            assertNull(state.accountPhoto)
            assertNull(state.savedAccountName)
            // The Apple Account's monogram instead.
            assertEquals(SAVED.accountName, state.accountName)
        }
    }

    @Test fun theStoreKeepsOneAccountAndRefreshesDaily() {
        var now = 1_000L
        val store = AccountPhotoStore(directory) { now }
        val saved = store.save("one", PHOTO, "One")
        assertNull("Another account reads nothing", store.load("two"))
        assertArrayEquals(PHOTO, store.load("one")?.photo)
        assertEquals("One", store.load("one")?.displayName)
        assertFalse(store.isStale(saved))
        now += 24L * 60 * 60 * 1000
        assertTrue(store.isStale(saved))
        store.keepOnly("one")
        assertNotNull(store.load("one"))
        store.keepOnly("two")
        assertNull(store.load("one"))
    }

    /** The saved account [SAVED], restoring; Apple answers every request with [answer]. */
    private fun savedAccount(answer: suspend () -> AppleHttpResponse): ICloudAccountManager {
        val transport = object : AppleHttpTransport {
            override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse = answer()
            override suspend fun stream(url: String, output: OutputStream) { answer() }
            override fun snapshotCookies(): List<PersistedCookie> = emptyList()
            override fun restore(snapshot: AppleSessionSnapshot) = Unit
            override fun clear() = Unit
        }
        val sessions = object : AppleSessionStore {
            private var saved: AppleSessionSnapshot? = SAVED
            override suspend fun load() = saved
            override suspend fun save(snapshot: AppleSessionSnapshot) { saved = snapshot }
            override suspend fun clear() { saved = null }
        }
        return ICloudAccountManager(AppleAuthenticationService(transport), sessions)
    }

    private companion object {
        val PHOTO = byteArrayOf(1, 2, 3, 4)
        val SAVED = AppleSessionSnapshot("owner@example.com", "client", "IT", "session", "token", null, "123",
            mapOf("ckdatabasews" to "https://p01-ckdatabasews.icloud.com"), emptyList())
    }
}
