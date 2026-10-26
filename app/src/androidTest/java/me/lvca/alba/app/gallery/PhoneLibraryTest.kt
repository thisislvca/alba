package me.lvca.alba.app.gallery

import android.net.Uri
import androidx.compose.ui.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.MediaStoreFixtures
import me.lvca.alba.app.testing.Smoke
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.grantTestMediaPermissions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Phone photos appear in the library as Android adds and removes them. */
class PhoneLibraryTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose
    private val repository get() = (compose.activity.application as AlbaApplication).graph.galleryRepository
    private fun image(): Uri = MediaStoreFixtures.insertJpeg("alba-completion-${System.nanoTime()}.jpg")

    @Smoke @Test fun foregroundMediaStoreChangesAppearWithoutLeavingTheApp() {
        grantTestMediaPermissions(compose.activity)
        // Resume updates permission state and starts the lifecycle-bound observer.
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForIdle()
        val uri = image()
        try {
            compose.waitUntil(UI_WAIT) { runBlocking { repository.observeGallery().first().any { it.originalReference == uri.toString() } } }
            compose.activity.contentResolver.delete(uri, null, null)
            compose.waitUntil(UI_WAIT) { runBlocking { repository.observeGallery().first().none { it.originalReference == uri.toString() } } }
        } finally { runCatching { compose.activity.contentResolver.delete(uri, null, null) } }
    }
}
