package me.lvca.alba.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.openGalleryMedia
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForGalleryMedia

class GalleryImprovementsTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }

    @Test fun viewerSwipesZoomsHidesControlsAndRestoresSelectedPhoto() {
        compose.waitForGalleryMedia()
        compose.openGalleryMedia("fixture:icloud:0016")
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
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithContentDescription("Share").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Share").assertDoesNotExist()
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").performTouchInput { advanceEventTime(500); click() }
        compose.mainClock.advanceTimeBy(600)
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithContentDescription("Share").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Share").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("viewer-image-fixture:icloud:0017").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to gallery").performClick()
        compose.openGalleryMedia("fixture:icloud:0001")
        compose.onNodeWithTag("viewer-image-fixture:icloud:0001").assertIsDisplayed()
    }

    @Test fun swipingAwayFromVideoReleasesPlayback() {
        compose.waitForGalleryMedia()
        compose.openGalleryMedia("fixture:icloud:0019")
        compose.onNodeWithTag("video-player").assertIsDisplayed()
        compose.onNodeWithTag("media-pager").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width * 0.8f, height * 0.5f), androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.5f), 350)
        }
        compose.onNodeWithTag("viewer-image-fixture:icloud:0001").assertIsDisplayed()
        compose.onNodeWithTag("video-player").assertDoesNotExist()
    }

    @Test fun dateAndDensityRestoreAndMonthJumpReachesPhotos() {
        compose.waitForGalleryMedia()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.filter_and_sort)).performClick()
        compose.onNodeWithText("Added", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Large").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Added").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Large").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Show photos").performClick()
        compose.scrollToGalleryMedia("fixture:icloud:0016")
        compose.onNodeWithTag("floating-date-button").performClick()
        // Demo spans months; selecting the earliest jumps to that month's grid header.
        val repository = (compose.activity.application as AlbaApplication).graph.galleryRepository
        val oldest = runBlocking { repository.observeGallery().first()
            .filterNot { it.id.startsWith("shared:") }
            .minBy { it.addedAtEpochMillis ?: it.capturedAtEpochMillis } }
        val label = java.time.Instant.ofEpochMilli(oldest.addedAtEpochMillis ?: oldest.capturedAtEpochMillis).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"))
        compose.onNodeWithTag("date-months").performScrollToNode(hasText(label))
        compose.onAllNodesWithText(label).onLast().performClick()
        compose.onNodeWithTag("media-${oldest.id}").assertExists()
    }

    @Test fun albumDeleteRequiresConfirmationAndKeepsLibraryPhotos() {
        compose.waitForGalleryMedia()
        val repository = (compose.activity.application as AlbaApplication).graph.galleryRepository
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
        compose.onNodeWithText("Delete album", useUnmergedTree = true).performClick()
        compose.waitUntil(SHORT_WAIT) { runBlocking { repository.observeCollections().first().none { it.id == album.id } } }
        assertEquals(originalCount, runBlocking { repository.observeGallery().first().size })
        assertTrue(runBlocking { repository.observeGallery().first().any { it.id == "fixture:icloud:0001" } })
    }
}
