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
import me.lvca.alba.app.testing.Screenshots
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.openGalleryMedia
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForGalleryMedia
import me.lvca.alba.app.testing.waitForVideoPlaying
import me.lvca.alba.app.testing.waitUntilDisplayed
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
    fun demoVideoActuallyPlaysAndCapturesFeatureScreens() {
        compose.waitForGalleryMedia()
        compose.scrollToGalleryMedia("fixture:icloud:0019")
        // scripts/run_android_tests.sh collects the features-* captures.
        Screenshots.capture("features-gallery")
        compose.onNodeWithTag("media-fixture:icloud:0019").performClick()
        compose.waitForVideoPlaying()
        Screenshots.capture("features-video")
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForVideoPlaying()
        compose.openViewerDetails()
        if (hasNodeWithText("Remove offline copy")) {
            compose.onNodeWithText("Remove offline copy").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = UI_WAIT) { hasNodeWithText("Keep original offline") }
        }
        compose.onNodeWithText("Keep original offline").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT) { hasNodeWithText("Remove offline copy") }
        compose.pressBack()
        compose.onNodeWithContentDescription("Back to gallery").performClick()
        compose.waitUntil(timeoutMillis = SHORT_WAIT) {
            compose.onAllNodes(hasTestTag("video-player")).fetchSemanticsNodes().isEmpty()
        }
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
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").performTouchInput { advanceEventTime(500); click() }
        compose.mainClock.advanceTimeBy(600)
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithContentDescription("Share").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Share").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to gallery").performClick()
        compose.openGalleryMedia("fixture:icloud:0001")
        compose.onNodeWithTag("viewer-image-fixture:icloud:0001").assertIsDisplayed()
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

    private fun hasNodeWithText(text: String): Boolean =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
}
