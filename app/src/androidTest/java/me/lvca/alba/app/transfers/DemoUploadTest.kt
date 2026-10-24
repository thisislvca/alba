package me.lvca.alba.app.transfers

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.AlbaViewModel
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.LONG_WAIT
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.engine.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class DemoUploadTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private val graph get() = (compose.activity.application as AlbaApplication).graph
    private val repository get() = graph.galleryRepository
    private fun label(id: Int) = compose.activity.getString(id)

    @Test fun manualSampleUploadUsesPublicEntryPointAndAppearsInTheLibrary() {
        compose.waitUntil(LONG_WAIT) { compose.onAllNodesWithTag("account-button").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(label(R.string.upload_photos)).performClick()
        compose.onNodeWithText(label(R.string.demo_upload_disclosure)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.demo_upload_sample)).performClick()
        var id: String? = null
        compose.waitUntil(UI_WAIT) {
            id = runBlocking { graph.demoCatalog.fetchPage(null, 100).records.firstOrNull { it.fileName == "Sample upload.webp" }?.id }
            id != null
        }
        compose.scrollToGalleryMedia(requireNotNull(id))
        compose.onNodeWithTag("media-$id").assertIsDisplayed().performClick()
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
        assertNull(graph.accountManager.session.value)
        assertNull(graph.demoCatalog.writeAccountId)
    }

    @Test fun sampleUploadDialogUsesAlbaThemeColors() {
        compose.waitUntil(LONG_WAIT) { compose.onAllNodesWithTag("account-button").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(label(R.string.upload_photos)).performClick()
        val dialog = compose.onNode(isDialog()).captureToImage().toPixelMap()
        val container = dialog[dialog.width / 2, 4]
        val dark = compose.activity.resources.configuration.isNightModeActive
        // Alba's surfaceContainerHigh, not Material's default lavender (0xFFECE6F0 / 0xFF2B2930).
        assertEquals(if (dark) Color(0xFF292D35) else Color(0xFFE6EAF0), container)
    }

    @Test fun pickedPhotoKeepsItsBytesAndBadSharedCopiesAreRejected() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        val file = File(File(compose.activity.cacheDir, "shares").apply { mkdirs() }, "Selected-review-photo.png")
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        try {
            compose.runOnUiThread {
                val selected = androidx.core.content.FileProvider.getUriForFile(compose.activity, "${compose.activity.packageName}.shares", file)
                val missing = androidx.core.content.FileProvider.getUriForFile(compose.activity, "${compose.activity.packageName}.shares", File(file.parentFile, "missing-review.png"))
                androidx.lifecycle.ViewModelProvider(compose.activity)[AlbaViewModel::class.java].transfers.uploadPicked(listOf(selected, missing))
            }
            var id: String? = null
            compose.waitUntil(UI_WAIT) {
                id = runBlocking { graph.demoCatalog.fetchPage(null, 100).records.firstOrNull { it.fileName == file.name }?.id }
                id != null
            }
            compose.scrollToGalleryMedia(requireNotNull(id))
            val selected = runBlocking { requireNotNull(repository.findMedia(requireNotNull(id))) }
            assertEquals(96, selected.width); assertEquals(64, selected.height)
            assertEquals("image/png", selected.mimeType)
            val exported = ByteArrayOutputStream()
            runBlocking { repository.exportOriginal(selected.id, exported) }
            assertArrayEquals(file.readBytes(), exported.toByteArray())
            compose.onNodeWithTag("media-$id").performClick()
            compose.onNodeWithTag("media-pager").assertIsDisplayed()
            val owner = "shared:demo-library:private:owner:two"
            val before = runBlocking { graph.demoCatalog.sharedAlbums().records.size }
            val corrupt = object : OneShotUploadSource {
                override val byteCount = file.length()
                override val sha256Hex = "bad-hash"
                override fun openOnce() = file.inputStream()
            }
            assertTrue(runBlocking { runCatching { graph.demoCatalog.uploadSharedPhoto(owner, file.name, corrupt, "corrupt") }.isFailure })
            assertEquals(before, runBlocking { graph.demoCatalog.sharedAlbums().records.size })
            assertNull(graph.accountManager.session.value)
        } finally { file.delete() }
    }
}
