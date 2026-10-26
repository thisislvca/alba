package me.lvca.alba.app.viewer

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.screenshots.ScreenshotFixtures
import me.lvca.alba.app.testing.GalleryStateActions
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.setAlbaContent
import me.lvca.alba.app.testing.testAlbaActions
import me.lvca.alba.app.ui.ExternalUse
import me.lvca.alba.engine.model.MediaAvailability
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The photo viewer's own controls, from fixed state: offline originals and quick-scale zoom. */
@RunWith(AndroidJUnit4::class)
class ViewerControlsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int) = context.getString(id)

    // A photo, not a video, whose preview is cached but whose original is not.
    private val gallery = ScreenshotFixtures.gallery()
    private val photo = gallery.items[1]

    @Test fun detailsKeepAnOriginalOfflineAndRemoveTheOfflineCopy() {
        val calls = mutableListOf<String>()
        val actions = object : GalleryStateActions(gallery.copy(selectedMedia = photo)) {
            // As the repository reports once the original is downloaded or removed.
            fun available(availability: MediaAvailability) {
                state.value = state.value.copy(selectedMedia = photo.copy(availability = availability),
                    items = state.value.items.map { if (it.id == photo.id) it.copy(availability = availability) else it })
            }
            override fun keepOriginalOffline(mediaId: String) { calls += "keep $mediaId"; available(MediaAvailability.ORIGINAL_CACHED) }
            override fun removeCachedOriginal(mediaId: String) { calls += "remove $mediaId"; available(MediaAvailability.PREVIEW_CACHED) }
        }
        compose.setAlbaContent(actions.state, ScreenshotFixtures.signedIn, actions)
        compose.openViewerDetails()
        compose.onAllNodesWithText(photo.fileName).onFirst().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.keep_original_offline)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.remove_offline_copy)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.keep_original_offline)).performScrollTo().assertIsDisplayed()
        assertEquals(listOf("keep ${photo.id}", "remove ${photo.id}"), calls)
    }

    @Test fun doubleTapAndDragZoomsAroundTheTapAndKeepsTheZoom() {
        val actions = GalleryStateActions(gallery.copy(selectedMedia = photo))
        compose.setAlbaContent(actions.state, ScreenshotFixtures.signedIn, actions)
        val image = compose.onNodeWithTag("viewer-image-${photo.id}")
        val zoomed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.photo_zoomed))
        val fit = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.photo_fit))
        // Tap, then keep the second touch down and drag it down: Google Photos' quick scale zooms in and stays zoomed.
        image.performTouchInput {
            click(center)
            advanceEventTime(100)
            down(center)
            repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(0f, 25f)) }
            up()
        }
        compose.waitForIdle()
        image.assert(zoomed)
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
        // Dragging back up past where it started returns to fit.
        image.performTouchInput {
            advanceEventTime(500)
            click(center)
            advanceEventTime(100)
            down(center)
            repeat(12) { moveBy(androidx.compose.ui.geometry.Offset(0f, -25f)) }
            up()
        }
        compose.waitForIdle()
        image.assert(fit)
    }

    @Test fun overflowHandsTheOriginalToOtherAppsAndStillOpensDetails() {
        val uses = mutableListOf<Pair<String, ExternalUse>>()
        val actions = GalleryStateActions(gallery.copy(selectedMedia = photo),
            testAlbaActions(useElsewhere = { media, use -> uses += media.id to use }))
        compose.setAlbaContent(actions.state, ScreenshotFixtures.signedIn, actions)
        compose.onNodeWithTag("viewer-more").performClick()
        listOf(R.string.viewer_use_as, R.string.viewer_open_with, R.string.viewer_edit_in, R.string.viewer_print).forEach {
            compose.onNodeWithText(text(it)).assertIsDisplayed()
        }
        compose.onNodeWithTag("viewer-use-use_as").performClick()
        compose.onNodeWithTag("viewer-more").performClick()
        compose.onNodeWithTag("viewer-use-print").performClick()
        assertEquals(listOf(photo.id to ExternalUse.USE_AS, photo.id to ExternalUse.PRINT), uses)
        compose.onNodeWithTag("viewer-more").performClick()
        compose.onNodeWithTag("viewer-menu-details").performClick()
        compose.onNodeWithTag("photo-details-panel").assertIsDisplayed()
    }
}
