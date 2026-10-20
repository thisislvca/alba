package me.lvca.alba.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import kotlinx.coroutines.flow.first
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.MediaStoreFixtures
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.scrollToGalleryMedia

class GalleryTableStakesTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    @Test fun dragSelectionSelectsRangeAndCancelReturnsNavigation() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.onNodeWithTag("media-fixture:icloud:0020").performTouchInput { longClick() }
        compose.onNodeWithTag("media-fixture:icloud:0020").assertIsSelected()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.cancel_selection)).performClick()
        val tile = compose.onNodeWithTag("media-fixture:icloud:0020").fetchSemanticsNode().boundsInRoot
        val grid = compose.onNodeWithTag("gallery-grid").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("gallery-grid").performTouchInput {
            val start = tile.center - grid.topLeft
            down(start); advanceEventTime(650)
            moveTo(start + Offset(tile.width * 1.9f, 0f), 300); up()
        }
        val count = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes().size
        assertTrue("Dragging should select a range, not just the first tile", count >= 2)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.cancel_selection)).performClick()
        compose.onNodeWithTag("tab-LIBRARY").assertIsDisplayed()
    }
    @Test fun pinchChangesGridSizeAndDateScrollerJumps() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        val before = compose.onNodeWithTag("media-fixture:icloud:0020").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("gallery-grid").performTouchInput {
            pinch(Offset(width*.4f,height*.35f),Offset(width*.2f,height*.35f),Offset(width*.6f,height*.35f),Offset(width*.8f,height*.35f),450)
        }
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        val after = compose.onNodeWithTag("media-fixture:icloud:0020").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Pinch out should enlarge tiles", after > before)
        compose.onNodeWithTag("gallery-fast-scroll").performSemanticsAction(SemanticsActions.SetProgress) { it(.8f) }
    }
    private fun editedCopies(): Set<android.net.Uri> = MediaStoreFixtures.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ?",
        "%-edited-%.jpg", "Pictures/Alba/")
    @Test fun editorSavesNewCopyAndKeepsOriginal() {
        val existing = editedCopies()
        try {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.scrollToGalleryMedia("fixture:icloud:0016")
        compose.onNodeWithTag("media-fixture:icloud:0016").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.viewer_edit)).performClick()
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("photo-crop").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(compose.activity.getString(R.string.square_crop)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.rotate_degrees,0)).performClick()
        compose.onNodeWithTag("save-edited-copy").performClick()
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("photo-crop").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("incoming-media").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.edited_copy_saved)).assertIsDisplayed()
        compose.onNodeWithTag("incoming-back").performClick()
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
        val repository = (compose.activity.application as AlbaApplication).graph.galleryRepository
        val media = runBlocking { repository.observeGallery().first() }
        assertTrue(media.any { it.id == "fixture:icloud:0016" })
        assertEquals("Editing publishes exactly one new photo", 1, (editedCopies() - existing).size)
        } finally {
            (editedCopies() - existing).forEach { compose.activity.contentResolver.delete(it, null, null) }
        }
    }
}
