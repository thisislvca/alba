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
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.AlbaViewModel
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.gallery.GalleryState
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
 * waitForIdle) until the activity's view model has published it to the UI ([InitialCatalogIdling]). The app's own
 * start-up refresh still runs, as in production, and republishes the same rows.
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
                compose.unregisterIdlingResource(idling)
                idling.stop()
            }
        }
    }
}

/** Reads the demo catalog into the repository on the calling thread; returns once Room holds all of it. */
internal fun seedDemoCatalog(application: AlbaApplication) {
    runBlocking { application.graph.galleryRepository.refresh(includeDeviceMedia = false) }
}

/**
 * Busy until [MainActivity]'s view model has published the seeded catalog to the gallery, then idle for good: later
 * refreshes and edits are each test's own to wait for. Call [start] before the activity launches, so it sees the
 * activity start, and [stop] once the test ends. Before any activity has started it has nothing to wait for.
 */
internal class InitialCatalogIdling(private val application: Application) : IdlingResource {
    @Volatile private var gallery: StateFlow<GalleryState>? = null
    @Volatile private var published = false

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            // The same instance the activity's `viewModels` delegate returns: same store, same key, same factory.
            if (activity is MainActivity) {
                gallery = ViewModelProvider(activity, AlbaViewModel.Factory(activity))[AlbaViewModel::class.java].gallery.state
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
    // busy until the gallery shows photos. Room publishes a query's whole result at once and the seed finished before
    // the activity started, so the gallery's first state with photos holds the whole catalog and its albums.
    override val isIdleNow: Boolean
        get() {
            val state = gallery ?: return true
            if (!published) published = state.value.let { it.catalogItems.isNotEmpty() && it.collections.isNotEmpty() }
            // Robolectric polls this in a tight loop on the main thread while Room and the projection publish on others.
            // Pausing briefly gives them the CPU, and gives the JVM a safepoint the spinning loop would not.
            if (!published) Thread.sleep(BUSY_POLL_MILLIS)
            return published
        }

    override fun getDiagnosticMessageIfBusy(): String = "The seeded demo catalog has not reached the gallery yet"

    private companion object {
        const val BUSY_POLL_MILLIS = 2L
    }
}
