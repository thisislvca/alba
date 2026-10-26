package me.lvca.alba.app.viewer

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.ScreenshotCapture
import me.lvca.alba.app.testing.Screenshots
import me.lvca.alba.app.testing.Smoke
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.openGalleryMedia
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForVideoPlaying
import me.lvca.alba.app.ui.viewer.ViewerSpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ViewerTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose
    private fun player(view: View = compose.activity.window.decorView): Player? {
        if (view is PlayerView) return view.player
        if (view is ViewGroup) for (i in 0 until view.childCount) player(view.getChildAt(i))?.let { return it }
        return null
    }

    @Smoke @Test
    fun demoVideoPlaysResumesAfterTheAppReturnsAndReleasesItsPlayerOnSwipeAndBack() {
        compose.openGalleryMedia("fixture:icloud:0019")
        compose.waitForVideoPlaying()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForVideoPlaying()
        // Swiping on to the next photo releases the player; swiping back brings one back.
        compose.onNodeWithTag("media-pager").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width * 0.8f, height * 0.5f), androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.5f), 350)
        }
        compose.onNodeWithTag("viewer-image-fixture:icloud:0001").assertIsDisplayed()
        compose.onNodeWithTag("video-player").assertDoesNotExist()
        compose.onNodeWithTag("media-pager").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.5f), androidx.compose.ui.geometry.Offset(width * 0.8f, height * 0.5f), 350)
        }
        compose.waitUntil(UI_WAIT) { compose.onAllNodes(hasTestTag("video-player")).fetchSemanticsNodes().isNotEmpty() }
        // Playback hides the chrome, Back button included, after a few seconds on the test clock, and a tap toggles
        // it, so tapping the button races that timer. System back closes the viewer whatever the chrome shows.
        compose.pressBack()
        compose.waitUntil(timeoutMillis = SHORT_WAIT) {
            compose.onAllNodes(hasTestTag("video-player")).fetchSemanticsNodes().isEmpty()
        }
    }

    /** README feature captures, which scripts/run_android_tests.sh collects. Runs only when asked for; see [ScreenshotCapture]. */
    @ScreenshotCapture
    @Test
    fun captureFeatureScreens() {
        compose.scrollToGalleryMedia("fixture:icloud:0019")
        Screenshots.capture("features-gallery")
        compose.onNodeWithTag("media-fixture:icloud:0019").performClick()
        compose.waitForVideoPlaying()
        Screenshots.capture("features-video")
    }

    @Test fun viewerSwipesZoomsHidesControlsAndRestoresSelectedPhoto() {
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
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithTag("viewer-share").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("viewer-share").assertDoesNotExist()
        // GP hides the system bars with the chrome.
        compose.waitUntil(SHORT_WAIT) { statusBarsVisible() == false }
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").performTouchInput { advanceEventTime(500); click() }
        compose.mainClock.advanceTimeBy(600)
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithTag("viewer-share").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("viewer-share").assertIsDisplayed()
        compose.waitUntil(SHORT_WAIT) { statusBarsVisible() == true }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").assertIsDisplayed()
        compose.onNodeWithTag("floating-viewer-back").performClick()
        compose.openGalleryMedia("fixture:icloud:0001")
        compose.onNodeWithTag("viewer-image-fixture:icloud:0001").assertIsDisplayed()
    }

    @Smoke @Test fun tappingAPlayingVideoBringsBackTheWholeChrome() {
        compose.openGalleryMedia("fixture:icloud:0019")
        compose.waitUntil(UI_WAIT) { compose.runOnIdle { player()?.isPlaying == true } }
        // Playback hides the chrome after a few seconds.
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("viewer-share").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithTag("floating-viewer-back").assertCountEquals(0)
        // Hold the test clock: with it advancing on its own, waiting for idle would also run the 3 s auto-hide.
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("video-player").performClick()
            compose.mainClock.advanceTimeBy(ViewerSpec.CHROME_FADE_MILLIS + 100L)
            // Like Google Photos, the tap brings back the top bar and actions with the playback controls, without pausing.
            compose.onNodeWithTag("viewer-share").assertExists()
            compose.onNodeWithTag("floating-viewer-back").assertIsDisplayed()
            compose.onNodeWithTag("video-play-pause").assertIsDisplayed()
            assertTrue("The tap must not pause playback", compose.runOnUiThread { player()?.isPlaying } == true)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Smoke @Test fun pausedVideoRetainsPositionAcrossActivityRecreation() {
        compose.openGalleryMedia("fixture:icloud:0019")
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

    private fun statusBarsVisible(): Boolean? = compose.runOnIdle {
        androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars())
    }
}
