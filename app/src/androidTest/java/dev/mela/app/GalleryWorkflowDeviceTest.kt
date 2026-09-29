package dev.mela.app

import android.content.ContentUris
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class GalleryWorkflowDeviceTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun text(id: Int) = compose.activity.getString(id)
    private fun loaded() = compose.waitUntil(20_000) { compose.onAllNodesWithTag("media-fixture:icloud:0020").fetchSemanticsNodes().isNotEmpty() }
    private fun open(id: String) {
        compose.onNodeWithTag("gallery-grid").performScrollToKey(id)
        compose.onNodeWithTag("media-$id").performClick()
    }
    private fun player(view: View = compose.activity.window.decorView): Player? {
        if (view is PlayerView) return view.player
        if (view is ViewGroup) for (i in 0 until view.childCount) player(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val dir = compose.activity.getExternalFilesDir("workflow-finish")!!.apply { mkdirs() }
        java.io.File(dir, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun pausedVideoRetainsPositionAcrossActivityRecreation() {
        loaded(); open("fixture:icloud:0019")
        compose.waitUntil(15_000) { compose.runOnIdle { player()?.playbackState == Player.STATE_READY } }
        compose.runOnIdle { player()!!.apply { pause(); seekTo(1200) } }
        compose.waitUntil(5_000) { compose.runOnIdle { player()!!.currentPosition >= 1100 } }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.runOnIdle { player()?.playbackState == Player.STATE_READY } }
        compose.runOnIdle {
            assertFalse(player()!!.playWhenReady)
            assertTrue("Playback position should survive recreation", player()!!.currentPosition in 1100..1400)
        }
        capture("restored-video")
    }

    @Test fun creatingAnAlbumFromTheViewerAddsTheCurrentPhoto() {
        loaded()
        val repository = (compose.activity.application as MelaApplication).graph.galleryRepository
        val name = "Workflow ${System.nanoTime()}"
        try {
            open("fixture:icloud:0016")
            compose.onNodeWithContentDescription(text(R.string.photo_details_and_actions)).performClick()
            compose.onNodeWithText(text(R.string.add_to_album)).performScrollTo().performClick()
            capture("album-picker")
            compose.onNodeWithText(text(R.string.new_album)).performClick()
            compose.onNode(hasSetTextAction()).performTextInput(name)
            compose.onNodeWithText(text(R.string.save)).performClick()
            compose.waitUntil(10_000) { runBlocking {
                val album = repository.observeCollections().first().firstOrNull { it.name == name }
                album != null && repository.observeGallery().first().any { it.id == "fixture:icloud:0016" && album.id in it.collectionIds }
            } }
        } finally { runBlocking { repository.observeCollections().first().filter { it.name == name }.forEach { repository.deleteAlbum(it.id) } } }
    }

    @Test fun accessibleCropUndoAndDirtyExitSurviveRecreation() {
        loaded(); open("fixture:icloud:0016")
        compose.onNodeWithContentDescription(text(R.string.edit_photo)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("photo-crop").fetchSemanticsNodes().isNotEmpty() }
        val before = compose.onNodeWithTag("photo-crop").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        val adjustment = compose.onNodeWithTag("photo-crop").fetchSemanticsNode().config[SemanticsActions.CustomActions][1]
        compose.runOnIdle { assertTrue(adjustment.action()) }
        val changed = compose.onNodeWithTag("photo-crop").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertNotEquals(before, changed)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("photo-crop").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("photo-crop").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, changed))
        Espresso.pressBack()
        compose.onNodeWithText(text(R.string.discard_edits_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.keep_editing)).performClick()
        compose.onNodeWithTag("undo-edit").performClick()
        compose.onNodeWithTag("photo-crop").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, before))
        compose.onNodeWithText(text(R.string.adjust_crop)).performClick()
        capture("accessible-editor")
        Espresso.pressBack()
        compose.onNodeWithTag("photo-crop").assertDoesNotExist()
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
    }

    private fun phoneCopies(): Set<android.net.Uri> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return compose.activity.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("Pictures/Alba/"), null)?.use { cursor -> buildSet {
                while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0)))
            } } ?: emptySet()
    }

    @Test fun selectedCloudPhotosAreSavedAsPublicPhoneCopies() {
        loaded()
        val before = phoneCopies()
        val graph = (compose.activity.application as MelaApplication).graph
        val previousBatches = runBlocking { graph.batches.observe().first().map { it.batchId }.toSet() }
        try {
            compose.onNodeWithTag("gallery-grid").performScrollToKey("fixture:icloud:0016")
            compose.onNodeWithTag("media-fixture:icloud:0016").performTouchInput { longClick() }
            compose.onNodeWithTag("gallery-grid").performScrollToKey("fixture:icloud:0017")
            compose.onNodeWithTag("media-fixture:icloud:0017").performClick()
            compose.onNodeWithTag("save-selection-to-phone").performScrollTo().performClick()
            compose.waitUntil(20_000) { runBlocking { graph.batches.observe().first().count {
                it.batchId !in previousBatches && it.action == "SAVE_TO_PHONE" && it.state == "DONE"
            } == 2 } }
            val saved = phoneCopies() - before
            assertEquals(2, saved.size)
            saved.forEach { uri -> assertTrue(compose.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes().isNotEmpty() }) }
        } finally { (phoneCopies() - before).forEach { compose.activity.contentResolver.delete(it, null, null) } }
    }
}
