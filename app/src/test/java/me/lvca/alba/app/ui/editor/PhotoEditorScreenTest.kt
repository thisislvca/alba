package me.lvca.alba.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaOrigin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The editor's accessible crop, undo and unsaved-changes guard, on a decoded photo. Saving a copy is a device test. */
@RunWith(AndroidJUnit4::class)
class PhotoEditorScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int) = context.getString(id)
    private fun copyOf(photo: File) = File.createTempFile("editor-copy", ".png", context.cacheDir).also { photo.copyTo(it, true) }

    @Test fun accessibleCropUndoAndDirtyExitSurviveRecreation() {
        val photo = File.createTempFile("editor-photo", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val media = GalleryMedia("fixture:edit", "edit.png", 1, 120, 80, MediaOrigin.ICLOUD, MediaAvailability.ORIGINAL_CACHED,
            null, null, 0, 0)
        val open = mutableStateOf(true)
        try {
            StateRestorationTester(compose).apply {
                setContent {
                    // The editor deletes the copy it is given when it closes, as it does with the repository's copy.
                    if (open.value) {
                        PhotoEditorScreen(media, source = { copyOf(photo) }, close = { open.value = false }, saved = {})
                    }
                }
                compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("photo-crop").fetchSemanticsNodes().isNotEmpty() }
                val before = compose.onNodeWithTag("photo-crop").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
                val adjustment = compose.onNodeWithTag("photo-crop").fetchSemanticsNode().config[SemanticsActions.CustomActions][1]
                compose.runOnIdle { assertTrue(adjustment.action()) }
                val changed = compose.onNodeWithTag("photo-crop").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
                assertNotEquals(before, changed)
                emulateSavedInstanceStateRestore()
                compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("photo-crop").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("photo-crop").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, changed))
                // Leaving with an unsaved crop asks first.
                compose.pressBack()
                compose.onNodeWithText(text(R.string.discard_edits_title)).assertIsDisplayed()
                compose.onNodeWithText(text(R.string.keep_editing)).performClick()
                compose.onNodeWithTag("undo-edit").performClick()
                compose.onNodeWithTag("photo-crop").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, before))
                compose.onNodeWithText(text(R.string.adjust_crop)).performClick()
                // Undone, there is nothing to lose, so Back closes at once.
                compose.pressBack()
                compose.onNodeWithTag("photo-crop").assertDoesNotExist()
                assertFalse(open.value)
            }
        } finally { photo.delete() }
    }
}
