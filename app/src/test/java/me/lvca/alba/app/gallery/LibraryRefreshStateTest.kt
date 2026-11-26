package me.lvca.alba.app.gallery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.AlbaDependencies
import me.lvca.alba.app.AlbaViewModel
import me.lvca.alba.app.screenshots.ScreenshotFixtures
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.runBlockingWithMainLooper
import me.lvca.alba.app.testing.testDependencies
import me.lvca.alba.app.ui.gallery.LibraryLoad
import me.lvca.alba.app.ui.gallery.libraryLoad
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.MaintenanceWakeup
import me.lvca.alba.engine.model.RefreshSummary
import me.lvca.alba.engine.model.WakeReason
import me.lvca.alba.engine.model.WakeSummary
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.account.AppleSessionStore
import me.lvca.alba.protocol.account.ICloudAccountManager
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.PersistedCookie
import me.lvca.alba.protocol.auth.AppleAuthenticationService
import me.lvca.alba.protocol.network.AppleHttpRequest
import me.lvca.alba.protocol.network.AppleHttpResponse
import me.lvca.alba.protocol.network.AppleHttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.OutputStream
import java.time.Instant

/**
 * The library's refreshing state, from start-up to a pull: it shows a refresh only while one runs, never waits for
 * work that is not the library's, and a stored library shows before Apple answers.
 */
@RunWith(AndroidJUnit4::class)
class LibraryRefreshStateTest {
    private val app get() = ApplicationProvider.getApplicationContext<AlbaApplication>()

    /**
     * One refresh at a time, each ending when the test says. Its counts are flows, so a wait on them together with the
     * session state checks again when either changes.
     */
    private class GatedRefreshes(delegate: GalleryRepository) : GalleryRepository by delegate {
        val started = MutableStateFlow(0)
        val deviceScans = MutableStateFlow(0)
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        var lastRefresh: Flow<Long?> = flowOf(null)

        fun gate() = CompletableDeferred<Unit>().also { synchronized(gates) { gates.addLast(it) } }

        override suspend fun refresh(includeDeviceMedia: Boolean): RefreshSummary {
            val gate = synchronized(gates) { gates.removeFirstOrNull() }
            started.update { it + 1 }
            gate?.await()
            return RefreshSummary(0, 0, REFRESHED_AT)
        }

        override suspend fun refreshDevice() { deviceScans.update { it + 1 } }
        override fun observeLastRefresh(): Flow<Long?> = lastRefresh
    }

    /**
     * Waits until the library shows as refreshing with [count] refreshes started. The session state alone does not
     * change when the repository's refresh begins (it already shows as refreshing from start-up), so a wait on the state
     * that only peeked at the count missed the refresh beginning after its last check, and timed out.
     */
    private suspend fun AlbaViewModel.awaitRefreshing(repository: GatedRefreshes, count: Int) = withTimeout(SHORT_WAIT) {
        combine(account.state, repository.started) { state, started -> state.isRefreshing && started == count }.first { it }
    }

    private fun <T> withViewModel(dependencies: AlbaDependencies, block: suspend CoroutineScope.(AlbaViewModel) -> T): T =
        runBlockingWithMainLooper {
            val store = ViewModelStore()
            val vm = withContext(Dispatchers.Main) { AlbaViewModel(dependencies, SavedStateHandle()).also { store.put("vm", it) } }
            val observing = launch { vm.account.state.collect() }
            val gallery = launch { vm.gallery.state.collect() }
            try { block(vm) } finally {
                observing.cancelAndJoin(); gallery.cancelAndJoin()
                withContext(Dispatchers.Main) { store.clear() }
            }
        }

    /**
     * Start-up woke photo maintenance first and refreshed the library only after it. Waking waits for a backup pass in
     * progress, upload included, so the library sat "refreshing" with no refresh running, as long as the upload took.
     */
    @Test fun startUpRefreshesWithoutWaitingForPhotoMaintenance() {
        val repository = GatedRefreshes(app.graph.galleryRepository)
        val maintenance = object : MaintenanceWakeup {
            override suspend fun wake(reason: WakeReason): WakeSummary = awaitCancellation()
        }
        withViewModel(testDependencies(app.graph).copy(repository = repository, maintenance = maintenance)) { vm ->
            withTimeout(SHORT_WAIT) { vm.account.state.first { !it.isRefreshing } }
            assertEquals(1, repository.started.value)
        }
    }

    /**
     * The pull indicator shows only the refresh the pull started. A pull during a refresh already running joins it
     * quietly, and reads the phone again at once (a photo deleted on the phone meanwhile drops out) instead of waiting
     * for the running refresh, which read the phone when it started.
     */
    @Test fun aPullShowsOnlyItsOwnRefreshAndRereadsThePhoneWhenJoining() {
        val repository = GatedRefreshes(app.graph.galleryRepository)
        val startUp = repository.gate()
        withViewModel(testDependencies(app.graph).copy(repository = repository)) { vm ->
            withContext(Dispatchers.Main) { vm.gallery.setDeviceMediaAccess(granted = true, limited = false) }
            vm.awaitRefreshing(repository, count = 1)

            withContext(Dispatchers.Main) { vm.sync.refresh(fromPull = true) }
            withTimeout(SHORT_WAIT) { repository.deviceScans.first { it > 0 } }
            assertFalse("A joined refresh shows no pull indicator", vm.account.state.value.pullRefreshing)
            assertEquals(1, repository.started.value)

            startUp.complete(Unit)
            withTimeout(SHORT_WAIT) { vm.account.state.first { !it.isRefreshing } }

            val pulled = repository.gate()
            withContext(Dispatchers.Main) { vm.sync.refresh(fromPull = true) }
            withTimeout(SHORT_WAIT) { vm.account.state.first { it.pullRefreshing && it.isRefreshing } }
            pulled.complete(Unit)
            val idle = withTimeout(SHORT_WAIT) { vm.account.state.first { !it.isRefreshing && !it.pullRefreshing } }
            assertEquals(Instant.ofEpochMilli(REFRESHED_AT), idle.lastRefreshedAt)
        }
    }

    /** A refresh replaced or cancelled mid-way leaves nothing showing as refreshing. */
    @Test fun aCancelledRefreshStopsShowingAsRefreshing() {
        val repository = GatedRefreshes(app.graph.galleryRepository)
        repository.gate()
        withViewModel(testDependencies(app.graph).copy(repository = repository)) { vm ->
            vm.awaitRefreshing(repository, count = 1)
            withContext(Dispatchers.Main) { vm.sync.cancel() }
            withTimeout(SHORT_WAIT) { vm.account.state.first { !it.isRefreshing } }
        }
    }

    /** Profile's "last refreshed" is the last refresh that finished, even one from before this process started. */
    @Test fun lastRefreshedComesFromTheStoredLibrary() {
        val repository = GatedRefreshes(app.graph.galleryRepository).apply { lastRefresh = MutableStateFlow(STORED_AT) }
        repository.gate()
        withViewModel(testDependencies(app.graph).copy(repository = repository)) { vm ->
            val state = withTimeout(SHORT_WAIT) { vm.account.state.first { it.lastRefreshedAt != null } }
            assertTrue("The start-up refresh is still running", state.isRefreshing)
            assertEquals(Instant.ofEpochMilli(STORED_AT), state.lastRefreshedAt)
        }
    }

    /**
     * Cold start with a saved account and a slow network: the stored library shows from Room at once, as a quiet
     * refresh, instead of "Loading photos…" and "Updating library…" until Apple confirms the session. Apple never
     * answers here, so the library can only show by not waiting for it.
     */
    @Test fun aStoredLibraryShowsBeforeAppleConfirmsTheSavedSession() {
        val network = CompletableDeferred<Unit>()
        val stored = ScreenshotFixtures.library(3).mapIndexed { index, media -> media.copy(id = "icloud:owner:$index") }
        val repository = object : GalleryRepository by app.graph.galleryRepository {
            override fun observeGallery(): Flow<List<GalleryMedia>> = flowOf(stored)
            override suspend fun newestMedia(limit: Int): List<GalleryMedia> = emptyList()
            override suspend fun refresh(includeDeviceMedia: Boolean): RefreshSummary = awaitCancellation()
        }
        val accounts = savedAccountAppleNeverConfirms(network)
        val started = System.nanoTime()
        withViewModel(testDependencies(app.graph).copy(repository = repository, accountManager = accounts)) { vm ->
            try {
                val gallery = withTimeout(SHORT_WAIT) { vm.gallery.state.first { it.items.isNotEmpty() } }
                val elapsed = (System.nanoTime() - started) / 1_000_000
                println("Stored library on screen ${elapsed}ms after start, Apple not yet answered")
                assertEquals(ICloudAccountState.Restoring, accounts.state.value)
                assertEquals(stored.map { it.id }, gallery.items.map { it.id })
                assertEquals(3, gallery.cloudCount)
                // The header shows nothing for the refresh of a library already shown.
                assertEquals(LibraryLoad.REFRESHING, vm.account.state.value.libraryLoad(storedLibrary = gallery.cloudCount > 0))
            } finally { network.complete(Unit) }
        }
    }

    /**
     * Reading a large stored library takes a moment. Its newest photos show first from a small query, without totals,
     * and the start-up refresh meanwhile is a quiet one: "Updating library…" showed until the whole library was read.
     */
    @Test fun theNewestPhotosShowWhileTheStoredLibraryLoads() {
        val stored = ScreenshotFixtures.library(6).mapIndexed { index, media -> media.copy(id = "icloud:owner:$index") }
        val whole = CompletableDeferred<Unit>()
        val network = CompletableDeferred<Unit>()
        val repository = object : GalleryRepository by app.graph.galleryRepository {
            override fun observeGallery(): Flow<List<GalleryMedia>> = kotlinx.coroutines.flow.flow { whole.await(); emit(stored) }
            override suspend fun newestMedia(limit: Int): List<GalleryMedia> = stored.take(2)
            override suspend fun refresh(includeDeviceMedia: Boolean): RefreshSummary = awaitCancellation()
        }
        val accounts = savedAccountAppleNeverConfirms(network)
        withViewModel(testDependencies(app.graph).copy(repository = repository, accountManager = accounts)) { vm ->
            try {
                val refreshing = withTimeout(SHORT_WAIT) { vm.account.state.first { it.isRefreshing } }
                assertEquals("Nothing is read yet", LibraryLoad.REFRESHING, refreshing.libraryLoad(GalleryState(libraryLoaded = false)))

                val first = withTimeout(SHORT_WAIT) { vm.gallery.state.first { it.items.isNotEmpty() } }
                assertFalse(first.libraryLoaded)
                assertEquals(stored.take(2).map { it.id }, first.items.map { it.id })
                assertEquals(LibraryLoad.REFRESHING, vm.account.state.value.libraryLoad(first))

                whole.complete(Unit)
                val loaded = withTimeout(SHORT_WAIT) { vm.gallery.state.first { it.libraryLoaded } }
                assertEquals(stored.map { it.id }, loaded.items.map { it.id })
                assertEquals(6, loaded.cloudCount)
            } finally { network.complete(Unit) }
        }
    }

    /** The saved account, restoring: Apple answers only once [network] completes, and then with a network error. */
    private fun savedAccountAppleNeverConfirms(network: CompletableDeferred<Unit>): ICloudAccountManager {
        val transport = object : AppleHttpTransport {
            override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse { network.await(); throw java.io.IOException("offline") }
            override suspend fun stream(url: String, output: OutputStream) { network.await() }
            override fun snapshotCookies(): List<PersistedCookie> = emptyList()
            override fun restore(snapshot: AppleSessionSnapshot) = Unit
            override fun clear() = Unit
        }
        val store = object : AppleSessionStore {
            override suspend fun load() = SAVED
            override suspend fun save(snapshot: AppleSessionSnapshot) = Unit
            override suspend fun clear() = Unit
        }
        return ICloudAccountManager(AppleAuthenticationService(transport), store)
    }

    private companion object {
        const val REFRESHED_AT = 1_800_000_000_000L
        const val STORED_AT = 1_700_000_000_000L
        val SAVED = AppleSessionSnapshot("owner@example.com", "client", "IT", "session", "token", null, "123",
            mapOf("ckdatabasews" to "https://p01-ckdatabasews.icloud.com"), emptyList())
    }
}
