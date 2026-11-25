package me.lvca.alba.app.gallery

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.screenshots.ScreenshotFixtures
import me.lvca.alba.app.testing.setAlbaContent
import me.lvca.alba.app.ui.designsystem.theme.AlbaLightColorScheme
import me.lvca.alba.app.ui.designsystem.theme.floating
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** The floating destination capsule: what it leaves clear and how quickly it answers. */
@RunWith(AndroidJUnit4::class)
class FloatingNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun libraryFooterEndsClearOfTheFloatingNavigation() {
        compose.setAlbaContent(ScreenshotFixtures.gallery(), ScreenshotFixtures.signedIn)
        val counts = hasText(context.getString(R.string.cloud_phone_counts, "", ""), substring = true)
        compose.onNodeWithTag("gallery-grid").performScrollToNode(counts)
        compose.onNodeWithTag("gallery-grid").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val footer = compose.onNode(counts).fetchSemanticsNode().boundsInRoot
        val navigation = compose.onNodeWithTag("gallery-destination-capsule").fetchSemanticsNode().boundsInRoot
        val gap = with(compose.density) { 16.dp.toPx() }
        assertTrue("footer $footer overlaps navigation $navigation", footer.bottom + gap <= navigation.top)
    }

    @Test fun tabTapAnswersAtOnceAndSwitchesOnceTheScreenHasFaded() {
        compose.setAlbaContent(ScreenshotFixtures.gallery(), ScreenshotFixtures.signedIn)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("tab-COLLECTIONS").performClick()
            compose.mainClock.advanceTimeByFrame()
            // The navigation answers in the first frame; Photos fades out before Collections composes.
            compose.onNodeWithTag("tab-COLLECTIONS").assertIsSelected()
            compose.onAllNodesWithTag("collection-albums").assertCountEquals(0)
            compose.mainClock.advanceTimeBy(500)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.onNodeWithTag("collection-albums").assertIsDisplayed()
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        compose.onNodeWithTag("gallery-grid").assertIsDisplayed()
        compose.onNodeWithTag("tab-LIBRARY").assertIsSelected()
    }

    @Test fun searchLeavesNoPillInTheCapsule() {
        compose.setAlbaContent(ScreenshotFixtures.gallery(), ScreenshotFixtures.signedIn)
        val floating = AlbaLightColorScheme.floating

        // Just inside Photos' start edge, level with its centre: on the pill when Photos is selected, and clear of its label.
        fun photosStart(): Color {
            val node = compose.onNodeWithTag("tab-LIBRARY")
            val image = node.captureToImage().toPixelMap()
            return image[with(compose.density) { 3.dp.roundToPx() }, image.height / 2]
        }
        fun Color.distanceTo(other: Color) = abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue)
        val selected = photosStart()
        assertTrue("Photos has its pill", selected.distanceTo(floating.active) < selected.distanceTo(floating.container))
        compose.onNodeWithTag("tab-SEARCH").performClick()
        compose.waitForIdle()
        val searching = photosStart()
        assertTrue("no pill is left under Photos", searching.distanceTo(floating.container) < searching.distanceTo(floating.active))
    }
}
