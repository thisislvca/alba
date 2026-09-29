package dev.mela.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey

internal fun ComposeTestRule.scrollToGalleryMedia(id: String) {
    // Wait for the item in the lazy grid's data, even when phone photos put it offscreen.
    waitUntil(15_000) {
        val grid = onAllNodesWithTag("gallery-grid").fetchSemanticsNodes().singleOrNull()
        (grid?.config?.getOrNull(SemanticsProperties.IndexForKey)?.invoke(id) ?: -1) >= 0
    }
    onNodeWithTag("gallery-grid").performScrollToKey(id)
    onNodeWithTag("media-$id").assertIsDisplayed()
}
