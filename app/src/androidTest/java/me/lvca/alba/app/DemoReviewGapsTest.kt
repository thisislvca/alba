package me.lvca.alba.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import me.lvca.alba.engine.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DemoReviewGapsTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as AlbaApplication).graph
    private val repository get() = graph.galleryRepository
    private fun label(id: Int) = compose.activity.getString(id)
    private fun click(id: Int) = compose.onNodeWithText(label(id)).performScrollTo().performClick()
    private fun confirm() = compose.onNodeWithText(label(R.string.shared_confirm)).performClick()
    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(500) // Let Android window animations settle; Compose idle does not cover them.
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val dir = compose.activity.getExternalFilesDir("demo-review")!!.apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun sharedAlbums() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-shared").performClick()
    }

    @Test fun manualSampleUploadUsesPublicEntryPointAndAppearsInTheLibrary() {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("account-button").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(label(R.string.upload_photos)).performClick()
        compose.onNodeWithText(label(R.string.demo_upload_disclosure)).assertIsDisplayed()
        capture("demo-manual-upload-disclosure")
        compose.onNodeWithText(label(R.string.demo_upload_sample)).performClick()
        var id: String? = null
        compose.waitUntil(15_000) {
            id = runBlocking { graph.demoCatalog.fetchPage(null, 100).records.firstOrNull { it.fileName == "Sample upload.webp" }?.id }
            id != null
        }
        compose.scrollToGalleryMedia(requireNotNull(id))
        compose.onNodeWithTag("media-$id").assertIsDisplayed().performClick()
        compose.onNodeWithTag("media-pager").assertIsDisplayed()
        capture("demo-manual-upload-viewer")
        assertNull(graph.accountManager.session.value)
        assertNull(graph.demoCatalog.writeAccountId)
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
                androidx.lifecycle.ViewModelProvider(compose.activity)[AlbaViewModel::class.java].uploadSelected(listOf(selected, missing))
            }
            var id: String? = null
            compose.waitUntil(15_000) {
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
            capture("demo-selected-upload-exact-photo")
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

    @Test fun invitationAcceptAndRequestApprovalWorkThroughTheirSheets() {
        sharedAlbums()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasContentDescription(label(R.string.shared_invitations)))
        compose.onNodeWithContentDescription(label(R.string.shared_invitations)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Sample weekend together").fetchSemanticsNodes().isNotEmpty() }
        capture("demo-incoming-invitations")
        compose.onAllNodesWithText(label(R.string.shared_accept))[0].performScrollTo().performClick()
        confirm()
        compose.waitUntil(15_000) { runBlocking { repository.pendingSharedInvitations().size == 1 } }
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText("Sample weekend together"))
        compose.onNodeWithText("Sample weekend together").assertIsDisplayed()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText("Family moments"))
        compose.onNodeWithText("Family moments").performClick()
        compose.onNodeWithContentDescription(label(R.string.shared_manage)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Alex (sample)").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(label(R.string.shared_approve))[0].performScrollTo().performClick()
        confirm()
        compose.waitUntil(15_000) { runBlocking { repository.sharedManagement("shared:demo-library:private:owner:two").participants.any { it.id == "sample-alex" } } }
        compose.onNodeWithText("Alex (sample)").performScrollTo().assertIsDisplayed()
        capture("demo-approved-participant")
    }

    @Test fun selectedSharedBytesAndLegacySaveAreRealCopiesAndCoverPersists() = runBlocking {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        val album = repository.createSharedAlbum("Demo selected bytes", "demo-bytes", SharedAlbumGeneration.LEGACY)
        // Exercise the real repository staging/validation path, including the selected video bytes.
        repository.contributeToSharedAlbum(album.id, listOf("fixture:icloud:0019", "fixture:icloud:0001"), "selected")
        repository.refresh(false)
        val snapshot = graph.demoCatalog.sharedAlbums()
        val selected = snapshot.records.filter { it.id.startsWith(album.id + ":") }
        assertEquals(2, selected.size)
        val video = selected.single { it.kind == MediaKind.VIDEO }
        val photo = selected.single { it.kind == MediaKind.PHOTO }
        assertEquals("Sea breeze.mp4", video.fileName)
        val original = ByteArrayOutputStream().also { repository.exportOriginal(video.id, it) }.toByteArray()
        assertArrayEquals(compose.activity.assets.open("fixture_video.mp4").use { it.readBytes() }, original)
        repository.openPlayback(video.id, 8, 32).use { assertArrayEquals(original.copyOfRange(8, 40), it.input.readBytes()) }
        val before = graph.demoCatalog.fetchPage(null, 100).records.map { it.id }.toSet()
        repository.changeSharedAlbum(album.id, SharedCommand(SharedAction.SAVE_TO_LIBRARY, photo.id), "save-legacy")
        repository.refresh(false)
        val saved = graph.demoCatalog.fetchPage(null, 100).records.single { it.id !in before }
        val sourceBytes = ByteArrayOutputStream().also { repository.exportOriginal(photo.id, it) }.toByteArray()
        assertArrayEquals(sourceBytes, ByteArrayOutputStream().also { repository.exportOriginal(saved.id, it) }.toByteArray())
        val owner = "shared:demo-library:private:owner:two"
        val cover = "$owner:photo-18"
        repository.changeSharedAlbum(owner, SharedCommand(SharedAction.COVER, cover), "cover-device")
        repository.refresh(false)
        assertEquals(cover, repository.sharedManagement(owner).album.coverMediaId)
        sharedAlbums()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText("Family moments"))
        capture("demo-selected-album-cover")
        compose.onNodeWithText("Family moments").performClick()
        compose.onNodeWithTag("album-hero").assertIsDisplayed()
        capture("demo-selected-cover-hero")
        assertNull(graph.accountManager.session.value)
        assertNull(graph.demoCatalog.writeAccountId)
    }
}
