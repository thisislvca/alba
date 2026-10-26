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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.editing.LookFilter
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

/** The editor's tabs, crop, adjustments, compare, undo and unsaved-changes guard, on a decoded photo. Saving a copy is a device test. */
@RunWith(AndroidJUnit4::class)
class PhotoEditorScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int) = context.getString(id)
    private fun copyOf(photo: File) = File.createTempFile("editor-copy", ".png", context.cacheDir).also { photo.copyTo(it, true) }
    private val media = GalleryMedia("fixture:edit", "edit.png", 1, 120, 80, MediaOrigin.ICLOUD, MediaAvailability.ORIGINAL_CACHED,
        null, null, 0, 0)

    private fun photo(pixel: (Int, Int) -> Int = { _, _ -> Color.rgb(90, 120, 60) }): File =
        File.createTempFile("editor-photo", ".png", context.cacheDir).also { photo ->
            val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            for (x in 0 until 120) for (y in 0 until 80) bitmap.setPixel(x, y, pixel(x, y))
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    private fun waitForPhoto() = compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("editor-image").fetchSemanticsNodes().isNotEmpty() }
    private fun slider() = compose.onNodeWithTag("editor-slider")

    // Changed tools show a dot, announced as their state.
    private val changed get() = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.adjustment_changed))
    private val unchanged = SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)
    private fun sliderValue(value: Int) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value.toString())

    @Test fun accessibleCropUndoAndDirtyExitSurviveRecreation() {
        val photo = photo { _, _ -> Color.RED }
        val open = mutableStateOf(true)
        try {
            StateRestorationTester(compose).apply {
                setContent {
                    // The editor deletes the copy it is given when it closes, as it does with the repository's copy.
                    if (open.value) {
                        PhotoEditorScreen(media, source = { copyOf(photo) }, close = { open.value = false }, saved = { _, _ -> })
                    }
                }
                waitForPhoto()
                compose.onNodeWithTag("editor-tab-crop").performClick()
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

    @Test fun tabsSlidersResetCompareAndUndoSurviveRecreation() {
        val photo = photo()
        try {
            StateRestorationTester(compose).apply {
                setContent { PhotoEditorScreen(media, source = { copyOf(photo) }, close = {}, saved = { _, _ -> }) }
                waitForPhoto()
                // The editor opens on Suggestions, as Google Photos does.
                compose.onNodeWithTag("suggestion-enhance").assertIsDisplayed()
                compose.onNodeWithTag("photo-crop").assertDoesNotExist()

                compose.onNodeWithTag("editor-tab-adjust").performClick()
                compose.onNodeWithTag("adjust-tool-brightness").assert(unchanged)
                slider().performSemanticsAction(SemanticsActions.SetProgress) { it(40f) }
                slider().assert(sliderValue(40))
                compose.onNodeWithTag("adjust-tool-brightness").assert(changed)

                // Double-tap puts the slider back to 0 as one undoable step.
                slider().performTouchInput { doubleClick(center) }
                slider().assert(sliderValue(0))
                compose.onNodeWithTag("adjust-tool-brightness").assert(unchanged)
                compose.onNodeWithTag("undo-edit").performClick()
                slider().assert(sliderValue(40))
                compose.onNodeWithTag("redo-edit").performClick()
                slider().assert(sliderValue(0))
                compose.onNodeWithTag("undo-edit").performClick()

                compose.onNodeWithTag("adjust-tool-contrast").performClick()
                slider().assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text(R.string.adjust_contrast))))
                slider().assert(sliderValue(0))

                // Touch and hold shows the original until the finger lifts.
                compose.onNodeWithTag("compare-original").assertDoesNotExist()
                compose.onNodeWithTag("editor-image").performTouchInput { down(center) }
                compose.onNodeWithTag("compare-original").assertIsDisplayed()
                compose.onNodeWithTag("editor-image").performTouchInput { up() }
                compose.onNodeWithTag("compare-original").assertDoesNotExist()

                compose.onNodeWithTag("editor-tab-filters").performClick()
                compose.onNodeWithTag("filter-strength").assertDoesNotExist()
                compose.onNodeWithTag("filter-list").performScrollToKey(LookFilter.MONO.name)
                compose.onNodeWithTag("filter-mono").performClick()
                compose.onNodeWithTag("filter-mono").assertIsSelected()
                compose.onNodeWithTag("filter-strength").assertIsDisplayed()

                emulateSavedInstanceStateRestore()
                waitForPhoto()
                compose.onNodeWithTag("filter-strength").assertIsDisplayed()
                compose.onNodeWithTag("filter-list").performScrollToKey(LookFilter.MONO.name)
                compose.onNodeWithTag("filter-mono").assertIsSelected()
                compose.onNodeWithTag("editor-tab-adjust").performClick()
                compose.onNodeWithTag("adjust-tool-brightness").performClick()
                slider().assert(sliderValue(40))
                compose.onNodeWithTag("undo-edit").performClick()
                compose.onNodeWithTag("undo-edit").performClick()
                slider().assert(sliderValue(0))
                compose.onNodeWithTag("undo-edit").assertIsNotEnabled()
            }
        } finally { photo.delete() }
    }

    @Test fun enhanceIsOneTapOnAndOff() {
        // A dark, flat gradient: enhance has something to do.
        val photo = photo { x, _ -> Color.rgb(x / 4 + 10, x / 4 + 15, x / 4 + 5) }
        try {
            compose.setContent { PhotoEditorScreen(media, source = { copyOf(photo) }, close = {}, saved = { _, _ -> }) }
            compose.waitUntil(UI_WAIT) {
                compose.onAllNodesWithTag("suggestion-enhance").fetchSemanticsNodes().singleOrNull()
                    ?.config?.contains(SemanticsProperties.Disabled) == false
            }
            compose.onNodeWithTag("suggestion-enhance").performClick()
            compose.onNodeWithTag("suggestion-enhance").assertIsSelected()
            compose.onNodeWithTag("editor-tab-adjust").performClick()
            compose.onNodeWithTag("adjust-tool-brightness").assert(changed)
            compose.onNodeWithTag("editor-tab-suggestions").performClick()
            compose.onNodeWithTag("suggestion-enhance").performClick()
            compose.onNodeWithTag("suggestion-enhance").assertIsNotSelected()
            compose.onNodeWithTag("editor-tab-adjust").performClick()
            compose.onNodeWithTag("adjust-tool-brightness").assert(unchanged)
        } finally { photo.delete() }
    }
}
