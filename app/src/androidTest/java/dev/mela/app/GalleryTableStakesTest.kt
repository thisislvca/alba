package dev.mela.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import kotlinx.coroutines.flow.first
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.File

class GalleryTableStakesTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun loaded() = compose.waitUntil(20_000) { compose.onAllNodesWithTag("media-fixture:icloud:0020").fetchSemanticsNodes().isNotEmpty() }
    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val dir = instrumentation.targetContext.getExternalFilesDir("table-stakes")!!.apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun dragSelectionSelectsRangeAndCancelReturnsNavigation() {
        loaded()
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
        capture("selection")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.cancel_selection)).performClick()
        compose.onNodeWithTag("tab-LIBRARY").assertIsDisplayed()
    }
    @Test fun pinchChangesGridSizeAndDateScrollerJumps() {
        loaded()
        val before = compose.onNodeWithTag("media-fixture:icloud:0020").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("gallery-grid").performTouchInput {
            pinch(Offset(width*.4f,height*.35f),Offset(width*.2f,height*.35f),Offset(width*.6f,height*.35f),Offset(width*.8f,height*.35f),450)
        }
        compose.onNodeWithTag("gallery-grid").performScrollToKey("fixture:icloud:0020")
        val after = compose.onNodeWithTag("media-fixture:icloud:0020").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Pinch out should enlarge tiles", after > before)
        compose.onNodeWithTag("gallery-fast-scroll").performSemanticsAction(SemanticsActions.SetProgress) { it(.8f) }
        capture("date-scroll")
    }
    private fun editedCopies(): Set<android.net.Uri> {
        val resolver = compose.activity.contentResolver
        val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return resolver.query(collection, arrayOf(android.provider.MediaStore.Images.Media._ID),
            "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf("%-edited-%.jpg", "Pictures/Alba/"), null)?.use { cursor -> buildSet {
                while (cursor.moveToNext()) add(android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)))
            } } ?: emptySet()
    }
    @Test fun editorSavesNewCopyAndKeepsOriginal() {
        val existing = editedCopies()
        try {
        loaded()
        compose.onNodeWithTag("gallery-grid").performScrollToKey("fixture:icloud:0016")
        compose.onNodeWithTag("media-fixture:icloud:0016").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.edit_photo)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("photo-crop").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(compose.activity.getString(R.string.square_crop)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.rotate_degrees,0)).performClick()
        capture("editor")
        compose.onNodeWithTag("save-edited-copy").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("photo-crop").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("incoming-media").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.edited_copy_saved)).assertIsDisplayed()
        capture("saved-copy")
        compose.onNodeWithTag("incoming-back").performClick()
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
        val repository = (compose.activity.application as MelaApplication).graph.galleryRepository
        val media = runBlocking { repository.observeGallery().first() }
        assertTrue(media.any { it.id == "fixture:icloud:0016" })
        assertEquals("Editing publishes exactly one new photo", 1, (editedCopies() - existing).size)
        } finally {
            (editedCopies() - existing).forEach { compose.activity.contentResolver.delete(it, null, null) }
        }
    }
}
