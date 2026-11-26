package me.lvca.alba.app.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.screenshots.ScreenshotFixtures
import me.lvca.alba.app.testing.GalleryStateActions
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.setAlbaContent
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The viewer's Info: Apple Photos' order with the date once, a title always in reach, and honest decode failures. */
@RunWith(AndroidJUnit4::class)
class PhotoInfoTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int) = context.getString(id)

    private val gallery = ScreenshotFixtures.gallery()

    /** A JPEG whose EXIF says an iPhone 15 Pro's main camera took it. */
    private fun iPhoneJpeg(): File = File(context.cacheDir, "info-iphone.jpg").also { file ->
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(90, 140, 180)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_MAKE, "Apple")
            setAttribute(ExifInterface.TAG_MODEL, "iPhone 15 Pro")
            setAttribute(ExifInterface.TAG_LENS_MODEL, "iPhone 15 Pro back triple camera 6.765mm f/1.78")
            setAttribute(ExifInterface.TAG_F_NUMBER, "1780/1000")
            setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "1/75")
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "640")
            setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "6765/1000")
            setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, "24")
            saveAttributes()
        }
    }

    private fun open(photo: GalleryMedia) {
        val items = gallery.items.map { if (it.id == photo.id) photo else it }
        val actions = GalleryStateActions(gallery.copy(items = items, selectedMedia = photo))
        compose.setAlbaContent(actions.state, ScreenshotFixtures.signedIn, actions)
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    @Test fun infoFollowsApplePhotosAndShowsTheDateOnce() {
        val base = gallery.items[1]
        // Added to iCloud minutes after it was taken: nothing the title doesn't already say.
        val photo = base.copy(originalReference = iPhoneJpeg().absolutePath, addedAtEpochMillis = base.capturedAtEpochMillis + 5 * 60_000L,
            byteCount = 2_400_000L, details = MediaDetails(latitude = 45.4642, longitude = 9.19, timeZoneOffsetSeconds = 7200))
        open(photo)
        compose.openViewerDetails()
        compose.waitUntil(UI_WAIT) { compose.onAllNodes(hasTestTag("photo-device"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("photo-device", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("iPhone 15 Pro").assertExists()
        compose.onNodeWithText("Main Camera — 24 mm ƒ1.78").assertExists()
        compose.onNodeWithText("ISO 640 · f/1.78 · 1/75 s").assertExists()
        compose.onNodeWithText("IMG_1.HEIC").assertExists()
        compose.onNodeWithText("HEIC").assertExists()

        // Title, then device, lens, file, size and dimensions, exposure, location, and where the photo is kept. Every
        // fact scrolls together, so their order on screen is their order in the sheet, wherever it is scrolled.
        val facts = listOf("photo-device", "photo-lens", "photo-file", "photo-file-facts", "photo-exposure", "photo-location", "photo-source")
        val tops = facts.map(::top)
        assertEquals("Info order", tops.sorted(), tops)
        assertEquals("Each fact has its own line", facts.size, tops.distinct().size)
        assertTrue("The date title comes first", compose.onNodeWithTag("photo-info-title").fetchSemanticsNode().boundsInRoot.bottom <= tops.first())

        // The date is the title and appears nowhere else: no "Taken" row, no "Added to iCloud", no "Details" heading.
        val title = compose.onNodeWithTag("photo-taken", useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
        val date = title.substringBefore(',')
        assertTrue("Title shows the date: $title", date.isNotBlank())
        assertEquals(1, compose.onAllNodes(hasText(date, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag("photo-added").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.details)).assertDoesNotExist()
    }

    @Test fun infoKeepsAddedToICloudWhenItCameMuchLater() {
        val base = gallery.items[1]
        open(base.copy(addedAtEpochMillis = base.capturedAtEpochMillis + 30L * 24 * 3_600_000L))
        compose.openViewerDetails()
        compose.onNodeWithTag("photo-info-scroll").performScrollToNode(hasTestTag("photo-added"))
        compose.onNodeWithText(text(R.string.photo_added)).assertIsDisplayed()
    }

    @Test fun theDateStaysInReachWhereverTheSheetIsScrolled() {
        val base = gallery.items[1]
        open(base.copy(details = MediaDetails(caption = "Lake", description = "Evening at the lake", latitude = 45.0, longitude = 9.0)))
        compose.openViewerDetails()
        val panel = compose.onNodeWithTag("photo-details-panel").fetchSemanticsNode().boundsInRoot
        fun titleInPanel() {
            compose.onNodeWithTag("photo-taken", useUnmergedTree = true).assertIsDisplayed()
            val title = compose.onNodeWithTag("photo-info-title").fetchSemanticsNode().boundsInRoot
            assertTrue("Title $title starts inside the sheet $panel", title.top >= panel.top && title.bottom <= panel.bottom)
        }
        titleInPanel()
        // The first fact under the title is fully below it, not clipped by the sheet's top.
        assertTrue(top("photo-caption") >= compose.onNodeWithTag("photo-info-title").fetchSemanticsNode().boundsInRoot.bottom)
        compose.onNodeWithTag("photo-info-scroll").performScrollToNode(hasTestTag("photo-source"))
        titleInPanel()
    }

    @Test fun anUnsupportedFormatSaysSoAndOffersNoRetry() {
        // A HEIC the decoder can't read, with no preview to fall back on.
        val heic = File(context.cacheDir, "unsupported.heic").apply {
            writeBytes(byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray() + ByteArray(200) { 7 })
        }
        // Its own revision, so no decoded copy another test left in the process-wide thumbnail caches stands in for it.
        val photo = gallery.items[1].copy(originalReference = heic.absolutePath, sourceRevision = "unsupported")
        open(photo)
        // The neighbouring pages, which have no preview at all, keep their own Try again; this page offers none.
        val unsupported = hasText(text(R.string.photo_format_unsupported))
        compose.waitUntil(UI_WAIT) { compose.onAllNodes(unsupported, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val onThisPage = hasAnyAncestor(hasTestTag("viewer-photo-status") and hasAnyDescendant(unsupported))
        compose.onAllNodes(hasTestTag("retry-decode"), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasTestTag("retry-preview") and onThisPage, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun anUndecodableOriginalFallsBackToItsPreview() {
        val heic = File(context.cacheDir, "fallback.heic").apply {
            writeBytes(byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray() + ByteArray(200) { 7 })
        }
        val preview = File(context.cacheDir, "fallback-preview.png").also { file ->
            val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val photo = gallery.items[1].copy(originalReference = heic.absolutePath, previewReference = preview.absolutePath,
            sourceRevision = "fallback")
        open(photo)
        compose.waitUntil(UI_WAIT) {
            compose.onAllNodes(hasTestTag("photo-viewer-${photo.id}"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text(R.string.photo_format_unsupported)).assertDoesNotExist()
    }
}
