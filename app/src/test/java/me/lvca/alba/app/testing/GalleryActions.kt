package me.lvca.alba.app.testing

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.espresso.Espresso

/** The first demo photo the gallery shows; the demo catalog loads asynchronously from its fixtures. */
internal const val FIRST_DEMO_PHOTO = "fixture:icloud:0020"

/**
 * How long a JVM test waits for work the app does off the main thread (Room, the demo catalog). Robolectric runs that
 * work on real threads; it normally takes milliseconds, so this only costs time when a test fails.
 */
internal const val APP_WAIT = 10_000L

internal fun ComposeTestRule.waitForGalleryMedia(id: String = FIRST_DEMO_PHOTO) {
    waitUntil(APP_WAIT) {
        val grid = onAllNodesWithTag("gallery-grid").fetchSemanticsNodes().singleOrNull()
        (grid?.config?.getOrNull(SemanticsProperties.IndexForKey)?.invoke(id) ?: -1) >= 0
    }
}

internal fun ComposeTestRule.scrollToGalleryMedia(id: String) {
    waitForGalleryMedia(id)
    onNodeWithTag("gallery-grid").performScrollToKey(id)
    onNodeWithTag("media-$id").assertIsDisplayed()
}

/** Opens [id] in the viewer from the gallery grid. */
internal fun ComposeTestRule.openGalleryMedia(id: String) {
    scrollToGalleryMedia(id)
    onNodeWithTag("media-$id").performClick()
    waitUntil(APP_WAIT) { onAllNodesWithTag("media-pager").fetchSemanticsNodes().isNotEmpty() }
}

internal fun ComposeTestRule.waitUntilExists(tag: String) =
    waitUntil(APP_WAIT) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

internal fun ComposeTestRule.waitUntilGone(tag: String) =
    waitUntil(APP_WAIT) { onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

/** Android back, delivered to the focused window: a dialog or sheet, else the activity. */
internal fun ComposeTestRule.pressBack() {
    waitForIdle()
    Espresso.pressBackUnconditionally()
    waitForIdle()
}
