package dev.mela.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.engine.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class PhotoExtensionsTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun loaded() = compose.scrollToGalleryMedia("fixture:icloud:0020")
    private fun galleryText(text: String) = compose.onNodeWithTag("gallery-grid").performScrollToNode(hasText(text))
    private fun open(id: String) {
        compose.scrollToGalleryMedia(id)
        compose.onNodeWithTag("media-$id").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun favoriteEditingKeepsDetailOpenAndAlbumCreateRenameAddWork() {
        loaded()
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithText("Favorites").performClick()
        open("fixture:icloud:0001")
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Unfavorite").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Favorite").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Favorite").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Unfavorite").fetchSemanticsNodes().isNotEmpty() }
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithContentDescription("Back to gallery").performClick()
        compose.onNodeWithContentDescription("Back to collections").performClick()
        compose.onNodeWithTag("collection-albums").performClick()
        compose.onNodeWithText("New album").performClick()
        compose.onNodeWithText("Album name").performTextInput("Extension test")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Album options").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Album options").performClick()
        compose.onNodeWithText("Rename album").performClick()
        compose.onNodeWithText("Album name").performTextReplacement("Extension renamed")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Extension renamed").fetchSemanticsNodes().isNotEmpty() }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("tab-LIBRARY").performClick()
        compose.onNodeWithTag("tab-LIBRARY").assertIsSelected()
        open("fixture:icloud:0001")
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Add to album").performScrollTo().performClick()
        compose.onNodeWithText("Extension renamed").performClick()
        val graph = (compose.activity.application as MelaApplication).graph
        compose.waitUntil(5_000) { runBlocking {
            val album = graph.galleryRepository.observeCollections().first().firstOrNull { it.name == "Extension renamed" }
            album != null && graph.galleryRepository.observeGallery().first().first { it.id == "fixture:icloud:0001" }.collectionIds.contains(album.id)
        } }
    }

    @Test fun smartLiveCollectionPlaybackOfflinePairAndExportsWork() = runBlocking {
        loaded()
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText("Live Photos"))
        compose.onNodeWithText("Live Photos").assertIsDisplayed()
        capture("icloud-media-types.png")
        compose.onNodeWithText("Live Photos").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        open("fixture:icloud:0020")
        compose.onNodeWithText("Play Live Photo").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing")).fetchSemanticsNodes().isNotEmpty()
        }
        capture("live-photo-playback.png")
        compose.onNodeWithText("Show still photo").performClick()
        val repository = (compose.activity.application as MelaApplication).graph.galleryRepository
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
            exporter.saveToGallery(media.copy(fileName = "Mela-extension-fixture.JPG"))
            for ((collection, name, expected) in listOf(
                Triple(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Mela-extension-fixture.JPG", still),
                Triple(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Mela-extension-fixture.mp4", motion))) {
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

    @Test fun storageUsesTopDemoBadgeAndSurvivesScreenRecreation() {
        loaded()
        compose.onNodeWithTag("account-button").performClick()
        compose.onNodeWithText("Demo").assertIsDisplayed()
        compose.onNodeWithText("Example storage · not your account").assertDoesNotExist()
        compose.onNodeWithText("iCloud storage").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check connection and storage").performScrollTo().assertIsDisplayed()
        capture("storage-and-connection.png")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("iCloud storage").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No Apple account connected").performScrollTo().assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: instrumentation.targetContext.cacheDir
        directory.mkdirs()
        java.io.File(directory, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
