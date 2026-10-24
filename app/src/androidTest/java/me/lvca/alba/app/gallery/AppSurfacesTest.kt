package me.lvca.alba.app.gallery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForGalleryMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppSurfacesTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private fun label(id: Int) = compose.activity.getString(id)
    @Test fun utilitySheetsAndProfileScrollUnderGestureArea() {
        compose.waitForGalleryMedia()
        compose.onNodeWithTag("account-button").performClick()
        val viewport = compose.onNodeWithTag("alba-page-scroll").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals("Scrolling must continue to the window edge, not stop above the gesture bar", root.bottom, viewport.bottom, 1f)
        compose.onNodeWithTag("profile-about").performScrollTo().performClick()
        compose.pressBack()
        compose.pressBack()
        compose.onNodeWithTag("activity-button").performClick()
        // Earlier tests in this process may leave transfer history, so check the sheet rather than its empty state.
        compose.onNodeWithText(label(R.string.uploads_downloads_and_backup)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.close_surface)).performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.filter_and_sort)).performClick()
        val sheet = compose.onNodeWithTag("gallery-filter-sheet").fetchSemanticsNode().boundsInRoot
        val screenHeight = compose.activity.resources.displayMetrics.heightPixels
        assertTrue("Sheet must leave visible space above its handle", sheet.top >= screenHeight * .15f)
        // GalleryNavigationTest checks that the sheet and its choices survive recreation.
        compose.onNodeWithText(label(R.string.small)).performClick().assertIsSelected()
        compose.onNodeWithText(label(R.string.medium)).performClick()
        compose.onNodeWithText(label(R.string.refresh_library)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.close_surface)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.show_photos)).assertIsDisplayed()
        compose.onNodeWithTag("gallery-filter-sheet").performTouchInput {
            swipe(Offset(center.x, 8f), Offset(center.x, height - 1f), 400)
        }
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithTag("gallery-filter-sheet").fetchSemanticsNodes().isEmpty() }
        compose.scrollToGalleryMedia("fixture:icloud:0016")
        compose.onNodeWithTag("floating-date-button").performClick()
        compose.onNodeWithContentDescription(label(R.string.close_surface)).performClick()
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText(label(R.string.media_types)))
    }
}
