package me.lvca.alba.app.albums

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
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
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.R
import me.lvca.alba.app.screenshots.ScreenshotFixtures
import me.lvca.alba.app.testing.setAlbaContent
import me.lvca.alba.engine.model.SmartCollection
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Collections hub from fixed state: counts under each section, and media types with nothing in them left out. */
@RunWith(AndroidJUnit4::class)
class CollectionsHubTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<AlbaApplication>()

    private fun openCollections(screenshots: Int = 0) {
        val gallery = ScreenshotFixtures.gallery()
        val items = gallery.items.mapIndexed { index, media ->
            if (index < screenshots) media.copy(collectionIds = setOf(SmartCollection.SCREENSHOTS.id)) else media
        }
        compose.setAlbaContent(gallery.copy(items = items, catalogItems = items), ScreenshotFixtures.signedIn)
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-albums").assertIsDisplayed()
    }

    @Test fun emptyMediaTypesAndVideosStayOutOfTheHub() {
        openCollections()
        // No photo in the fixtures belongs to a smart collection: no Videos card, no Media types heading or zero rows.
        compose.onAllNodesWithTag("collection-videos").assertCountEquals(0)
        compose.onAllNodesWithTag("collection-media-types").assertCountEquals(0)
        compose.onAllNodesWithTag("collection-screenshots").assertCountEquals(0)
        compose.onAllNodes(hasText(app.getString(R.string.media_types))).assertCountEquals(0)
        // Hidden and the phone access row are still offered.
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasTestTag("collection-hidden"))
        compose.onNodeWithTag("collection-hidden").assertIsDisplayed()
    }

    @Test fun aMediaTypeWithPhotosShowsItsCountAndSectionsShowTheirs() {
        openCollections(screenshots = 2)
        // The fixtures have three personal albums and one shared album; the shared hub has no photos to show yet.
        compose.onNode(hasText(app.resources.getQuantityString(R.plurals.album_count, 3, 3))).assertIsDisplayed()
        compose.onNode(hasText(app.resources.getQuantityString(R.plurals.album_count, 1, 1))).assertIsDisplayed()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasTestTag("collection-shared"))
        compose.onNodeWithTag("collection-shared-empty", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasTestTag("collection-screenshots"))
        compose.onNodeWithTag("collection-screenshots").assertIsDisplayed()
        compose.onAllNodesWithTag("collection-live_photos").assertCountEquals(0)
        compose.onNodeWithTag("collection-screenshots").assert(hasText("2"))
    }

    @Test fun lastRowScrollsClearOfTheFloatingNavigation() {
        openCollections()
        val phone = hasText(app.getString(R.string.this_phone))
        compose.onNodeWithTag("collections-grid").performScrollToNode(phone)
        // Past the end: the grid's bottom padding is all that keeps the row above the navigation.
        compose.onNodeWithTag("collections-grid").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val last = compose.onNode(phone).fetchSemanticsNode().boundsInRoot
        val navigation = compose.onNodeWithTag("gallery-destination-capsule").fetchSemanticsNode().boundsInRoot
        val gap = with(compose.density) { 16.dp.toPx() }
        assertTrue("last row $last overlaps navigation $navigation", last.bottom + gap <= navigation.top)
    }
}
