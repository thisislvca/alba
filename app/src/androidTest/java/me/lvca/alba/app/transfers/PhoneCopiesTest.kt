package me.lvca.alba.app.transfers

import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.platform.GalleryExporter
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.LONG_WAIT
import me.lvca.alba.app.testing.MediaStoreFixtures
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.openGalleryMedia
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForGalleryMedia
import me.lvca.alba.engine.model.*
import me.lvca.alba.engine.model.BatchItemState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Copies Alba publishes to the phone gallery: saved selections and Live Photo pairs. */
class PhoneCopiesTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private fun phoneCopies(): Set<android.net.Uri> =
        MediaStoreFixtures.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", "Pictures/Alba/")

    @Test fun selectedCloudPhotosAreSavedAsPublicPhoneCopies() {
        compose.waitForGalleryMedia()
        val before = phoneCopies()
        val graph = (compose.activity.application as AlbaApplication).graph
        val previousBatches = runBlocking { graph.batches.observe().first().map { it.batchId }.toSet() }
        try {
            compose.scrollToGalleryMedia("fixture:icloud:0016")
            compose.onNodeWithTag("media-fixture:icloud:0016").performTouchInput { longClick() }
            compose.scrollToGalleryMedia("fixture:icloud:0017")
            compose.onNodeWithTag("media-fixture:icloud:0017").performClick()
            compose.onNodeWithTag("save-selection-to-phone").assertIsDisplayed().performClick()
            compose.waitUntil(LONG_WAIT) { runBlocking { graph.batches.observe().first().count {
                it.batchId !in previousBatches && it.action == "SAVE_TO_PHONE" && it.status == BatchItemState.DONE
            } == 2 } }
            val saved = phoneCopies() - before
            assertEquals(2, saved.size)
            saved.forEach { uri -> assertTrue(compose.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes().isNotEmpty() }) }
        } finally { (phoneCopies() - before).forEach { compose.activity.contentResolver.delete(it, null, null) } }
    }

    @Test fun smartLiveCollectionPlaybackOfflinePairAndExportsWork() = runBlocking {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText("Live Photos"))
        compose.onNodeWithText("Live Photos").assertIsDisplayed()
        compose.onNodeWithText("Live Photos").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.openGalleryMedia("fixture:icloud:0020")
        compose.onNodeWithText("Play Live Photo").performClick()
        compose.waitUntil(UI_WAIT) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Show still photo").performClick()
        val repository = (compose.activity.application as AlbaApplication).graph.galleryRepository
        repository.keepOriginalOffline("fixture:icloud:0020")
        val media = repository.observeGallery().first().first { it.id == "fixture:icloud:0020" }
        assertNotNull(media.originalReference)
        assertNotNull(media.motionReference)
        val still = java.io.File(media.originalReference!!).readBytes()
        val motion = java.io.File(media.motionReference!!).readBytes()
        val exporter = GalleryExporter(compose.activity, repository)
        val bytes = ByteArrayOutputStream()
        exporter.writeLivePhotoArchive(media, bytes)
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            assertEquals("Live sea breeze.JPG", zip.nextEntry.name)
            assertArrayEquals(still, zip.readBytes())
            assertEquals("Live sea breeze.mp4", zip.nextEntry.name)
            assertArrayEquals(motion, zip.readBytes())
            assertNull(zip.nextEntry)
        }
        // Publish only disposable fixture media, then remove the exact app-owned copies.
        val resolver = compose.activity.contentResolver
        val inserted = mutableListOf<android.net.Uri>()
        try {
            exporter.saveToGallery(media.copy(fileName = "Alba-extension-fixture.JPG"))
            for ((collection, name, expected) in listOf(
                Triple(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Alba-extension-fixture.JPG", still),
                Triple(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Alba-extension-fixture.mp4", motion))) {
                resolver.query(collection, arrayOf("_id", "is_pending"), "_display_name = ?", arrayOf(name), null)!!.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
                    inserted += uri
                    assertEquals(0, cursor.getInt(1))
                    assertArrayEquals(expected, resolver.openInputStream(uri)!!.use { it.readBytes() })
                }
            }
        } finally { inserted.forEach { resolver.delete(it, null, null) } }
    }
}
