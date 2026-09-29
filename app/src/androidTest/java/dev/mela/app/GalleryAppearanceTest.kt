package dev.mela.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.app.ui.mediaDuration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Exercises the new entry points against the real demo repository and video previews. */
class GalleryAppearanceTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity
    private fun capture(name: String) {
        compose.waitForIdle()
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val dir = context.getExternalFilesDir("gallery-polish")!!.apply { mkdirs() }
        java.io.File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun photosCollectionsSharedAlbumsAndVideoCovers() {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("shared-albums-strip").fetchSemanticsNodes().isNotEmpty() }
        val repository = (context.application as MelaApplication).graph.galleryRepository
        val sharedVideo = "shared:demo-library:private:owner:two:video"
        runBlocking { repository.ensurePreview(sharedVideo) }
        compose.waitForIdle()
        capture("photos")
        val rail = compose.onNodeWithTag("gallery-fast-scroll")
        rail.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.35f) }
        compose.waitForIdle()
        val handle = compose.onNodeWithTag("fast-scroll-handle").fetchSemanticsNode().boundsInRoot
        val track = rail.fetchSemanticsNode().boundsInRoot
        rail.performTouchInput { down(handle.center - track.topLeft) }
        compose.onNodeWithTag("fast-scroll-date").assertIsDisplayed()
        capture("scrubber")
        rail.performTouchInput { up() }
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-albums").assertIsDisplayed()
        compose.onNodeWithTag("collection-shared").assertIsDisplayed()
        capture("collections")
        compose.onNodeWithTag("collection-shared").performClick()
        compose.onNodeWithText("Family moments").assertIsDisplayed()
        capture("shared-albums")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Family moments").assertIsDisplayed()
        compose.onNodeWithText("Family moments").performClick()
        compose.onNodeWithTag("album-hero").assertIsDisplayed()
        capture("shared-album")
        compose.onNodeWithTag("gallery-grid").performScrollToKey(sharedVideo)
        val video = runBlocking { repository.observeGallery().first().first { it.id == sharedVideo } }
        compose.onNodeWithTag("media-$sharedVideo").assertTextContains(requireNotNull(mediaDuration(video.durationMillis)))
        capture("video-duration")
        compose.onNodeWithContentDescription(context.getString(R.string.back_to_collections)).performClick()
        compose.onNodeWithText("Family moments").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.back_to_collections)).performClick()
        compose.onNodeWithTag("collection-albums").performClick()
        compose.onNodeWithText("Journeys").performClick()
        compose.onNodeWithText("By the sea").performClick()
        compose.onNodeWithTag("album-hero").assertIsDisplayed()
        capture("personal-album")
    }
}
