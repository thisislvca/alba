package me.lvca.alba.app.testing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

internal fun ComposeTestRule.waitForGalleryMedia(id: String = "fixture:icloud:0020") {
    // Wait for the item in the lazy grid's data, even when phone photos put it offscreen.
    waitUntil(UI_WAIT) {
        val grid = onAllNodesWithTag("gallery-grid").fetchSemanticsNodes().singleOrNull()
        (grid?.config?.getOrNull(SemanticsProperties.IndexForKey)?.invoke(id) ?: -1) >= 0
    }
}

internal fun ComposeTestRule.scrollToGalleryMedia(id: String) {
    waitForGalleryMedia(id)
    onNodeWithTag("gallery-grid").performScrollToKey(id)
    // Lazy-grid scrolling knows its viewport, but not the floating date header above it.
    // Move the photo below that header before injecting a real tap/long press.
    val tile = onNodeWithTag("media-$id").fetchSemanticsNode().boundsInRoot
    val date = onAllNodesWithTag("floating-date-button").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
    if (date != null && tile.overlaps(date)) {
        onNodeWithTag("gallery-grid").performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, tile.top - date.bottom - 24f)
        }
    }
    onNodeWithTag("media-$id").assertIsDisplayed()
}

/** Opens [id] in the viewer from the gallery grid. */
internal fun ComposeTestRule.openGalleryMedia(id: String) {
    scrollToGalleryMedia(id)
    onNodeWithTag("media-$id").performClick()
    waitUntil(SHORT_WAIT) { onAllNodesWithTag("media-pager").fetchSemanticsNodes().isNotEmpty() }
}

internal fun ComposeTestRule.openViewerDetails() {
    onNodeWithTag("media-pager").performTouchInput {
        swipe(Offset(width * .5f, height * .72f), Offset(width * .5f, height * .22f), 400)
    }
    onNodeWithTag("photo-details-panel").assertIsDisplayed()
}

internal fun ComposeTestRule.waitForVideoPlaying() = waitUntil(UI_WAIT) {
    onAllNodesWithTag("video-player").fetchSemanticsNodes().any {
        it.config.getOrNull(SemanticsProperties.StateDescription) == "Playing"
    }
}
