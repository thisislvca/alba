package me.lvca.alba.app.gallery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
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
import kotlinx.coroutines.flow.first
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.runBlockingWithMainLooper
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The gallery's utility surfaces: profile, activity, filter and date sheets, and the collections list. */
@RunWith(AndroidJUnit4::class)
class AppSurfacesTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose

    @Before fun galleryLoaded() = compose.waitForGalleryMedia()

    @Test fun profileScrollsToTheWindowEdgeAndAboutRestoresWithoutLeavingTheAccount() {
        compose.onNodeWithTag("account-button").performClick()
        val viewport = compose.onNodeWithTag("alba-page-scroll").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals("Scrolling must continue to the window edge, not stop above the gesture bar", root.bottom, viewport.bottom, 1f)
        compose.onNodeWithTag("profile-about").performScrollTo().performClick()
        compose.waitUntilExists("about-screen")
        val info = app.activity.packageManager.getPackageInfo(app.activity.packageName, 0)
        compose.onNodeWithText(app.string(R.string.about_version, "${info.versionName} (${info.longVersionCode})")).assertIsDisplayed()
        compose.onNodeWithTag("about-source").assertHasClickAction()
        // The expanded limitations and the open About screen survive recreation.
        compose.onNodeWithText(app.string(R.string.about_missing_support)).assertDoesNotExist()
        compose.onNodeWithTag("about-limitations").performScrollTo().performClick()
        app.recreate()
        compose.waitUntilExists("about-screen")
        compose.onNodeWithText(app.string(R.string.about_missing_support)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("about-notices").performScrollTo().performClick()
        compose.onNodeWithText("The MIT License", substring = true).performScrollTo().assertExists()
        compose.pressBack()
        compose.waitUntilGone("about-screen")
        compose.onNodeWithTag("profile-about").performScrollTo().assertIsDisplayed()
    }

    @Test fun filterSheetLeavesSpaceAboveItAndKeepsDensityAcrossRecreation() {
        compose.onNodeWithContentDescription(app.string(R.string.filter_and_sort)).performClick()
        val sheet = compose.onNodeWithTag("gallery-filter-sheet").fetchSemanticsNode().boundsInRoot
        val screenHeight = app.activity.resources.displayMetrics.heightPixels
        assertTrue("Sheet must leave visible space above its handle", sheet.top >= screenHeight * .15f)
        compose.onNodeWithText(app.string(R.string.small)).performClick().assertIsSelected()
        app.recreate()
        compose.onNodeWithText(app.string(R.string.small)).assertIsSelected()
        compose.onNodeWithText(app.string(R.string.medium)).performClick().assertIsSelected()
        compose.onNodeWithText(app.string(R.string.refresh_library)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(app.string(R.string.close_surface)).assertIsDisplayed()
        compose.onNodeWithText(app.string(R.string.show_photos)).assertIsDisplayed()
    }

    @Test fun filterChoicesSurviveRecreationAndDoNotLeakIntoCollections() {
        compose.onNodeWithContentDescription(app.string(R.string.filter_and_sort)).performClick()
        val choices = listOf(R.string.favorites, R.string.oldest_first, R.string.added).map(app::string)
        choices.forEach { compose.onNodeWithText(it, useUnmergedTree = true).performScrollTo().performClick() }
        app.recreate()
        choices.forEach { compose.onNodeWithText(it).performScrollTo().assertIsSelected() }
        compose.onNodeWithText(app.string(R.string.show_photos)).performClick()
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-albums").assertIsDisplayed()
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        compose.onNodeWithContentDescription(app.string(R.string.filter_and_sort)).performClick()
        choices.forEach { compose.onNodeWithText(it).performScrollTo().assertIsSelected() }
    }

    @Test fun filterSheetSwipesAway() {
        compose.onNodeWithContentDescription(app.string(R.string.filter_and_sort)).performClick()
        compose.onNodeWithTag("gallery-filter-sheet").performTouchInput {
            swipe(Offset(center.x, 8f), Offset(center.x, height - 1f), 400)
        }
        compose.waitUntilGone("gallery-filter-sheet")
    }

    @Test fun activityAndDateSheetsOpenAndCloseAndAMonthJumpReachesItsPhotos() {
        compose.onNodeWithTag("activity-button").performClick()
        compose.onNodeWithText(app.string(R.string.uploads_downloads_and_backup)).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.string(R.string.close_surface)).performClick()
        compose.onNodeWithText(app.string(R.string.uploads_downloads_and_backup)).assertDoesNotExist()
        compose.showFloatingDate().performClick()
        compose.waitUntilExists("date-months")
        compose.onNodeWithContentDescription(app.string(R.string.close_surface)).performClick()
        compose.waitUntilGone("date-months")
        // The demo spans several months; choosing the earliest jumps to that month's photos.
        val oldest = runBlockingWithMainLooper {
            app.application.graph.galleryRepository.observeGallery().first().filterNot { it.id.startsWith("shared:") }
                .minBy { it.capturedAtEpochMillis }
        }
        val label = Instant.ofEpochMilli(oldest.capturedAtEpochMillis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        compose.showFloatingDate().performClick()
        compose.onNodeWithTag("date-months").performScrollToNode(hasText(label))
        compose.onAllNodesWithText(label).onLast().performClick()
        compose.waitUntilExists("media-${oldest.id}")
    }

    @Test fun openFolderAndAlbumSurviveRecreation() {
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-albums").performClick()
        // The demo's albums arrive with the catalog, which loads in the background.
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithText("Journeys").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Journeys").performClick()
        app.recreate()
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithText("By the sea").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("By the sea").performClick()
        compose.waitUntilExists("album-hero")
        app.recreate()
        compose.waitUntilExists("album-hero")
        compose.onNodeWithText("By the sea").assertIsDisplayed()
    }

    @Test fun collectionsScrollToMediaTypes() {
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText(app.string(R.string.media_types)))
        compose.onNodeWithText(app.string(R.string.media_types)).assertIsDisplayed()
    }
}
