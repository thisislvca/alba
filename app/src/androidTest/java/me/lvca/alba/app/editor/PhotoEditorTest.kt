package me.lvca.alba.app.editor

import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.MediaStoreFixtures
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.Smoke
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.scrollToGalleryMedia
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhotoEditorTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private fun editedCopies(): Set<android.net.Uri> = MediaStoreFixtures.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ?",
        "%-edited-%.jpg", "Pictures/Alba/")

    @Smoke @Test fun editorSavesNewCopyAndKeepsOriginal() {
        val existing = editedCopies()
        try {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.scrollToGalleryMedia("fixture:icloud:0016")
        compose.onNodeWithTag("media-fixture:icloud:0016").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.viewer_edit)).performClick()
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("photo-crop").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(compose.activity.getString(R.string.square_crop)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.rotate_degrees, 0)).performClick()
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
