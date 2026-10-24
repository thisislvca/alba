package me.lvca.alba.app.viewer

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.ScreenshotCapture
import me.lvca.alba.app.testing.Screenshots
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.openGalleryMedia
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForGalleryMedia
import me.lvca.alba.app.testing.waitForVideoPlaying
import me.lvca.alba.app.testing.waitUntilDisplayed
import me.lvca.alba.app.ui.viewer.ViewerSpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ViewerTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private fun player(view: View = compose.activity.window.decorView): Player? {
        if (view is PlayerView) return view.player
        if (view is ViewGroup) for (i in 0 until view.childCount) player(view.getChildAt(i))?.let { return it }
        return null
    }

    @Test
    fun browseFixtureAndKeepAnOriginalOffline() {
        compose.waitForGalleryMedia()
        compose.onNodeWithTag("tab-LIBRARY").assertIsSelected()

        compose.scrollToGalleryMedia("fixture:icloud:0001")
        compose.onNodeWithTag("media-fixture:icloud:0001").performClick()
        compose.waitUntilDisplayed(hasContentDescription("IMG_8421.JPG"))
        compose.openViewerDetails()
        compose.onAllNodesWithText("IMG_8421.JPG").onFirst().assertIsDisplayed()

        compose.waitUntil(timeoutMillis = UI_WAIT) {
            hasNodeWithText("Keep original offline") || hasNodeWithText("Remove offline copy")
        }
        if (hasNodeWithText("Remove offline copy")) {
            compose.onNodeWithText("Remove offline copy").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = UI_WAIT) {
                hasNodeWithText("Keep original offline")
            }
        }
        compose.onNodeWithText("Keep original offline").performScrollTo().performClick()

        compose.waitUntil(timeoutMillis = UI_WAIT) {
            hasNodeWithText("Remove offline copy")
        }
        compose.onNodeWithText("Remove offline copy").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun demoVideoPlaysResumesAfterTheAppReturnsAndClosesItsPlayer() {
        compose.waitForGalleryMedia()
        compose.openGalleryMedia("fixture:icloud:0019")
        compose.waitForVideoPlaying()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForVideoPlaying()
        compose.onNodeWithContentDescription("Back to gallery").performClick()
        compose.waitUntil(timeoutMillis = SHORT_WAIT) {
            compose.onAllNodes(hasTestTag("video-player")).fetchSemanticsNodes().isEmpty()
        }
    }

    /** README feature captures, which scripts/run_android_tests.sh collects. Runs only when asked for; see [ScreenshotCapture]. */
    @ScreenshotCapture
    @Test
    fun captureFeatureScreens() {
        compose.waitForGalleryMedia()
        compose.scrollToGalleryMedia("fixture:icloud:0019")
        Screenshots.capture("features-gallery")
        compose.onNodeWithTag("media-fixture:icloud:0019").performClick()
        compose.waitForVideoPlaying()
        Screenshots.capture("features-video")
    }

    @Test fun viewerSwipesZoomsHidesControlsAndRestoresSelectedPhoto() {
        compose.waitForGalleryMedia()
        compose.openGalleryMedia("fixture:icloud:0016")
        compose.onNodeWithTag("media-pager").performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width * 0.8f, height * 0.5f), androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.5f), 350) }
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").assertIsDisplayed()
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").performTouchInput { doubleClick() }
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoomed"))
        compose.onNodeWithTag("media-pager").performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width * 0.8f, height * 0.5f), androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.5f), 350) }
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").assertIsDisplayed()
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").performTouchInput { doubleClick() }
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Fit"))
        // These are independent single taps, not another double tap on the injected event clock.
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").performTouchInput { advanceEventTime(500); click() }
        // detectTapGestures defers single taps while waiting for a possible second tap.
        // waitUntil uses wall time; advance the gesture coroutine's Compose clock too.
        compose.mainClock.advanceTimeBy(600)
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithContentDescription("Share").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Share").assertDoesNotExist()
        // GP hides the system bars with the chrome.
        compose.waitUntil(SHORT_WAIT) { statusBarsVisible() == false }
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").performTouchInput { advanceEventTime(500); click() }
        compose.mainClock.advanceTimeBy(600)
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithContentDescription("Share").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Share").assertIsDisplayed()
        compose.waitUntil(SHORT_WAIT) { statusBarsVisible() == true }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to gallery").performClick()
        compose.openGalleryMedia("fixture:icloud:0001")
        compose.onNodeWithTag("viewer-image-fixture:icloud:0001").assertIsDisplayed()
    }

    @Test fun tappingAPlayingVideoBringsBackTheWholeChrome() {
        compose.waitForGalleryMedia()
        compose.openGalleryMedia("fixture:icloud:0019")
        compose.waitUntil(UI_WAIT) { compose.runOnIdle { player()?.isPlaying == true } }
        // Playback hides the chrome after a few seconds.
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithContentDescription("Share").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithTag("floating-viewer-back").assertCountEquals(0)
        // Hold the test clock: with it advancing on its own, waiting for idle would also run the 3 s auto-hide.
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("video-player").performClick()
            compose.mainClock.advanceTimeBy(ViewerSpec.CHROME_FADE_MILLIS + 100L)
            // Like Google Photos, the tap brings back the top bar and actions with the playback controls, without pausing.
            compose.onAllNodesWithContentDescription("Share").onFirst().assertExists()
            compose.onNodeWithTag("floating-viewer-back").assertIsDisplayed()
            compose.onNodeWithTag("video-play-pause").assertIsDisplayed()
            assertTrue("The tap must not pause playback", compose.runOnUiThread { player()?.isPlaying } == true)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun swipingAwayFromVideoReleasesPlayback() {
        compose.waitForGalleryMedia()
        compose.openGalleryMedia("fixture:icloud:0019")
        compose.onNodeWithTag("video-player").assertIsDisplayed()
        compose.onNodeWithTag("media-pager").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width * 0.8f, height * 0.5f), androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.5f), 350)
        }
        compose.onNodeWithTag("viewer-image-fixture:icloud:0001").assertIsDisplayed()
        compose.onNodeWithTag("video-player").assertDoesNotExist()
    }

    @Test fun pausedVideoRetainsPositionAcrossActivityRecreation() {
        compose.waitForGalleryMedia(); compose.openGalleryMedia("fixture:icloud:0019")
        compose.waitUntil(UI_WAIT) { compose.runOnIdle { player()?.playbackState == Player.STATE_READY } }
        compose.runOnIdle { player()!!.apply { pause(); seekTo(1200) } }
        compose.waitUntil(SHORT_WAIT) { compose.runOnIdle { player()!!.currentPosition >= 1100 } }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(UI_WAIT) { compose.runOnIdle { player()?.playbackState == Player.STATE_READY } }
        compose.runOnIdle {
            assertFalse(player()!!.playWhenReady)
            assertTrue("Playback position should survive recreation", player()!!.currentPosition in 1100..1400)
        }
    }

    @Test fun pinchingInAtFitClosesTheViewerAndASlightPinchReturns() {
        compose.waitForGalleryMedia()
        compose.openGalleryMedia("fixture:icloud:0016")
        val photo = compose.onNodeWithTag("viewer-image-fixture:icloud:0016")
        // A slight pinch (about 0.9×) springs back to fit and keeps the viewer open.
        photo.performTouchInput {
            pinch(center - androidx.compose.ui.geometry.Offset(width * .3f, 0f), center - androidx.compose.ui.geometry.Offset(width * .27f, 0f),
                center + androidx.compose.ui.geometry.Offset(width * .3f, 0f), center + androidx.compose.ui.geometry.Offset(width * .27f, 0f), 300)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("media-pager").assertExists()
        photo.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Fit"))
        // Pinching well in closes back to the grid, like a swipe down.
        photo.performTouchInput {
            pinch(center - androidx.compose.ui.geometry.Offset(width * .35f, 0f), center - androidx.compose.ui.geometry.Offset(width * .08f, 0f),
                center + androidx.compose.ui.geometry.Offset(width * .35f, 0f), center + androidx.compose.ui.geometry.Offset(width * .08f, 0f), 300)
        }
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("media-fixture:icloud:0016").assertExists()
    }

    @Test fun doubleTapAndDragZoomsAroundTheTapAndKeepsTheZoom() {
        compose.waitForGalleryMedia()
        compose.openGalleryMedia("fixture:icloud:0016")
        val photo = compose.onNodeWithTag("viewer-image-fixture:icloud:0016")
        val zoomed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoomed")
        val fit = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Fit")
        // Tap, then keep the second touch down and drag it down: GP's quick scale zooms in and stays zoomed on release.
        photo.performTouchInput {
            click(center)
            advanceEventTime(100)
            down(center)
            repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(0f, 25f)) }
            up()
        }
        compose.waitForIdle()
        photo.assert(zoomed)
        compose.onNodeWithTag("media-pager").assertExists()
        // Dragging back up past where it started returns to fit.
        photo.performTouchInput {
            advanceEventTime(500)
            click(center)
            advanceEventTime(100)
            down(center)
            repeat(12) { moveBy(androidx.compose.ui.geometry.Offset(0f, -25f)) }
            up()
        }
        compose.waitForIdle()
        photo.assert(fit)
    }

    private fun statusBarsVisible(): Boolean? = compose.runOnIdle {
        androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars())
    }

    private fun hasNodeWithText(text: String): Boolean =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
}
