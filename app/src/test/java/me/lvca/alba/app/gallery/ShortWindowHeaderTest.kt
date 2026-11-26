package me.lvca.alba.app.gallery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.AlbaAppRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A short window (a phone in landscape, compact height) keeps one small bar with the title inline and the filter
 * actions beside the screen's own, so the photos start right under it. Taller windows keep the large titles.
 */
@RunWith(AndroidJUnit4::class)
class ShortWindowHeaderTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private val inRail = hasAnyAncestor(hasTestTag("navigation-rail"))
    private val inSmallBar = hasAnyAncestor(hasTestTag("title-bar-small"))

    @Config(qualifiers = "w914dp-h411dp-land")
    @Test fun phoneLandscapeHasOneSmallBarAndThePhotosStartUnderIt() {
        compose.onNodeWithTag("title-bar-large").assertDoesNotExist()
        compose.onNodeWithTag("title-bar-small").assertIsDisplayed()
        compose.onNode(hasTestTag("screen-title-LIBRARY") and inSmallBar).assertTextEquals(app.string(R.string.gallery_photos))
            .assertIsDisplayed()
        // The filter row's actions are in the bar, and there is no row under it.
        compose.onNode(hasTestTag("filter-button") and inSmallBar).assertIsDisplayed()
        compose.onNode(hasTestTag("browse-dates-button") and inSmallBar).assertIsDisplayed()

        // The bar starts under the status bar; the grid starts within a small bar's 64dp of it.
        val limit = 64f * compose.density.density
        val gap = bounds("gallery-grid").top - bounds("title-bar-small").top
        assertTrue("The grid starts ${gap / compose.density.density}dp under the status bar", gap <= limit + 1f)

        // The rail fits the short window: Search in its header and both destinations, all on screen.
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        listOf("tab-SEARCH", "tab-LIBRARY", "tab-COLLECTIONS").forEach { tag ->
            val item = compose.onNode(hasTestTag(tag) and inRail).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag is on screen: $item in $screen", item.top >= screen.top && item.bottom <= screen.bottom)
        }

        // Every destination keeps the small bar.
        compose.onNode(hasTestTag("tab-COLLECTIONS") and inRail).performClick()
        compose.onNode(hasTestTag("screen-title-COLLECTIONS") and inSmallBar).assertTextEquals(app.string(R.string.collections))
            .assertIsDisplayed()
        compose.onNodeWithTag("title-bar-large").assertDoesNotExist()
    }

    @Test fun phonePortraitKeepsTheLargeTitle() {
        compose.onNodeWithTag("title-bar-small").assertDoesNotExist()
        compose.onNode(hasTestTag("screen-title-LIBRARY") and hasAnyAncestor(hasTestTag("title-bar-large")))
            .assertTextEquals(app.string(R.string.gallery_photos)).assertIsDisplayed()
    }

    @Config(qualifiers = "w1200dp-h800dp-land-hdpi")
    @Test fun tallLandscapeKeepsTheLargeTitleAndTheFilterRow() {
        compose.onNodeWithTag("title-bar-small").assertDoesNotExist()
        compose.onNode(hasTestTag("screen-title-LIBRARY") and hasAnyAncestor(hasTestTag("title-bar-large"))).assertIsDisplayed()
        compose.onNodeWithTag("filter-button").assertIsDisplayed()
        assertTrue("The filter row sits under the large title", bounds("filter-button").top >= bounds("title-bar-large").bottom)
    }
}
