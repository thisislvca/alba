package me.lvca.alba.app.gallery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.screenshots.ScreenshotFixtures
import me.lvca.alba.app.testing.setAlbaContent
import me.lvca.alba.engine.model.GalleryQuery
import me.lvca.alba.engine.model.MediaOrigin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The count under the grid's last photo describes the view it ends. */
@RunWith(AndroidJUnit4::class)
class GalleryFooterTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun totals(cloud: Int, phone: Int) = context.getString(R.string.cloud_phone_counts,
        context.resources.getQuantityString(R.plurals.cloud_photo_count, cloud, cloud), context.getString(R.string.phone_photo_count, phone))

    @Test fun recentlyDeletedCountsOnlyItself() {
        val deleted = ScreenshotFixtures.library(3).map { it.copy(isTrashed = true) }
        compose.setAlbaContent(ScreenshotFixtures.gallery().copy(query = GalleryQuery(origin = MediaOrigin.ICLOUD, trashOnly = true),
            items = deleted, catalogItems = deleted, sections = buildGallerySections(deleted), cloudCount = 12_607, deviceCount = 7),
            ScreenshotFixtures.signedIn)
        compose.onNodeWithTag("gallery-grid").performScrollToKey("summary")
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.item_count, 3, 3)).assertIsDisplayed()
        compose.onNodeWithText(totals(12_607, 7)).assertDoesNotExist()
    }

    @Test fun theLibraryStillEndsWithItsTotals() {
        compose.setAlbaContent(ScreenshotFixtures.gallery(3).copy(cloudCount = 3, deviceCount = 7), ScreenshotFixtures.signedIn)
        compose.onNodeWithTag("gallery-grid").performScrollToKey("summary")
        compose.onNodeWithText(totals(3, 7)).assertIsDisplayed()
    }
}
