package me.lvca.alba.app.gallery

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.MediaStoreFixtures
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.Smoke
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.grantTestMediaPermissions
import me.lvca.alba.app.testing.waitForGalleryMedia
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Phone photos appear in the library as Android adds and removes them. */
class PhoneLibraryTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private val repository get() = (compose.activity.application as AlbaApplication).graph.galleryRepository
    private fun image(): Uri = MediaStoreFixtures.insertJpeg("alba-completion-${System.nanoTime()}.jpg")

    @Smoke @Test fun foregroundMediaStoreChangesAppearWithoutLeavingTheApp() {
        compose.waitForGalleryMedia()
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
