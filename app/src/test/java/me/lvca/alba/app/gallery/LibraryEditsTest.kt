package me.lvca.alba.app.gallery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitUntilDisplayed
import me.lvca.alba.app.testing.waitUntilExists
import me.lvca.alba.app.testing.waitUntilSettled
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Trashing a photo in iCloud from the viewer: confirmation, Undo, the next photo, and restoring from Recently Deleted. */
@RunWith(AndroidJUnit4::class)
class LibraryEditsTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose
    private val repository get() = app.application.graph.galleryRepository
    private val id = "fixture:icloud:0017"

    // Room reads need no main looper, so this can block the main thread inside waitUntil.
    private fun trashed() = runBlocking { repository.observeGallery().first().first { it.id == id }.isTrashed }

    private fun trashFromDetails() {
        compose.onNodeWithTag("detail-cloud-trash").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-removal").performClick()
        compose.waitUntilSettled { trashed() }
    }

    @Test fun cloudTrashRequiresConfirmationAndCanBeUndoneOrRestoredFromItsCollection() {
        compose.scrollToGalleryMedia(id)
        compose.onNodeWithTag("media-$id").performClick()
        compose.openViewerDetails()

        // Cancelling the confirmation leaves the photo alone.
        compose.onNodeWithTag("detail-cloud-trash").performScrollTo().performClick()
        compose.onNodeWithText(app.activity.resources.getQuantityString(R.plurals.cloud_trash_confirmation, 1, 1)).assertIsDisplayed()
        compose.onNodeWithTag("cancel-removal").performClick()
        assertFalse(trashed())

        // The message offers Undo, which brings the photo back into the viewer and the library.
        trashFromDetails()
        // Edits reach the screen through Room and the gallery's projection, off the main thread.
        compose.waitUntilExists("snackbar-action")
        compose.onNodeWithText(app.activity.resources.getQuantityString(R.plurals.cloud_trash_moved, 1, 1)).assertIsDisplayed()
        compose.onNodeWithTag("snackbar-action").assertTextEquals(app.string(R.string.undo)).performClick()
        compose.waitUntilSettled { !trashed() }
        compose.waitUntilDisplayed(hasTestTag("viewer-image-$id"))
        // The photo comes back in the viewer, without its details.
        compose.onNodeWithTag("photo-details-panel").assertDoesNotExist()
        compose.openViewerDetails()

        // Like Google Photos, the viewer moves on to the next (older) photo instead of closing.
        trashFromDetails()
        compose.waitUntilDisplayed(hasTestTag("viewer-image-fixture:icloud:0018"))
        compose.onNodeWithTag("viewer-image-$id").assertDoesNotExist()
        compose.pressBack()
        compose.onNodeWithTag("media-pager").assertDoesNotExist()
        val snackbar = compose.onNodeWithTag("status-snackbar").fetchSemanticsNode().boundsInRoot
        val tab = compose.onNodeWithTag("tab-COLLECTIONS").fetchSemanticsNode().boundsInRoot
        assertTrue("Status messages must not cover floating navigation", snackbar.bottom <= tab.top)

        // Recently Deleted lists it, and restoring from there brings it back.
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        // The Undo message stays 5 s on the test clock.
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasTestTag("collection-cloud-trash"))
        compose.onNodeWithTag("collection-cloud-trash").performClick()
        compose.waitUntilExists("media-$id")
        compose.scrollToGalleryMedia(id)
        compose.onNodeWithTag("media-$id").performClick()
        compose.openViewerDetails()
        compose.onNodeWithTag("detail-cloud-trash").assertTextEquals(app.string(R.string.restore_from_icloud)).performScrollTo().performClick()
        compose.onNodeWithTag("confirm-removal").performClick()
        compose.waitUntilSettled { !trashed() }
    }
}
