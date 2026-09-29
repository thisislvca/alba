package dev.mela.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.app.ui.MediaThumbnail
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailRevisionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sharperResourceArrivingDuringATapDoesNotLoseTheTap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preview = File.createTempFile("tap-preview", ".png", context.cacheDir)
        val original = File.createTempFile("tap-original", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        for (file in listOf(preview, original)) file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val reference = mutableStateOf(preview.path)
        val taps = java.util.concurrent.atomic.AtomicInteger()
        try {
            compose.setContent {
                MediaThumbnail(reference.value, 0xff000000, 0xff000000, null,
                    Modifier.size(200.dp).testTag("upgrading-photo"), contentScale = ContentScale.Fit,
                    zoomable = true, sharedMediaId = "same-photo", sourceRevision = "same-revision", onTap = { taps.incrementAndGet() })
            }
            compose.onNodeWithTag("upgrading-photo").performTouchInput { down(center) }
            compose.runOnIdle { reference.value = original.path }
            compose.onNodeWithTag("upgrading-photo").performTouchInput { advanceEventTime(60); up() }
            compose.mainClock.advanceTimeBy(600)
            compose.waitUntil(2_000) { taps.get() == 1 }
        } finally { preview.delete(); original.delete() }
    }

    @Test fun changedRevisionReplacesCachedPixelsAtTheSamePath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("thumbnail-revision", ".png", context.cacheDir)
        val revision = mutableStateOf("first")
        fun writeColor(color: Int) {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun hasColor(red: Boolean): Boolean {
            val pixels = compose.onNodeWithTag("thumbnail").captureToImage().toPixelMap()
            val color = pixels[pixels.width / 2, pixels.height / 2]
            return if (red) color.red > .9f && color.blue < .1f else color.blue > .9f && color.red < .1f
        }
        try {
            writeColor(android.graphics.Color.RED)
            compose.setContent {
                MediaThumbnail(file.path, 0xff000000, 0xff000000, null,
                    Modifier.size(100.dp).testTag("thumbnail"), sourceRevision = revision.value)
            }
            compose.waitUntil(5_000) { hasColor(red = true) }
            writeColor(android.graphics.Color.BLUE)
            compose.runOnIdle { revision.value = "second" }
            compose.waitUntil(5_000) { hasColor(red = false) }
        } finally { file.delete() }
    }

    @Test fun largerViewerKeepsPreviewPixelsWhileOriginalIsUnavailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preview = File.createTempFile("thumbnail-continuity", ".png", context.cacheDir)
        val original = File(context.cacheDir, "missing-original-${System.nanoTime()}.png")
        val viewer = mutableStateOf(false)
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        preview.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        fun isRed(): Boolean {
            val pixels = compose.onNodeWithTag("continuity").captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            return center.red > .9f && center.blue < .1f
        }
        try {
            compose.setContent {
                MediaThumbnail(if (viewer.value) original.path else preview.path, 0xff000000, 0xff000000, null,
                    Modifier.size(if (viewer.value) 280.dp else 80.dp).testTag("continuity"),
                    contentScale = if (viewer.value) ContentScale.Fit else ContentScale.Crop,
                    fallbackReference = preview.path, sourceRevision = "unchanged")
            }
            compose.waitUntil(5_000) { isRed() }
            // A cache-only preview must survive both the reference switch and larger viewer bounds.
            preview.delete()
            compose.runOnIdle { viewer.value = true }
            compose.waitForIdle()
            org.junit.Assert.assertTrue("Viewer flashed a placeholder instead of keeping the preview", isRed())
        } finally { preview.delete() }
    }
}
