package dev.mela.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.engine.model.MediaOrigin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GalleryCompletionTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val repository get() = (compose.activity.application as MelaApplication).graph.galleryRepository
    private fun loaded() = compose.waitUntil(20_000) { compose.onAllNodesWithTag("media-fixture:icloud:0020").fetchSemanticsNodes().isNotEmpty() }
    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350) // Let native dialog windows reach the compositor before capture.
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val directory = compose.activity.getExternalFilesDir("table-stakes-finish")!!.apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun image(): Uri {
        val resolver = compose.activity.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "mela-completion-${System.nanoTime()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg"); put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MelaTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
    @Test fun foregroundMediaStoreChangesAppearWithoutLeavingTheApp() {
        loaded()
        grantTestMediaPermissions(compose.activity)
        // Resume updates permission state and starts the lifecycle-bound observer.
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForIdle()
        val uri = image()
        try {
            compose.waitUntil(15_000) { runBlocking { repository.observeGallery().first().any { it.originalReference == uri.toString() } } }
            compose.activity.contentResolver.delete(uri, null, null)
            compose.waitUntil(15_000) { runBlocking { repository.observeGallery().first().none { it.originalReference == uri.toString() } } }
        } finally { runCatching { compose.activity.contentResolver.delete(uri, null, null) } }
    }
    @Test fun bulkFavoritesAlbumRemovalAndCloudTrashRestoreWorkInDemo() {
        loaded()
        val ids = listOf("fixture:icloud:0016", "fixture:icloud:0017")
        val previous = runBlocking { repository.observeGallery().first().filter { it.id in ids }.associate { it.id to it.isFavorite } }
        val album = runBlocking { repository.createAlbum("Completion test").also { repository.addToAlbum(it.id, ids) } }
        try {
            compose.runOnUiThread { ViewModelProvider(compose.activity)[MelaViewModel::class.java].apply { selectItems(ids.toSet(), true); setFavorites(ids, true) } }
            compose.waitUntil(10_000) { runBlocking { repository.observeGallery().first().filter { it.id in ids }.all { it.isFavorite } } }
            runBlocking { repository.removeFromAlbum(album.id, ids); repository.setCloudTrashed(ids, true); repository.refresh(false) }
            var items = runBlocking { repository.observeGallery().first().filter { it.id in ids } }
            assertEquals(2, items.size); assertTrue(items.all { it.isTrashed && album.id !in it.collectionIds && it.origin == MediaOrigin.ICLOUD })
            runBlocking { repository.setCloudTrashed(ids, false); repository.refresh(false) }
            items = runBlocking { repository.observeGallery().first().filter { it.id in ids } }
            assertEquals(2, items.size); assertTrue(items.none { it.isTrashed })
        } finally { runBlocking { repository.setCloudTrashed(ids, false); previous.forEach { (id, value) -> repository.setFavorite(id, value) }; repository.deleteAlbum(album.id) } }
    }
    @Test fun cloudTrashRequiresConfirmationAndCanBeRestoredFromItsCollection() {
        loaded()
        val id = "fixture:icloud:0017"
        try {
            compose.onNodeWithTag("gallery-grid").performScrollToKey(id)
            compose.onNodeWithTag("media-$id").performClick()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.photo_details_and_actions)).performClick()
            val trash = compose.activity.getString(R.string.trash_in_icloud)
            compose.onNodeWithText(trash).performScrollTo().performClick()
            compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.cloud_trash_confirmation, 1, 1)).assertIsDisplayed()
            capture("icloud-trash-confirmation")
            compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
            assertFalse(runBlocking { repository.observeGallery().first().first { it.id == id }.isTrashed })
            compose.onNodeWithText(trash).performScrollTo().performClick()
            compose.onAllNodesWithText(trash).onLast().performClick()
            compose.waitUntil(10_000) { runBlocking { repository.observeGallery().first().first { it.id == id }.isTrashed } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isEmpty() }
            capture("after-cloud-trash")
            compose.runOnIdle {
                val lightTheme = compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
                assertEquals("Gallery must restore system-bar contrast after the viewer exits", lightTheme,
                    androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).isAppearanceLightStatusBars)
            }
            val snackbar = compose.onNodeWithTag("status-snackbar").fetchSemanticsNode().boundsInRoot
            val tab = compose.onNodeWithTag("tab-COLLECTIONS").fetchSemanticsNode().boundsInRoot
            assertTrue("Status messages must not cover floating navigation", snackbar.bottom <= tab.top)
            compose.onNodeWithTag("tab-COLLECTIONS").performClick()
            try { compose.waitUntil(10_000) { compose.onAllNodesWithTag("collections-grid").fetchSemanticsNodes().isNotEmpty() } }
            catch (failure: Throwable) { capture("collections-navigation-failure"); throw failure }
            // Snackbar timeout runs on the injected clock, not waitUntil's wall clock.
            compose.mainClock.advanceTimeBy(5_000)
            compose.onNodeWithTag("collections-grid").performScrollToNode(hasText(compose.activity.getString(R.string.icloud_recently_deleted)))
            // Lazy-grid scroll-to only accounts for its viewport. Scroll the whole row
            // above the floating controls, as a user does, before injecting a touch.
            val row = compose.onNodeWithText(compose.activity.getString(R.string.icloud_recently_deleted)).fetchSemanticsNode().boundsInRoot
            val bar = compose.onNodeWithTag("gallery-destination-capsule").fetchSemanticsNode().boundsInRoot
            val extra = (row.bottom - bar.top + 24f).coerceAtLeast(0f)
            if (extra > 0f) compose.onNodeWithTag("collections-grid").performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(width * .5f, height * .65f),
                    androidx.compose.ui.geometry.Offset(width * .5f, height * .65f - extra), 350)
            }
            compose.onNodeWithText(compose.activity.getString(R.string.icloud_recently_deleted)).performClick()
            try { compose.waitUntil(10_000) { compose.onNodeWithTag("media-$id").isDisplayed() } }
            catch (failure: Throwable) {
                capture("recently-deleted-failure")
                java.io.File(compose.activity.getExternalFilesDir("table-stakes-finish"), "recently-deleted-tree.txt").writeText(compose.onRoot().printToString())
                throw failure
            }
            compose.onNodeWithTag("media-$id").assertIsDisplayed()
            capture("icloud-recently-deleted")
            compose.onNodeWithTag("media-$id").performClick()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.photo_details_and_actions)).performClick()
            val restore = compose.activity.getString(R.string.restore_from_icloud)
            compose.onNodeWithText(restore).performScrollTo().performClick()
            compose.onAllNodesWithText(restore).onLast().performClick()
            compose.waitUntil(10_000) { runBlocking { repository.observeGallery().first().first { it.id == id }.isTrashed.not() } }
        } finally { runBlocking { repository.setCloudTrashed(listOf(id), false) } }
    }
}
