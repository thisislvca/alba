package me.lvca.alba.app.gallery.grid

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.gallery.LocalPreviewStorageLow
import me.lvca.alba.app.ui.gallery.MediaTile
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaOrigin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailFailureTest {
    @get:Rule
    val compose = createComposeRule()

    private val media = GalleryMedia("fixture:failure:1", "IMG_0001.JPG", 0, 4, 3, MediaOrigin.ICLOUD,
        MediaAvailability.CLOUD_ONLY, previewReference = null, originalReference = null, accentStartArgb = 0xFF263238, accentEndArgb = 0xFF455A64)

    private fun showTile(storageLow: Boolean) = compose.setContent {
        AlbaTheme(darkTheme = false) {
            CompositionLocalProvider(LocalPreviewStorageLow provides storageLow) {
                MediaTile(media = media, onClick = {}, onVisible = {}, selectionAction = {})
            }
        }
    }

    @Test fun missingPreviewBlamesLowPhoneStorageWhenWritesFailForSpace() {
        showTile(storageLow = true)
        compose.onNodeWithContentDescription("Not enough space on this phone", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Connect and refresh", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun missingPreviewSuggestsReconnectingOtherwise() {
        showTile(storageLow = false)
        compose.onNodeWithContentDescription("Preview unavailable. Connect and refresh to try again.", substring = true, useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
