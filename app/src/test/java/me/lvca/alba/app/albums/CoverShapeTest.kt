package me.lvca.alba.app.albums

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.lvca.alba.app.screenshots.ScreenshotFixtures
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.setAlbaContent
import me.lvca.alba.app.ui.gallery.GalleryFeel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Album and collection covers fill their rounded shape edge to edge: the image is clipped to the same shape as the
 * cover around it, so no frame shows along an edge and no corner of the image disagrees with the cover's corner.
 * Every photo here has the same one-colour preview, so any pixel that isn't it is a frame, a gap or the page behind a
 * corner.
 */
@RunWith(AndroidJUnit4::class)
class CoverShapeTest {
    @get:Rule val compose = createComposeRule()

    private val solid = 0xFFD0342C.toInt()
    private val photo = Color(solid)

    // Tiles without a preview show only the flat placeholder, so every photo gets a real one-colour preview file.
    private val preview: File = File.createTempFile("cover-shape", ".png",
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir).apply {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(solid)
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @After fun deletePreview() {
        preview.delete()
    }

    private fun open() {
        val gallery = ScreenshotFixtures.gallery()
        val items = gallery.items.mapIndexed { index, media ->
            media.copy(previewReference = preview.path, collectionIds = if (index < 4) setOf("album-trips") else emptySet())
        }
        compose.setAlbaContent(gallery.copy(items = items, catalogItems = items), ScreenshotFixtures.signedIn)
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-albums").assertIsDisplayed()
    }

    @Test fun theAlbumsMosaicFillsItsCoverShape() {
        open()
        assertCoverFillsItsShape("collection-albums-cover")
    }

    @Test fun anAlbumCoverFillsItsCardShape() {
        open()
        compose.onNodeWithTag("collection-albums").performClick()
        assertCoverFillsItsShape("album-cover-album-trips")
    }

    private fun capture(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()

    /**
     * Previews decode off the main thread and then fade in over the placeholder. Wait until the middle of each quarter
     * (each mosaic cell, or the middle of a single cover) shows the photo, then run the clock past the reveal, so the
     * cover is in its final state. The points asserted on are the edges and corners, never these.
     */
    private fun settle(tag: String) {
        compose.waitUntil(SHORT_WAIT) {
            val image = capture(tag)
            listOf(1, 3).all { x -> listOf(1, 3).all { y -> image[image.width * x / 4, image.height * y / 4].closeTo(photo) } }
        }
        compose.mainClock.advanceTimeBy(GalleryFeel.TILE_FADE_IN_MILLIS.toLong())
        compose.waitForIdle()
    }

    private fun assertCoverFillsItsShape(tag: String) {
        settle(tag)
        val image = capture(tag)
        // Points 1dp inside each edge, away from the mosaic's gaps and the placeholder glyph at each photo's centre.
        val w = image.width
        val h = image.height
        fun px(x: Int, y: Int) = image[x.coerceIn(0, w - 1), y.coerceIn(0, h - 1)]
        val one = with(compose.density) { 1.dp.roundToPx() }
        // Inside the corner curve of the cover's shape, but outside where an inset or smaller-radius image would start.
        val nearCorner = with(compose.density) { 6.dp.roundToPx() }
        val inside = mapOf(
            "start edge" to px(one, h / 4), "end edge" to px(w - 1 - one, h / 4),
            "top edge" to px(w / 4, one), "bottom edge" to px(w / 4, h - 1 - one),
            "top-start corner" to px(nearCorner, nearCorner), "top-end corner" to px(w - 1 - nearCorner, nearCorner),
            "bottom-start corner" to px(nearCorner, h - 1 - nearCorner), "bottom-end corner" to px(w - 1 - nearCorner, h - 1 - nearCorner),
        )
        inside.forEach { (where, color) ->
            assertTrue("$tag: the $where shows $color, not the photo: a frame or a mismatched clip", color.closeTo(photo))
        }
        // The very corners are rounded off by the cover's shape, for the image and the cover alike.
        val corners = mapOf("top-start" to px(0, 0), "top-end" to px(w - 1, 0), "bottom-start" to px(0, h - 1), "bottom-end" to px(w - 1, h - 1))
        corners.forEach { (where, color) ->
            assertTrue("$tag: the $where corner is square: the photo isn't clipped to the cover's shape", !color.closeTo(photo))
        }
    }

    private fun Color.closeTo(other: Color) = abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue) < .08f
}
