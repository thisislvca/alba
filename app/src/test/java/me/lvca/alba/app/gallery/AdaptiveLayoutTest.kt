package me.lvca.alba.app.gallery

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.backGesture
import me.lvca.alba.app.testing.openGalleryMedia
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.waitUntilExists
import me.lvca.alba.app.ui.adaptive.WindowWidth
import me.lvca.alba.app.ui.gallery.GridLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * The window size classes' layouts: the floating capsule or a rail, Collections as one pane or list-detail, a photo's
 * Info as a sheet or a side pane, and a photo grid whose columns follow the window rather than stretching.
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose
    private val photo = "fixture:icloud:0001"

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private val inRail = hasAnyAncestor(hasTestTag("navigation-rail"))

    /** The photo grid's columns, from how wide its tiles are: the grid's width over a tile and its 2dp gap. */
    private fun gridColumns(): Int {
        val tile = compose.onAllNodes(SemanticsMatcher("gallery media tile") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("media-") == true
        }).fetchSemanticsNodes().map { it.boundsInRoot }.first { it.height > 0 }
        val gap = 2f * compose.density.density
        return ((bounds("gallery-grid").width + gap) / (tile.width + gap)).roundToInt()
    }

    private fun gridWidthDp(): Float = bounds("gallery-grid").width / compose.density.density

    /** Opens the photo and its Info, which must sit beside the photo with no sheet handle, and closes it with back. */
    private fun infoOpensBesideThePhoto() {
        compose.openGalleryMedia(photo)
        compose.openViewerDetails()
        val pane = bounds("info-side-pane")
        val viewport = bounds("viewer-photo-viewport")
        assertTrue("Info sits beside the photo", viewport.right <= pane.left + 1f)
        assertEquals("The photo keeps the full height", pane.height, viewport.height, 1f)
        compose.onNodeWithTag("details-drag-handle").assertDoesNotExist()
        compose.pressBack()
        compose.onNodeWithTag("info-side-pane").assertDoesNotExist()
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
    }

    @Test fun compactPortraitKeepsTheCapsuleOnePaneAndTheInfoSheet() {
        compose.onNodeWithTag("navigation-rail").assertDoesNotExist()
        compose.onNodeWithTag("gallery-destination-capsule").assertIsDisplayed()
        assertEquals(GridLevel.DAY.columns(WindowWidth.COMPACT, gridWidthDp()), gridColumns())
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("list-pane").assertDoesNotExist()
        compose.onNodeWithTag("collection-favorites").performClick()
        // The collection replaces the hub.
        compose.onNodeWithTag("collections-grid").assertDoesNotExist()
        compose.onNodeWithTag("gallery-grid").assertIsDisplayed()
        compose.pressBack()
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        compose.openGalleryMedia(photo)
        compose.openViewerDetails()
        compose.onNodeWithTag("info-side-pane").assertDoesNotExist()
        compose.onNodeWithTag("details-drag-handle").assertIsDisplayed()
        assertTrue(bounds("viewer-photo-viewport").bottom <= bounds("photo-details-panel").top + 1f)
    }

    @Config(qualifiers = "w600dp-h960dp-port-hdpi")
    @Test fun mediumWidthMovesNavigationToARailWithSearchAndInfoBesideThePhoto() {
        compose.onNodeWithTag("navigation-rail").assertIsDisplayed()
        compose.onNodeWithTag("gallery-destination-capsule").assertDoesNotExist()
        assertEquals(GridLevel.DAY.columns(WindowWidth.MEDIUM, gridWidthDp()), gridColumns())
        // Search is the rail's header action; the destinations are its items.
        compose.onNode(hasTestTag("tab-SEARCH") and inRail).performClick()
        compose.onNodeWithTag("tab-SEARCH").assertIsSelected()
        compose.onNodeWithTag("search-field").assertIsDisplayed()
        compose.onNode(hasTestTag("tab-COLLECTIONS") and inRail).performClick()
        compose.onNodeWithTag("tab-COLLECTIONS").assertIsSelected()
        // A medium window still opens a collection in place of the hub.
        compose.onNodeWithTag("list-pane").assertDoesNotExist()
        compose.onNodeWithTag("collection-favorites").performClick()
        compose.onNodeWithTag("collections-grid").assertDoesNotExist()
        compose.pressBack()
        compose.onNode(hasTestTag("tab-LIBRARY") and inRail).performClick()
        infoOpensBesideThePhoto()
    }

    @Config(qualifiers = "w840dp-h1200dp-port-hdpi")
    @Test fun expandedCollectionsShowTheOpenedCollectionBesideTheListAndBackClosesIt() {
        compose.onNode(hasTestTag("tab-COLLECTIONS") and inRail).performClick()
        compose.onNodeWithTag("list-pane").assertIsDisplayed()
        compose.onNodeWithTag("collection-detail-placeholder").assertIsDisplayed()
        assertTrue("The list is on the left", bounds("list-pane").right <= bounds("detail-pane").left)

        compose.onNodeWithTag("collection-favorites").performClick()
        compose.onNode(hasTestTag("gallery-grid") and hasAnyAncestor(hasTestTag("detail-pane"))).assertIsDisplayed()
        compose.onNodeWithTag("collection-detail-title").assertTextEquals(app.string(R.string.favorites))
        // The list stays, and the header keeps the list's title.
        compose.onNodeWithTag("collection-favorites").assertIsDisplayed()
        compose.onNodeWithTag("screen-title-COLLECTIONS").assertTextEquals(app.string(R.string.collections))
        app.recreate()
        compose.onNodeWithTag("gallery-grid").assertIsDisplayed()

        // A cancelled back gesture keeps the collection; a committed one closes it and leaves the list.
        compose.backGesture(commit = false)
        compose.onNodeWithTag("gallery-grid").assertIsDisplayed()
        compose.backGesture(commit = true)
        compose.onNodeWithTag("collection-detail-placeholder").assertIsDisplayed()
        compose.onNodeWithTag("collection-favorites").assertIsDisplayed()

        // Through a page and a folder: the album opens beside the folder, and back steps out of the album, then out
        // of the folder, then out of the page.
        compose.onNodeWithTag("collection-albums").performClick()
        compose.onNode(hasText("Journeys") and hasAnyAncestor(hasTestTag("list-pane"))).performClick()
        val album = hasText("By the sea") and hasAnyAncestor(hasTestTag("list-pane"))
        compose.onNode(album).performClick()
        compose.waitUntilExists("album-hero")
        compose.onNode(hasTestTag("album-hero") and hasAnyAncestor(hasTestTag("detail-pane"))).assertIsDisplayed()
        compose.onNode(album).assertIsDisplayed()
        compose.pressBack()
        compose.onNodeWithTag("collection-detail-placeholder").assertIsDisplayed()
        compose.onNode(album).assertIsDisplayed()
        compose.pressBack()
        compose.onNode(hasText("Journeys") and hasAnyAncestor(hasTestTag("list-pane"))).assertIsDisplayed()
        compose.pressBack()
        compose.onNodeWithTag("collection-albums").assertIsDisplayed()
    }

    @Config(qualifiers = "w1200dp-h800dp-land-hdpi")
    @Test fun wideLandscapeGridAddsColumnsAndInfoOpensBesideThePhoto() {
        compose.onNodeWithTag("navigation-rail").assertIsDisplayed()
        val columns = gridColumns()
        assertEquals(GridLevel.DAY.columns(WindowWidth.EXPANDED, gridWidthDp()), columns)
        assertTrue("A wide grid adds columns rather than stretching a phone's, but had $columns", columns >= 8)
        infoOpensBesideThePhoto()
    }

    /** A phone turned to landscape: the photo runs edge to edge, and Info opens beside it rather than below. */
    @Config(qualifiers = "w914dp-h411dp-land-hdpi")
    @Test fun phoneLandscapeShowsThePhotoEdgeToEdgeWithInfoBesideIt() {
        compose.openGalleryMedia(photo)
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val viewport = bounds("viewer-photo-viewport")
        assertEquals(screen.width, viewport.width, 1f)
        assertEquals(screen.height, viewport.height, 1f)
        compose.pressBack()
        compose.onNodeWithTag("media-pager").assertDoesNotExist()
        infoOpensBesideThePhoto()
    }
}
