package me.lvca.alba.app.testing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.MainActivity

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

/**
 * Runs [block] with [count] extra phone photos in the library, so the grid is long enough (100+ items) to get the date
 * scrubber, which the 20-photo demo alone is not. The photos are removed again afterwards.
 */
internal fun <T> AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.withLongLibrary(
    count: Int = 110, block: () -> T,
): T {
    waitForGalleryMedia()
    grantTestMediaPermissions(activity)
    // The gallery shows phone photos once it sees the permission, which it checks when the activity resumes.
    activityRule.scenario.recreate()
    waitForGalleryMedia()
    val repository = (activity.application as AlbaApplication).graph.galleryRepository
    val uris = mutableListOf<android.net.Uri>()
    try {
        repeat(count) { uris += MediaStoreFixtures.insertJpeg("alba-long-library-$it-${System.nanoTime()}.jpg") }
        // Scan once rather than wait for the debounced MediaStore observer, which follows the activity's lifecycle.
        runBlocking { repository.refreshDevice() }
        val references = uris.map { it.toString() }.toSet()
        waitUntil(LONG_WAIT) { runBlocking { repository.observeGallery().first().count { it.originalReference in references } == count } }
        waitForIdle()
        return block()
    } finally {
        uris.forEach { runCatching { activity.contentResolver.delete(it, null, null) } }
    }
}
