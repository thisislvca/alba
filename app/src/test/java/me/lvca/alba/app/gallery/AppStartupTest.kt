package me.lvca.alba.app.gallery

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.testing.FIRST_DEMO_PHOTO
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.runBlockingWithMainLooper
import me.lvca.alba.app.testing.waitForGalleryMedia
import me.lvca.alba.app.testing.waitUntilDisplayed
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * The real start-up path that `AlbaAppRule` skips by seeding: the demo starts with an empty repository, and the view
 * model's own start-up refresh loads the catalog and puts it on screen.
 */
@RunWith(AndroidJUnit4::class)
class AppStartupTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    private val repository get() = ApplicationProvider.getApplicationContext<AlbaApplication>().graph.galleryRepository

    /** Before the activity starts, the library is empty: nothing seeds it. Room reads need no main looper. */
    @get:Rule(order = 1) val startsEmpty = object : ExternalResource() {
        override fun before() = assertTrue(runBlocking { repository.observeGallery().first() }.isEmpty())
    }

    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun theDemoLibraryLoadsFromEmptyAtStartUp() {
        // Photos and albums arrive with the refresh, in whichever order its publishing reaches the screen.
        compose.waitForGalleryMedia(FIRST_DEMO_PHOTO)
        compose.waitUntilDisplayed(hasTestTag("shared-albums-strip"))
        val loaded = runBlockingWithMainLooper { repository.observeGallery().first() }
        assertTrue("The start-up refresh reads the demo catalog", loaded.any { it.id == FIRST_DEMO_PHOTO })
    }
}
