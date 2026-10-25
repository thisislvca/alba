package me.lvca.alba.app.gallery

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.Smoke
import me.lvca.alba.app.testing.UI_WAIT
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Exercises the new entry points against the real demo repository and video previews. */
class GalleryAppearanceTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }

    @Smoke @Test fun statusBarAreaIsStronglyTintedOverScrolledPhotos() {
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("shared-albums-strip").fetchSemanticsNodes().isNotEmpty() }
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
}
