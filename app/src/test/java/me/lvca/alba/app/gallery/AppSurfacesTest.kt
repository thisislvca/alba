package me.lvca.alba.app.gallery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.showFloatingDate
import me.lvca.alba.app.testing.waitForGalleryMedia
import me.lvca.alba.app.testing.waitUntilExists
import me.lvca.alba.app.testing.waitUntilGone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The gallery's utility surfaces: profile, activity, filter and date sheets, and the collections list. */
@RunWith(AndroidJUnit4::class)
class AppSurfacesTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose

    @Before fun galleryLoaded() = compose.waitForGalleryMedia()

    @Test fun profileScrollsToTheWindowEdgeAndOpensAbout() {
        compose.onNodeWithTag("account-button").performClick()
        val viewport = compose.onNodeWithTag("alba-page-scroll").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals("Scrolling must continue to the window edge, not stop above the gesture bar", root.bottom, viewport.bottom, 1f)
        compose.onNodeWithTag("profile-about").performScrollTo().performClick()
        compose.waitUntilExists("about-screen")
        compose.pressBack()
        compose.waitUntilGone("about-screen")
    }

    @Test fun activitySheetOpensAndCloses() {
        compose.onNodeWithTag("activity-button").performClick()
        compose.onNodeWithText(app.string(R.string.uploads_downloads_and_backup)).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.string(R.string.close_surface)).performClick()
        compose.onNodeWithText(app.string(R.string.uploads_downloads_and_backup)).assertDoesNotExist()
    }

    @Test fun filterSheetLeavesSpaceAboveItAndChangesDensity() {
        compose.onNodeWithContentDescription(app.string(R.string.filter_and_sort)).performClick()
        val sheet = compose.onNodeWithTag("gallery-filter-sheet").fetchSemanticsNode().boundsInRoot
        val screenHeight = app.activity.resources.displayMetrics.heightPixels
        assertTrue("Sheet must leave visible space above its handle", sheet.top >= screenHeight * .15f)
        compose.onNodeWithText(app.string(R.string.small)).performClick().assertIsSelected()
        compose.onNodeWithText(app.string(R.string.medium)).performClick().assertIsSelected()
        compose.onNodeWithText(app.string(R.string.refresh_library)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(app.string(R.string.close_surface)).assertIsDisplayed()
        compose.onNodeWithText(app.string(R.string.show_photos)).assertIsDisplayed()
    }

    @Test fun filterSheetSwipesAway() {
        compose.onNodeWithContentDescription(app.string(R.string.filter_and_sort)).performClick()
        compose.onNodeWithTag("gallery-filter-sheet").performTouchInput {
            swipe(Offset(center.x, 8f), Offset(center.x, height - 1f), 400)
        }
        compose.waitUntilGone("gallery-filter-sheet")
    }

    @Test fun floatingDateOpensTheDateBrowser() {
        compose.showFloatingDate().performClick()
        compose.waitUntilExists("date-months")
        compose.onNodeWithContentDescription(app.string(R.string.close_surface)).performClick()
        compose.waitUntilGone("date-months")
    }

    @Test fun collectionsScrollToMediaTypes() {
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText(app.string(R.string.media_types)))
    }
}
