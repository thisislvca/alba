package me.lvca.alba.app.transfers

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaViewModel
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.pressBack
import me.lvca.alba.app.testing.runBlockingWithMainLooper
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitUntilSettled
import me.lvca.alba.engine.model.OneShotUploadSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Uploads into the demo account: the sample, picked photos with their exact bytes, and refused corrupt shared copies. */
@RunWith(AndroidJUnit4::class)
class DemoUploadTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose
    private val graph get() = app.application.graph
    private val repository get() = graph.galleryRepository

    /** The demo library's record named [fileName], once an upload has added it. The fixture source needs no main looper. */
    private fun uploaded(fileName: String): String? =
        runBlocking { graph.demoCatalog.fetchPage(null, 100).records.firstOrNull { it.fileName == fileName }?.id }

    @Test fun sampleUploadDialogUsesAlbaThemeColors() {
        compose.onNodeWithTag("gallery-upload").performClick()
        val dialog = compose.onNode(isDialog()).captureToImage().toPixelMap()
        val container = dialog[dialog.width / 2, 4]
        val dark = app.activity.resources.configuration.isNightModeActive
        // Alba's surfaceContainerHigh, not Material's default lavender (0xFFECE6F0 / 0xFF2B2930).
        assertEquals(if (dark) Color(0xFF292D35) else Color(0xFFE6EAF0), container)
    }

    @Test fun sampleAndPickedUploadsReachTheLibraryWithTheirBytesAndBadSharedCopiesAreRejected() {
        // The sample upload goes through the public entry point and appears in the library.
        compose.onNodeWithTag("gallery-upload").performClick()
        compose.onNodeWithText(app.string(R.string.demo_upload_disclosure)).assertIsDisplayed()
        compose.onNodeWithTag("demo-upload-sample").performClick()
        compose.waitUntilSettled { uploaded("Sample upload.webp") != null }
        val sample = requireNotNull(uploaded("Sample upload.webp"))
        compose.scrollToGalleryMedia(sample)
        compose.onNodeWithTag("media-$sample").performClick()
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
        compose.pressBack()
        compose.onNodeWithTag("media-pager").assertDoesNotExist()
        assertNull(graph.demoCatalog.writeAccountId)

        // A picked photo keeps its exact bytes; a picked file that is gone is skipped.
        val file = File(File(app.activity.cacheDir, "shares").apply { mkdirs() }, "Selected-review-photo.png")
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            compose.runOnUiThread {
                val authority = "${app.activity.packageName}.shares"
                val selected = FileProvider.getUriForFile(app.activity, authority, file)
                val missing = FileProvider.getUriForFile(app.activity, authority, File(file.parentFile, "missing-review.png"))
                ViewModelProvider(app.activity)[AlbaViewModel::class.java].transfers.uploadPicked(listOf(selected, missing))
            }
            compose.waitUntilSettled { uploaded(file.name) != null }
            val id = requireNotNull(uploaded(file.name))
            compose.scrollToGalleryMedia(id)
            runBlockingWithMainLooper {
                val selected = requireNotNull(repository.findMedia(id))
                assertEquals(96, selected.width)
                assertEquals(64, selected.height)
                assertEquals("image/png", selected.mimeType)
                val exported = ByteArrayOutputStream()
                repository.exportOriginal(selected.id, exported)
                assertArrayEquals(file.readBytes(), exported.toByteArray())
            }
            compose.onNodeWithTag("media-$id").performClick()
            compose.onNodeWithTag("media-pager").assertIsDisplayed()

            // A shared upload whose bytes do not match their hash is refused and leaves the album alone.
            val owner = "shared:demo-library:private:owner:two"
            runBlockingWithMainLooper {
                val before = graph.demoCatalog.sharedAlbums().records.size
                val corrupt = object : OneShotUploadSource {
                    override val byteCount = file.length()
                    override val sha256Hex = "bad-hash"
                    override fun openOnce() = file.inputStream()
                }
                assertTrue(runCatching { graph.demoCatalog.uploadSharedPhoto(owner, file.name, corrupt, "corrupt") }.isFailure)
                assertEquals(before, graph.demoCatalog.sharedAlbums().records.size)
            }
            assertNull(graph.accountManager.session.value)
        } finally {
            file.delete()
        }
    }
}
