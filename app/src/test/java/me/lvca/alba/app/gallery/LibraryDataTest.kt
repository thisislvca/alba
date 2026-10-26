package me.lvca.alba.app.gallery

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.screenshots.ScreenshotFixtures
import me.lvca.alba.app.testing.GalleryStateActions
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.setAlbaContent
import me.lvca.alba.app.testing.testAlbaActions
import me.lvca.alba.engine.model.MediaDetails
import me.lvca.alba.engine.model.SmartCollection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Hidden, captions and places, photo types and search suggestions, from fixed state. */
@RunWith(AndroidJUnit4::class)
class LibraryDataTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int) = context.getString(id)

    @Test fun detailsShowTheCaptionAndPlaceAndHideThePhoto() {
        val base = ScreenshotFixtures.gallery()
        val photo = base.items[1].copy(details = MediaDetails(caption = "Sunset at the harbour", description = "Last swim",
            latitude = 44.3036, longitude = 9.2101, altitude = 4.0, timeZoneOffsetSeconds = 7200))
        val gallery = base.copy(items = base.items.map { if (it.id == photo.id) photo else it }, selectedMedia = photo)
        val hidden = mutableListOf<Pair<List<String>, Boolean>>()
        val actions = GalleryStateActions(gallery, testAlbaActions(setHidden = { ids, hide -> hidden += ids to hide }))
        compose.setAlbaContent(actions.state, ScreenshotFixtures.signedIn, actions)
        compose.openViewerDetails()
        compose.onNodeWithText("Sunset at the harbour").assertIsDisplayed()
        compose.onNodeWithText("Last swim").assertIsDisplayed()
        compose.onNodeWithText("44.303600, 9.210100").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("open-in-maps").performScrollTo().performClick()
        val intent = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("geo:44.303600,9.210100?q=44.303600,9.210100", intent.dataString)
        compose.onNodeWithText(text(R.string.hide)).performScrollTo().performClick()
        assertEquals(listOf(listOf(photo.id) to true), hidden)
    }

    @Test fun collectionsOfferHiddenBehindAnUnlock() {
        var unlocks = 0
        val actions = GalleryStateActions(ScreenshotFixtures.gallery(), testAlbaActions(openHidden = { unlocks++ }))
        compose.setAlbaContent(actions.state, ScreenshotFixtures.signedIn, actions)
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasTestTag("collection-hidden"))
        compose.onNodeWithTag("collection-hidden").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.hidden)).performClick()
        assertEquals(1, unlocks)
    }

    @Test fun searchSuggestsEveryMediaType() {
        val actions = GalleryStateActions(ScreenshotFixtures.gallery())
        compose.setAlbaContent(actions.state, ScreenshotFixtures.signedIn, actions)
        compose.onNodeWithTag("tab-SEARCH").performClick()
        compose.onNodeWithTag("smart-collection-chips").assertIsDisplayed()
        compose.onNodeWithTag("smart-chip-${SmartCollection.VIDEOS.id}").performClick()
        assertEquals(SmartCollection.VIDEOS.id, actions.state.value.query.collectionId)
    }

    @Test fun tilesNameTheirPhotoType() {
        val base = ScreenshotFixtures.gallery(6)
        val items = base.items.mapIndexed { index, item ->
            when (index) {
                0 -> item.copy(collectionIds = setOf(SmartCollection.PANORAMAS.id))
                1 -> item.copy(details = MediaDetails(burstId = "burst"))
                2 -> item.copy(mimeType = "image/x-adobe-dng")
                else -> item
            }
        }
        val actions = GalleryStateActions(base.copy(items = items, sections = buildGallerySections(items)))
        compose.setAlbaContent(actions.state, ScreenshotFixtures.signedIn, actions)
        compose.onNodeWithTag("tile-badge-panorama", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("tile-badge-burst", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("tile-badge-raw", useUnmergedTree = true).assertIsDisplayed()
    }
}
