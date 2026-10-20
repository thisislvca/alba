package me.lvca.alba.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.ui.gallery.mediaDuration

/** Exercises the new entry points against the real demo repository and video previews. */
class GalleryAppearanceTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private val context get() = compose.activity
    @Test fun photosCollectionsSharedAlbumsAndVideoCovers() {
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("shared-albums-strip").fetchSemanticsNodes().isNotEmpty() }
        val repository = (context.application as AlbaApplication).graph.galleryRepository
        val sharedVideo = "shared:demo-library:private:owner:two:video"
        runBlocking { repository.ensurePreview(sharedVideo) }
        compose.waitForIdle()
        val rail = compose.onNodeWithTag("gallery-fast-scroll")
        rail.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.35f) }
        compose.waitForIdle()
        val handle = compose.onNodeWithTag("fast-scroll-handle").fetchSemanticsNode().boundsInRoot
        val track = rail.fetchSemanticsNode().boundsInRoot
        rail.performTouchInput { down(handle.center - track.topLeft) }
        compose.onNodeWithTag("fast-scroll-date").assertIsDisplayed()
        rail.performTouchInput { up() }
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-albums").assertIsDisplayed()
        compose.onNodeWithTag("collection-shared").assertIsDisplayed()
        compose.onNodeWithTag("collection-shared").performClick()
        compose.onNodeWithText("Family moments").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Family moments").assertIsDisplayed()
        compose.onNodeWithText("Family moments").performClick()
        compose.onNodeWithTag("album-hero").assertIsDisplayed()
        compose.onNodeWithTag("gallery-grid").performScrollToKey(sharedVideo)
        val video = runBlocking { repository.observeGallery().first().first { it.id == sharedVideo } }
        compose.onNodeWithTag("media-$sharedVideo").assertTextContains(requireNotNull(mediaDuration(video.durationMillis)))
        compose.onNodeWithContentDescription(context.getString(R.string.back_to_collections)).performClick()
        compose.onNodeWithText("Family moments").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.back_to_collections)).performClick()
        compose.onNodeWithTag("collection-albums").performClick()
        compose.onNodeWithText("Journeys").performClick()
        compose.onNodeWithText("By the sea").performClick()
        compose.onNodeWithTag("album-hero").assertIsDisplayed()
    }
}
