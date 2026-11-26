package me.lvca.alba.app.gallery

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.screenshots.ScreenshotFixtures
import me.lvca.alba.app.testing.setAlbaContent
import me.lvca.alba.app.testing.testAlbaActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seen on a cold start with a large library: the Shared Albums strip takes space only once its album previews are
 * counted, after the first photos already show. Its arrival pushed the grid down, the headers moved at once, and the
 * photo tiles slid from where they had been, so for a moment they drew over the strip.
 */
@RunWith(AndroidJUnit4::class)
class GalleryLeadingReflowTest {
    @get:Rule val compose = createComposeRule()

    private val album = ScreenshotFixtures.collections.first { it.shared != null }
    private val library = ScreenshotFixtures.library(30)
    private val sharedPhotos = ScreenshotFixtures.library(3).map { it.copy(id = "shared-${it.id}", collectionIds = setOf(album.id)) }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** Steps the paused clock one frame at a time, so the test sees the first frame that shows [tag]. */
    private fun advanceFramesUntilShown(tag: String) {
        var frames = 0
        while (compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            check(++frames < MAX_FRAMES) { "$tag never appeared" }
            compose.mainClock.advanceTimeByFrame()
        }
    }

    @Test fun photosMoveWithTheSharedAlbumsStripInsteadOfSlidingOverIt() {
        val gallery = mutableStateOf(GalleryState(items = library, catalogItems = library + sharedPhotos,
            cloudCount = library.size, deviceMediaAccess = true))
        compose.setAlbaContent(gallery, ScreenshotFixtures.signedIn, testAlbaActions())
        compose.waitForIdle()
        compose.onNodeWithTag("shared-albums-strip").assertDoesNotExist()

        // The strip appears in the frame its album's preview is counted; that frame must already be laid out.
        compose.mainClock.autoAdvance = false
        gallery.value = gallery.value.copy(collections = listOf(album))
        advanceFramesUntilShown("shared-albums-strip")
        val strip = bounds("shared-albums-strip")
        val firstTile = bounds("media-${library.first().id}")
        assertTrue("The first photo ($firstTile) starts below the Shared Albums strip ($strip)", firstTile.top >= strip.bottom)

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("The first photo is already where it settles", firstTile.top, bounds("media-${library.first().id}").top)
    }

    /** The whole library replacing the first screen's newest photos is a load, not photos arriving: nothing slides. */
    @Test fun theWholeLibraryReplacingTheFirstScreenLaysOutAtOnce() {
        val firstScreen = library.drop(3)
        val gallery = mutableStateOf(GalleryState(items = firstScreen, cloudCount = firstScreen.size, deviceMediaAccess = true,
            libraryLoaded = false))
        compose.setAlbaContent(gallery, ScreenshotFixtures.signedIn, testAlbaActions())
        compose.waitForIdle()
        val tag = "media-${firstScreen.first().id}"
        val before = bounds(tag)

        compose.mainClock.autoAdvance = false
        gallery.value = gallery.value.copy(items = library, catalogItems = library, sections = buildGallerySections(library),
            cloudCount = library.size, libraryLoaded = true)
        advanceFramesUntilShown("media-${library.first().id}")
        val firstFrame = bounds(tag)
        assertTrue("The newest day's row pushed the photo down ($before, then $firstFrame)", firstFrame.top > before.top)

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("The photo is already where it settles", firstFrame.top, bounds(tag).top)
    }

    private companion object {
        const val MAX_FRAMES = 200
    }
}
