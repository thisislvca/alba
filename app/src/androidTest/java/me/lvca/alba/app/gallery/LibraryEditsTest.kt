package me.lvca.alba.app.gallery

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.LONG_WAIT
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.Smoke
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForGalleryMedia
import me.lvca.alba.app.testing.waitUntilDisplayed
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LibraryEditsTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private val repository get() = (compose.activity.application as AlbaApplication).graph.galleryRepository

    @Smoke @Test fun cloudTrashRequiresConfirmationAndCanBeRestoredFromItsCollection() {
        compose.waitForGalleryMedia()
        val id = "fixture:icloud:0017"
        try {
            compose.scrollToGalleryMedia(id)
            compose.onNodeWithTag("media-$id").performClick()
            compose.openViewerDetails()
            val trash = compose.activity.getString(R.string.trash_in_icloud)
            compose.onNodeWithText(trash).performScrollTo().performClick()
            compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.cloud_trash_confirmation, 1, 1)).assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
            assertFalse(runBlocking { repository.observeGallery().first().first { it.id == id }.isTrashed })
            compose.onNodeWithText(trash).performScrollTo().performClick()
            compose.onAllNodesWithText(trash).onLast().performClick()
            compose.waitUntil(LONG_WAIT) { runBlocking { repository.observeGallery().first().first { it.id == id }.isTrashed } }
            // Like Google Photos, the viewer moves on to the next (older) photo instead of closing.
            compose.waitUntilDisplayed(hasTestTag("viewer-image-fixture:icloud:0018"))
            compose.onAllNodesWithTag("viewer-image-$id").assertCountEquals(0)
            compose.pressBack()
            compose.waitUntil(LONG_WAIT) { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle {
                val lightTheme = compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
                assertEquals("Gallery must restore system-bar contrast after the viewer exits", lightTheme,
                    androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).isAppearanceLightStatusBars)
            }
            val snackbar = compose.onNodeWithTag("status-snackbar").fetchSemanticsNode().boundsInRoot
            val tab = compose.onNodeWithTag("tab-COLLECTIONS").fetchSemanticsNode().boundsInRoot
            assertTrue("Status messages must not cover floating navigation", snackbar.bottom <= tab.top)
            compose.onNodeWithTag("tab-COLLECTIONS").performClick()
            compose.waitUntil(LONG_WAIT) { compose.onAllNodesWithTag("collections-grid").fetchSemanticsNodes().isNotEmpty() }
            // Snackbar timeout runs on the injected clock, not waitUntil's wall clock. The Undo message stays 5 s.
            compose.mainClock.advanceTimeBy(6_000)
            compose.onNodeWithTag("collections-grid").performScrollToNode(hasText(compose.activity.getString(R.string.icloud_recently_deleted)))
            // Lazy-grid scroll-to only accounts for its viewport. Scroll the whole row
            // above the floating controls, as a user does, before injecting a touch.
            val row = compose.onNodeWithText(compose.activity.getString(R.string.icloud_recently_deleted)).fetchSemanticsNode().boundsInRoot
            val bar = compose.onNodeWithTag("gallery-destination-capsule").fetchSemanticsNode().boundsInRoot
            val extra = (row.bottom - bar.top + 24f).coerceAtLeast(0f)
            if (extra > 0f) compose.onNodeWithTag("collections-grid").performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(width * .5f, height * .65f),
                    androidx.compose.ui.geometry.Offset(width * .5f, height * .65f - extra), 350)
            }
            compose.onNodeWithText(compose.activity.getString(R.string.icloud_recently_deleted)).performClick()
            compose.waitUntil(LONG_WAIT) { compose.onNodeWithTag("media-$id").isDisplayed() }
            compose.onNodeWithTag("media-$id").assertIsDisplayed()
            compose.onNodeWithTag("media-$id").performClick()
            compose.openViewerDetails()
            val restore = compose.activity.getString(R.string.restore_from_icloud)
            compose.onNodeWithText(restore).performScrollTo().performClick()
            compose.onAllNodesWithText(restore).onLast().performClick()
            compose.waitUntil(LONG_WAIT) { runBlocking { repository.observeGallery().first().first { it.id == id }.isTrashed.not() } }
        } finally { runBlocking { repository.setCloudTrashed(listOf(id), false) } }
    }

    @Test fun movingToICloudTrashCanBeUndoneFromItsMessage() {
        compose.waitForGalleryMedia()
        val id = "fixture:icloud:0016"
        fun trashed() = runBlocking { repository.observeGallery().first().first { it.id == id }.isTrashed }
        try {
            compose.scrollToGalleryMedia(id)
            compose.onNodeWithTag("media-$id").performClick()
            compose.openViewerDetails()
            val trash = compose.activity.getString(R.string.trash_in_icloud)
            compose.onNodeWithText(trash).performScrollTo().performClick()
            // iCloud trash keeps its confirmation, like Google Photos' "Remove from your Google Account" question.
            compose.onAllNodesWithText(trash).onLast().performClick()
            compose.waitUntil(LONG_WAIT) { trashed() }
            compose.waitUntil(LONG_WAIT) { compose.onAllNodesWithTag("snackbar-action").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.cloud_trash_moved, 1, 1)).assertIsDisplayed()
            compose.onNodeWithTag("snackbar-action").assertTextEquals(compose.activity.getString(R.string.undo)).performClick()
            compose.waitUntil(LONG_WAIT) { !trashed() }
            // The viewer moved on after the trash; Undo brings the photo back into it, and back into the library.
            compose.waitUntilDisplayed(hasTestTag("viewer-image-$id"))
            compose.pressBack()
            compose.waitUntil(LONG_WAIT) { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("media-$id").assertExists()
        } finally { runBlocking { repository.setCloudTrashed(listOf(id), false) } }
    }
}
