package me.lvca.alba.app.testing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe

/** The first demo photo the gallery shows. */
internal const val FIRST_DEMO_PHOTO = "fixture:icloud:0020"

/**
 * Waits for [id] to reach the gallery grid's data, even when phone photos put it offscreen. With [AlbaAppRule] the demo
 * catalog is there from the start; this is for items that arrive later, such as uploads and contributions.
 */
internal fun ComposeTestRule.waitForGalleryMedia(id: String) {
    waitUntil(UI_WAIT) {
        val grid = onAllNodesWithTag("gallery-grid").fetchSemanticsNodes().singleOrNull()
        (grid?.config?.getOrNull(SemanticsProperties.IndexForKey)?.invoke(id) ?: -1) >= 0
    }
}

internal fun ComposeTestRule.scrollToGalleryMedia(id: String) {
    waitForGalleryMedia(id)
    onNodeWithTag("gallery-grid").performScrollToKey(id)
    // Lazy-grid scrolling knows its viewport, but not the floating date header above it.
    // Move the photo below that header before injecting a tap or long press.
    val tile = onNodeWithTag("media-$id").fetchSemanticsNode().boundsInRoot
    val date = onAllNodesWithTag("floating-date-button").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
    if (date != null && tile.overlaps(date)) {
        onNodeWithTag("gallery-grid").performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, tile.top - date.bottom - 24f)
        }
    }
    onNodeWithTag("media-$id").assertIsDisplayed()
}

/** The demo's newest July photo: July's header sits right above it, and June's header is the whole month below it. */
private const val FIRST_JULY_PHOTO = "fixture:icloud:0005"

/**
 * Scrolls so the floating date pill shows and taps it. Like Google Photos, the pill steps aside while a real month
 * header is near the top of the grid (GalleryFeel.STICKY_DATE_HEADER_CLEARANCE). Scrolling the first photo of a month
 * to the top puts that month's header just above the viewport and the next one at least a row of tiles below it.
 * The pill hides about a second after scrolling stops, so the clock is held from the scroll until the tap.
 */
internal fun ComposeTestRule.tapFloatingDate() {
    waitForGalleryMedia(FIRST_JULY_PHOTO)
    mainClock.autoAdvance = false
    try {
        onNodeWithTag("gallery-grid").performScrollToKey(FIRST_JULY_PHOTO)
        // A jump by code shows no date; a short drag on the grid does, as a finger would.
        onNodeWithTag("gallery-grid").performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, -12f)) }
            up()
        }
        repeat(3) { mainClock.advanceTimeByFrame() }
        onNodeWithTag("floating-date-button").assertIsDisplayed().performClick()
    } finally {
        mainClock.autoAdvance = true
    }
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
