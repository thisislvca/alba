package me.lvca.alba.app.viewer

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.openGalleryMedia
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The viewer's favourite heart, drawn with native graphics so its colour can be read from the pixels. */
@RunWith(AndroidJUnit4::class)
class ViewerFavoriteTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose

    @Test fun favoriteHeartIsFilledRedRatherThanThePrimaryBlue() {
        compose.openGalleryMedia("fixture:icloud:0001")
        val unfavorite = app.string(R.string.unfavorite)
        fun isFavorite() = compose.onNodeWithTag("viewer-favorite").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(unfavorite)
        // Set the favourite through the viewer when the demo photo is not one already.
        if (!isFavorite()) compose.onNodeWithTag("viewer-favorite").performClick()
        compose.waitUntil(SHORT_WAIT) { isFavorite() }
        compose.waitForIdle()
        val heart = compose.onNodeWithTag("viewer-favorite").captureToImage().toPixelMap()
        val pixels = (0 until heart.width).flatMap { x -> (0 until heart.height).map { y -> heart[x, y] } }
        assertTrue("The set favourite is drawn in red", pixels.any { it.red > .8f && it.green < .4f && it.blue < .4f })
        assertTrue("No primary blue in the heart", pixels.none { it.blue > .6f && it.red < .3f })
    }
}
