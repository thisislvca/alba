package me.lvca.alba.app.gallery

import android.content.res.Configuration
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.core.view.WindowCompat
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.Smoke
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.openGalleryMedia
import me.lvca.alba.app.testing.pressBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** How the gallery looks under the real system bars, which only a device draws. */
class GalleryAppearanceTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose

    @Smoke @Test fun statusBarAreaIsStronglyTintedOverScrolledPhotos() {
        compose.onNodeWithTag("gallery-grid").performTouchInput { swipeUp() }
        compose.waitForIdle()
        // Like Google Photos, photos stay faintly visible under the clock, but the tint keeps every pixel close to
        // the header colour so the status icons stay readable over any photo.
        val scrim = compose.onNodeWithTag("gallery-status-scrim").captureToImage().toPixelMap()
        val colors = (0 until scrim.width step 6).flatMap { x -> (0 until scrim.height step 3).map { y -> scrim[x, y] } }
        val base = colors.first()
        colors.forEach { color ->
            val spread = maxOf(abs(color.red - base.red), abs(color.green - base.green), abs(color.blue - base.blue))
            assertTrue("$color strays from the tint $base", spread <= .16f)
        }
    }

    /** The viewer darkens the status bar icons' background; the gallery must restore its own contrast afterwards. */
    @Test fun galleryRestoresStatusBarContrastAfterTheViewer() {
        compose.openGalleryMedia("fixture:icloud:0017")
        compose.pressBack()
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle {
            val window = app.activity.window
            val lightTheme = app.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
            assertEquals("Gallery must restore system-bar contrast after the viewer exits", lightTheme,
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars)
        }
    }
}
