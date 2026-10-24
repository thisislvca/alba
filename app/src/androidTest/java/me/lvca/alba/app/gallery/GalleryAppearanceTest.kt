package me.lvca.alba.app.gallery

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.withLongLibrary
import me.lvca.alba.app.ui.gallery.mediaDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Exercises the new entry points against the real demo repository and video previews. */
class GalleryAppearanceTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private val context get() = compose.activity

    @Test fun statusBarAreaIsStronglyTintedOverScrolledPhotos() {
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("shared-albums-strip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("gallery-grid").performTouchInput { swipeUp() }
        compose.waitForIdle()
        // Like Google Photos, photos stay faintly visible under the clock, but the tint keeps every pixel close to
        // the header colour so the status icons stay readable over any photo.
        val scrim = compose.onNodeWithTag("gallery-status-scrim").captureToImage().toPixelMap()
        val colors = (0 until scrim.width step 6).flatMap { x -> (0 until scrim.height step 3).map { y -> scrim[x, y] } }
        val base = colors.first()
        colors.forEach { color ->
            val spread = maxOf(abs(color.red - base.red), abs(color.green - base.green), abs(color.blue - base.blue))
            assertTrue("$color strays from the tint $base", spread <= .16f)
        }
    }

    @Test fun libraryFooterEndsClearOfTheFloatingNavigation() {
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("shared-albums-strip").fetchSemanticsNodes().isNotEmpty() }
        val counts = hasText(context.getString(R.string.cloud_phone_counts, "", ""), substring = true)
        compose.onNodeWithTag("gallery-grid").performScrollToNode(counts)
        compose.onNodeWithTag("gallery-grid").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val footer = compose.onNode(counts).fetchSemanticsNode().boundsInRoot
        val navigation = compose.onNodeWithTag("gallery-destination-capsule").fetchSemanticsNode().boundsInRoot
        val gap = with(compose.density) { 16.dp.toPx() }
        assertTrue("footer $footer overlaps navigation $navigation", footer.bottom + gap <= navigation.top)
    }

    @Test fun photosCollectionsSharedAlbumsAndVideoCovers() {
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("shared-albums-strip").fetchSemanticsNodes().isNotEmpty() }
        val repository = (context.application as AlbaApplication).graph.galleryRepository
        val sharedVideo = "shared:demo-library:private:owner:two:video"
        runBlocking { repository.ensurePreview(sharedVideo) }
        compose.waitForIdle()
        // The date scrubber only appears on grids of 100 items or more.
        compose.withLongLibrary {
            val rail = compose.onNodeWithTag("gallery-fast-scroll")
            rail.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.35f) }
            compose.waitForIdle()
            val handle = compose.onNodeWithTag("fast-scroll-handle").fetchSemanticsNode().boundsInRoot
            val track = rail.fetchSemanticsNode().boundsInRoot
            rail.performTouchInput { down(handle.center - track.topLeft) }
            compose.onAllNodes(SemanticsMatcher("fast-scroll year label") { node ->
                node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }?.startsWith("fast-scroll-year-") == true
            }).onFirst().assertIsDisplayed()
            rail.performTouchInput { up() }
        }
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
        compose.onNodeWithContentDescription(context.getString(R.string.back_action)).performClick()
        compose.onNodeWithText("Family moments").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.back_action)).performClick()
        compose.onNodeWithTag("collection-albums").performClick()
        compose.onNodeWithText("Journeys").performClick()
        compose.onNodeWithText("By the sea").performClick()
        compose.onNodeWithTag("album-hero").assertIsDisplayed()
    }
}
