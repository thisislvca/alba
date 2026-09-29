package dev.mela.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.geometry.Offset
import org.junit.Rule
import org.junit.Test
import java.io.File

class AppSurfacesTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun label(id: Int) = compose.activity.getString(id)
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val dir = compose.activity.getExternalFilesDir("app-surfaces")!!.apply { mkdirs() }
        val night = compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        File(dir, "$name-$night.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun utilitySheetsAndProfileScrollUnderGestureArea() {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("media-fixture:icloud:0020").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("account-button").performClick()
        val viewport = compose.onNodeWithTag("mela-page-scroll").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals("Scrolling must continue to the window edge, not stop above the gesture bar", root.bottom, viewport.bottom, 1f)
        capture("profile-edge")
        compose.onNodeWithTag("profile-about").performScrollTo().performClick()
        capture("about")
        androidx.test.espresso.Espresso.pressBack()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("activity-button").performClick()
        compose.onNodeWithText(label(R.string.no_transfers_yet)).assertIsDisplayed()
        capture("activity")
        compose.onNodeWithContentDescription(label(R.string.close_surface)).performClick()
        compose.onNodeWithTag("filter-button").performClick()
        val sheet = compose.onNodeWithTag("gallery-filter-sheet").fetchSemanticsNode().boundsInRoot
        val screenHeight = compose.activity.resources.displayMetrics.heightPixels
        assertTrue("Sheet must leave visible space above its handle", sheet.top >= screenHeight * .15f)
        compose.onNodeWithText(label(R.string.small)).performClick().assertIsSelected()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(label(R.string.small)).assertIsSelected()
        compose.onNodeWithText(label(R.string.medium)).performClick()
        capture("filters-top")
        compose.onNodeWithText(label(R.string.refresh_library)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.close_surface)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.show_photos)).assertIsDisplayed()
        capture("filters-bottom")
        compose.onNodeWithTag("gallery-filter-sheet").performTouchInput {
            swipe(Offset(center.x, 8f), Offset(center.x, height - 1f), 400)
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("gallery-filter-sheet").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription(label(R.string.browse_by_date)).performClick()
        capture("dates")
        compose.onNodeWithContentDescription(label(R.string.close_surface)).performClick()
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        capture("collections")
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText(label(R.string.media_types)))
        capture("collection-types")
    }
}
