package me.lvca.alba.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset

/** CI emulators use software rendering on two cores; repository work can queue behind a catalog refresh. */
internal const val CI_WAIT_MS = 30_000L

/** Navigation, recreation and the viewer transition settle asynchronously; assert only once the node is on screen. */
internal fun ComposeTestRule.waitUntilDisplayed(matcher: SemanticsMatcher): SemanticsNodeInteraction {
    waitUntil(CI_WAIT_MS) { runCatching { onNode(matcher).assertIsDisplayed() }.isSuccess }
    return onNode(matcher)
}

internal fun ComposeTestRule.waitForGalleryMedia(id: String = "fixture:icloud:0020") {
    // Wait for the item in the lazy grid's data, even when phone photos put it offscreen.
    waitUntil(15_000) {
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

internal fun ComposeTestRule.openViewerDetails() {
    onNodeWithTag("media-pager").performTouchInput {
        swipe(Offset(width * .5f, height * .72f), Offset(width * .5f, height * .22f), 400)
    }
    onNodeWithTag("photo-details-panel").assertIsDisplayed()
}

internal fun ComposeTestRule.waitForVideoPlaying() = waitUntil(15_000) {
    onAllNodesWithTag("video-player").fetchSemanticsNodes().any {
        it.config.getOrNull(SemanticsProperties.StateDescription) == "Playing"
    }
}
