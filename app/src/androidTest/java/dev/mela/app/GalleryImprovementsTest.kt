package dev.mela.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GalleryImprovementsTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun loaded() = compose.waitUntil(15_000) { compose.onAllNodesWithTag("media-fixture:icloud:0020").fetchSemanticsNodes().isNotEmpty() }
    private fun open(id: String) {
        compose.onNodeWithTag("gallery-grid").performScrollToKey("$id")
        compose.onNodeWithTag("media-$id").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = instrumentation.targetContext.getExternalFilesDir("gallery-improvements")!!.apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun viewerSwipesZoomsHidesControlsAndRestoresSelectedPhoto() {
        loaded()
        open("fixture:icloud:0016")
        compose.onNodeWithTag("media-pager").performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width * 0.8f, height * 0.5f), androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.5f), 350) }
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").assertIsDisplayed()
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").performTouchInput { doubleClick() }
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoomed"))
        compose.onNodeWithTag("media-pager").performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width * 0.8f, height * 0.5f), androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.5f), 350) }
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").assertIsDisplayed()
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").performTouchInput { doubleClick() }
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Fit"))
        // These are independent single taps, not another double tap on the injected event clock.
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").performTouchInput { advanceEventTime(500); click() }
        // detectTapGestures defers single taps while waiting for a possible second tap.
        // waitUntil uses wall time; advance the gesture coroutine's Compose clock too.
        compose.mainClock.advanceTimeBy(600)
        compose.waitUntil(2_000) { compose.onAllNodesWithContentDescription("Share").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Share").assertDoesNotExist()
        capture("viewer-hidden")
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").performTouchInput { advanceEventTime(500); click() }
        compose.mainClock.advanceTimeBy(600)
        compose.waitUntil(2_000) { compose.onAllNodesWithContentDescription("Share").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Share").assertIsDisplayed()
        capture("viewer")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to gallery").performClick()
        open("fixture:icloud:0001")
        compose.onNodeWithTag("viewer-image-fixture:icloud:0001").assertIsDisplayed()
    }

    @Test fun swipingAwayFromVideoReleasesPlayback() {
        loaded()
        open("fixture:icloud:0019")
        compose.onNodeWithTag("video-player").assertIsDisplayed()
        compose.onNodeWithTag("media-pager").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width * 0.8f, height * 0.5f), androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.5f), 350)
        }
        compose.onNodeWithTag("viewer-image-fixture:icloud:0001").assertIsDisplayed()
        compose.onNodeWithTag("video-player").assertDoesNotExist()
    }

    @Test fun dateAndDensityRestoreAndMonthJumpReachesPhotos() {
        loaded()
        compose.onNodeWithTag("filter-button").performClick()
        compose.onNodeWithText("Added", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Large").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Added").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Large").performScrollTo().assertIsSelected()
        capture("filters")
        compose.onNodeWithText("Show photos").performClick()
        compose.onNodeWithContentDescription("Browse by date").performClick()
        capture("dates")
        // Demo spans months; selecting the earliest jumps to that month's grid header.
        val repository = (compose.activity.application as MelaApplication).graph.galleryRepository
        val oldest = runBlocking { repository.observeGallery().first().minBy { it.capturedAtEpochMillis } }
        val label = java.time.Instant.ofEpochMilli(oldest.capturedAtEpochMillis).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"))
        compose.onNodeWithTag("date-months").performScrollToNode(hasText(label))
        compose.onAllNodesWithText(label).onLast().performClick()
        compose.onNodeWithTag("media-${oldest.id}").assertExists()
        capture("date-jump")
    }

    @Test fun albumDeleteRequiresConfirmationAndKeepsLibraryPhotos() {
        loaded()
        val repository = (compose.activity.application as MelaApplication).graph.galleryRepository
        val originalCount = runBlocking { repository.observeGallery().first().size }
        val album = runBlocking {
            repository.createAlbum("Delete test").also { repository.addToAlbum(it.id, listOf("fixture:icloud:0001")) }
        }
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-albums").performClick()
        compose.onNodeWithText("Delete test").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Album options").performClick()
        compose.onNodeWithText("Delete album").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(runBlocking { repository.observeCollections().first().any { it.id == album.id } })
        compose.onNodeWithContentDescription("Album options").performClick()
        compose.onNodeWithText("Delete album").performClick()
        capture("delete-album")
        compose.onNodeWithText("Delete album", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { runBlocking { repository.observeCollections().first().none { it.id == album.id } } }
        assertEquals(originalCount, runBlocking { repository.observeGallery().first().size })
        assertTrue(runBlocking { repository.observeGallery().first().any { it.id == "fixture:icloud:0001" } })
    }
}
