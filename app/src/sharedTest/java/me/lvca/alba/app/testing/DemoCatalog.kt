package me.lvca.alba.app.testing

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.compose.ui.test.IdlingResource
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.AlbaViewModel
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.gallery.GalleryState
import me.lvca.alba.app.gallery.SyncState
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/*
 * The demo library, made deterministic for whole-app tests.
 *
 * In production the demo catalog reaches the screen asynchronously: the view model's start-up refresh reads the
 * fixtures into Room, Room publishes on a background thread, and the gallery projects that off the main thread. A test
 * that started tapping as soon as the activity was up raced all three.
 *
 * Tests instead read the whole catalog (library, albums, shared albums, Recently Deleted, Hidden) into the repository
 * before the activity starts ([seedDemoCatalog]), and hold every Compose synchronization (each onNode, click and
 * waitForIdle) until the activity's view model has published it to the UI and finished its own start-up refresh
 * ([InitialCatalogIdling]). That refresh runs as in production and republishes the same rows; waiting for it means no
 * test edit races it. `AppStartupTest` covers the unseeded path, from an empty library to a loaded one.
 */

/**
 * Seeds the demo catalog and holds [compose] until it is on screen. Order it inside the onboarding rule, which selects
 * the demo, and outside [compose], which launches the activity.
 */
internal class DemoCatalogRule(private val compose: ComposeTestRule) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val application = ApplicationProvider.getApplicationContext<AlbaApplication>()
            seedDemoCatalog(application)
            val idling = InitialCatalogIdling(application)
            idling.start()
            compose.registerIdlingResource(idling)
            try {
                base.evaluate()
            } finally {
                idling.stop()
                // Best effort once the test is over: a failure here must not hide the test's own.
                runCatching { compose.unregisterIdlingResource(idling) }
            }
        }
    }
}

/**
 * Reads the demo catalog into the repository on the calling thread; returns once Room holds all of it. The refresh needs
 * no main looper (Room and the fixture source run on their own threads), so this can block Robolectric's main thread,
 * and it fails rather than hangs if the refresh does not finish.
 */
internal fun seedDemoCatalog(application: AlbaApplication) {
    runBlocking { withTimeout(SEED_TIMEOUT_MILLIS) { application.graph.galleryRepository.refresh(includeDeviceMedia = false) } }
}

/** The fixture catalog reads in well under a second; this only bounds a failure. */
private const val SEED_TIMEOUT_MILLIS = 30_000L

/**
 * Busy until [MainActivity]'s view model has published the seeded catalog to the gallery and finished its start-up
 * refresh, then idle for good: later refreshes and edits are each test's own to wait for. Call [start] before the activity launches, so it sees the
 * activity start, and [stop] once the test ends. Before any activity has started it has nothing to wait for.
 */
internal class InitialCatalogIdling(private val application: Application) : IdlingResource {
    @Volatile private var gallery: StateFlow<GalleryState>? = null
    @Volatile private var sync: StateFlow<SyncState>? = null
    @Volatile private var published = false

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            // The same instance the activity's `viewModels` delegate returns: same store, same key, same factory.
            if (activity is MainActivity) {
                val model = ViewModelProvider(activity, AlbaViewModel.Factory(activity))[AlbaViewModel::class.java]
                sync = model.sync.state
                gallery = model.gallery.state
            }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    fun start() = application.registerActivityLifecycleCallbacks(callbacks)

    fun stop() = application.unregisterActivityLifecycleCallbacks(callbacks)

    // Idle until the activity starts: the Compose rule synchronizes once before it launches the activity. From then on,
    // busy until the gallery shows photos and the start-up refresh is over. Room publishes a query's whole result at
    // once and the seed finished before the activity started, so the gallery's first state with photos holds the whole
    // catalog and its albums.
    override val isIdleNow: Boolean
        get() {
            val state = gallery ?: return true
            val refresh = sync?.value ?: return true
            if (!published) {
                published = state.value.let { it.catalogItems.isNotEmpty() && it.collections.isNotEmpty() } &&
                    !refresh.isRefreshing && refresh.lastRefreshedAt != null
            }
            // Robolectric polls this in a tight loop on the main thread while Room and the projection publish on others.
            // Pausing briefly leaves them the CPU.
            if (!published) Thread.sleep(BUSY_POLL_MILLIS)
            return published
        }

    override fun getDiagnosticMessageIfBusy(): String = "The seeded demo catalog has not reached the gallery, or the start-up refresh is running"

    private companion object {
        const val BUSY_POLL_MILLIS = 2L
    }
}
