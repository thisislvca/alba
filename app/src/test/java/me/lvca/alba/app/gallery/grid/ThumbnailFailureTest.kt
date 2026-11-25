package me.lvca.alba.app.gallery.grid

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.gallery.LocalPreviewStorageLow
import me.lvca.alba.app.ui.gallery.MediaTile
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaKind
import me.lvca.alba.engine.model.MediaOrigin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailFailureTest {
    @get:Rule
    val compose = createComposeRule()

    /** A favourite iCloud video whose preview has not been downloaded yet. */
    private val media = GalleryMedia("fixture:failure:1", "VID_0001.MOV", 0, 4, 3, MediaOrigin.ICLOUD,
        MediaAvailability.CLOUD_ONLY, previewReference = null, originalReference = null, accentStartArgb = 0xFF263238, accentEndArgb = 0xFF455A64,
        kind = MediaKind.VIDEO, mimeType = "video/quicktime", durationMillis = 14_000, isFavorite = true)

    private fun showTile(storageLow: Boolean, dark: Boolean = false) = compose.setContent {
        AlbaTheme(darkTheme = dark) {
            CompositionLocalProvider(LocalPreviewStorageLow provides storageLow) {
                MediaTile(media = media, onClick = {}, onVisible = {}, selectionAction = {})
            }
        }
    }

    @Test fun missingPreviewBlamesLowPhoneStorageWhenWritesFailForSpace() {
        showTile(storageLow = true)
        compose.onNodeWithTag("tile-preview-problem", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Not enough space on this phone", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Connect and refresh", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun tileWithNoPreviewYetShowsNoGlyphButItsDurationBadge() {
        showTile(storageLow = false)
        assertWaitingTile()
    }

    @Test fun tileWithNoPreviewYetShowsNoGlyphButItsDurationBadgeInDarkTheme() {
        showTile(storageLow = false, dark = true)
        assertWaitingTile()
    }

    /** A tile still waiting for its preview is only the placeholder and the badges its catalog metadata gives it. */
    private fun assertWaitingTile() {
        compose.onNodeWithTag("tile-preview-problem", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Preview unavailable", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("tile-duration-${media.id}", useUnmergedTree = true).assertIsDisplayed().assertTextEquals("0:14")
        compose.onNodeWithContentDescription("Favorite", useUnmergedTree = true).assertIsDisplayed()
    }
}
