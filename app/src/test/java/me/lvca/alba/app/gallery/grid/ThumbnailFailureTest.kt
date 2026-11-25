package me.lvca.alba.app.gallery.grid

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.gallery.LocalPreviewStorageLow
import me.lvca.alba.app.ui.gallery.MediaTile
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaKind
import me.lvca.alba.engine.model.MediaOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ThumbnailFailureTest {
    @get:Rule
    val compose = createComposeRule()

    /** A favourite iCloud video whose preview has not been downloaded yet. */
    private val media = GalleryMedia("fixture:failure:1", "VID_0001.MOV", 0, 4, 3, MediaOrigin.ICLOUD,
        MediaAvailability.CLOUD_ONLY, previewReference = null, originalReference = null, accentStartArgb = 0xFF263238, accentEndArgb = 0xFF455A64,
        kind = MediaKind.VIDEO, mimeType = "video/quicktime", durationMillis = 14_000, isFavorite = true)

    private var placeholder = Color.Unspecified
    private var ink = Color.Unspecified

    private fun showTile(tile: GalleryMedia = media, storageLow: Boolean = false, dark: Boolean = false) = compose.setContent {
        AlbaTheme(darkTheme = dark) {
            placeholder = MaterialTheme.colorScheme.surfaceContainerHigh
            ink = MaterialTheme.colorScheme.onSurfaceVariant
            CompositionLocalProvider(LocalPreviewStorageLow provides storageLow) {
                Box(Modifier.size(120.dp)) { MediaTile(media = tile, onClick = {}, onVisible = {}, selectionAction = {}) }
            }
        }
    }

    @Test fun missingPreviewBlamesLowPhoneStorageWhenWritesFailForSpace() {
        showTile(storageLow = true)
        compose.onNodeWithTag("tile-preview-problem", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Not enough space on this phone", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Connect and refresh", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun tileWithNoPreviewYetShowsNoGlyphOrScrimAndItsDurationInQuietInk() {
        showTile()
        assertWaitingTile()
    }

    @Test fun tileWithNoPreviewYetShowsNoGlyphOrScrimAndItsDurationInQuietInkInDarkTheme() {
        showTile(dark = true)
        assertWaitingTile()
    }

    /** Once the photo has faded in, its scrim darkens the bottom of the tile and the duration turns white. */
    @Test fun loadedTileKeepsItsScrimAndWhiteBadges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("loaded-tile", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(200, 200, 200))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val loaded = media.copy(id = "fixture:failure:loaded", previewReference = file.path, availability = MediaAvailability.PREVIEW_CACHED)
        try {
            showTile(loaded)
            compose.waitUntil(SHORT_WAIT) {
                compose.onAllNodesWithTag("photo-tile-${loaded.id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            val tile = compose.onNodeWithTag("media-${loaded.id}").captureToImage().toPixelMap()
            val top = tile[2, 2]
            val bottom = tile[2, tile.height - 2]
            assertTrue("bottom $bottom should be darker than top $top", bottom.red < top.red - .3f)
            val duration = compose.onNodeWithTag("tile-duration-${loaded.id}", useUnmergedTree = true).captureToImage().toPixelMap()
            assertTrue("duration is white over the photo", duration.pixels().any { it.red > .95f && it.green > .95f && it.blue > .95f })
        } finally { file.delete() }
    }

    /** A tile still waiting for its preview is only the placeholder and the badges its catalog metadata gives it. */
    private fun assertWaitingTile() {
        compose.onNodeWithTag("tile-preview-problem", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Preview unavailable", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("tile-duration-${media.id}", useUnmergedTree = true).assertIsDisplayed().assertTextEquals("0:14")
        compose.onNodeWithContentDescription("Favorite", useUnmergedTree = true).assertIsDisplayed()
        // No scrim: the bottom of the tile, where a photo's scrim would be darkest, is the bare placeholder.
        val tile = compose.onNodeWithTag("media-${media.id}").captureToImage().toPixelMap()
        assertSameColor(placeholder, tile[2, tile.height - 2])
        assertSameColor(placeholder, tile[2, 2])
        // The duration is drawn in quiet ink, never white: no pixel is lighter than the ink or the placeholder.
        val duration = compose.onNodeWithTag("tile-duration-${media.id}", useUnmergedTree = true).captureToImage().toPixelMap().pixels()
        val lightestRed = maxOf(ink.red, placeholder.red) + TOLERANCE
        val lightestGreen = maxOf(ink.green, placeholder.green) + TOLERANCE
        assertTrue("duration has a pixel lighter than its ink", duration.all { it.red <= lightestRed && it.green <= lightestGreen })
        assertTrue("duration is drawn in its ink", duration.any { abs(it.red - ink.red) < INK_MATCH && abs(it.blue - ink.blue) < INK_MATCH })
    }

    private fun assertSameColor(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, TOLERANCE)
        assertEquals(expected.green, actual.green, TOLERANCE)
        assertEquals(expected.blue, actual.blue, TOLERANCE)
    }

    private fun PixelMap.pixels(): List<Color> = (0 until height).flatMap { y -> (0 until width).map { x -> this[x, y] } }

    private companion object {
        const val TOLERANCE = .02f
        const val INK_MATCH = .08f
    }
}
