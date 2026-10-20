package me.lvca.alba.app

import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import me.lvca.alba.engine.model.BatchItemState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.LONG_WAIT
import me.lvca.alba.app.testing.MediaStoreFixtures
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.openGalleryMedia
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForGalleryMedia

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class GalleryWorkflowDeviceTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private fun text(id: Int) = compose.activity.getString(id)
    private fun player(view: View = compose.activity.window.decorView): Player? {
        if (view is PlayerView) return view.player
        if (view is ViewGroup) for (i in 0 until view.childCount) player(view.getChildAt(i))?.let { return it }
        return null
    }

    @Test fun pausedVideoRetainsPositionAcrossActivityRecreation() {
        compose.waitForGalleryMedia(); compose.openGalleryMedia("fixture:icloud:0019")
        compose.waitUntil(UI_WAIT) { compose.runOnIdle { player()?.playbackState == Player.STATE_READY } }
        compose.runOnIdle { player()!!.apply { pause(); seekTo(1200) } }
        compose.waitUntil(SHORT_WAIT) { compose.runOnIdle { player()!!.currentPosition >= 1100 } }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(UI_WAIT) { compose.runOnIdle { player()?.playbackState == Player.STATE_READY } }
        compose.runOnIdle {
            assertFalse(player()!!.playWhenReady)
            assertTrue("Playback position should survive recreation", player()!!.currentPosition in 1100..1400)
        }
    }

    @Test fun creatingAnAlbumFromTheViewerAddsTheCurrentPhoto() {
        compose.waitForGalleryMedia()
        val repository = (compose.activity.application as AlbaApplication).graph.galleryRepository
        val name = "Workflow ${System.nanoTime()}"
        try {
            compose.openGalleryMedia("fixture:icloud:0016")
            compose.openViewerDetails()
            compose.onNodeWithText(text(R.string.add_to_album)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.new_album)).performClick()
            compose.onNode(hasSetTextAction()).performTextInput(name)
            compose.onNodeWithText(text(R.string.save)).performClick()
            compose.waitUntil(LONG_WAIT) { runBlocking {
                val album = repository.observeCollections().first().firstOrNull { it.name == name }
                album != null && repository.observeGallery().first().any { it.id == "fixture:icloud:0016" && album.id in it.collectionIds }
            } }
        } finally { runBlocking { repository.observeCollections().first().filter { it.name == name }.forEach { repository.deleteAlbum(it.id) } } }
    }

    @Test fun accessibleCropUndoAndDirtyExitSurviveRecreation() {
        compose.waitForGalleryMedia(); compose.openGalleryMedia("fixture:icloud:0016")
        compose.waitUntil(SHORT_WAIT) { compose.onNodeWithContentDescription(text(R.string.viewer_edit)).isDisplayed() }
        compose.onNodeWithContentDescription(text(R.string.viewer_edit)).performClick()
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("photo-crop").fetchSemanticsNodes().isNotEmpty() }
        val before = compose.onNodeWithTag("photo-crop").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        val adjustment = compose.onNodeWithTag("photo-crop").fetchSemanticsNode().config[SemanticsActions.CustomActions][1]
        compose.runOnIdle { assertTrue(adjustment.action()) }
        val changed = compose.onNodeWithTag("photo-crop").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertNotEquals(before, changed)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("photo-crop").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("photo-crop").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, changed))
        Espresso.pressBack()
        compose.onNodeWithText(text(R.string.discard_edits_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.keep_editing)).performClick()
        compose.onNodeWithTag("undo-edit").performClick()
        compose.onNodeWithTag("photo-crop").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, before))
        compose.onNodeWithText(text(R.string.adjust_crop)).performClick()
        Espresso.pressBack()
        compose.onNodeWithTag("photo-crop").assertDoesNotExist()
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
    }

    private fun phoneCopies(): Set<android.net.Uri> =
        MediaStoreFixtures.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", "Pictures/Alba/")

    @Test fun selectedCloudPhotosAreSavedAsPublicPhoneCopies() {
        compose.waitForGalleryMedia()
        val before = phoneCopies()
        val graph = (compose.activity.application as AlbaApplication).graph
        val previousBatches = runBlocking { graph.batches.observe().first().map { it.batchId }.toSet() }
        try {
            compose.scrollToGalleryMedia("fixture:icloud:0016")
            compose.onNodeWithTag("media-fixture:icloud:0016").performTouchInput { longClick() }
            compose.scrollToGalleryMedia("fixture:icloud:0017")
            compose.onNodeWithTag("media-fixture:icloud:0017").performClick()
            compose.onNodeWithTag("save-selection-to-phone").performScrollTo().performClick()
            compose.waitUntil(LONG_WAIT) { runBlocking { graph.batches.observe().first().count {
                it.batchId !in previousBatches && it.action == "SAVE_TO_PHONE" && it.status == BatchItemState.DONE
            } == 2 } }
            val saved = phoneCopies() - before
            assertEquals(2, saved.size)
            saved.forEach { uri -> assertTrue(compose.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes().isNotEmpty() }) }
        } finally { (phoneCopies() - before).forEach { compose.activity.contentResolver.delete(it, null, null) } }
    }
}
