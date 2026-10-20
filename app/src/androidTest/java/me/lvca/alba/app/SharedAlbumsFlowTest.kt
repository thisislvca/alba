package me.lvca.alba.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import me.lvca.alba.engine.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForVideoPlaying

/** Full native navigation and repository wiring, using the explicitly labelled demo account. */
class SharedAlbumsFlowTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
    private val repository get() = (compose.activity.application as AlbaApplication).graph.galleryRepository
    private fun label(id: Int) = compose.activity.getString(id)
    @Test fun activityOpensFromAlbumAndRestoresAfterRecreation() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-shared").performClick()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText("Family moments"))
        compose.onNodeWithText("Family moments").performClick()
        compose.onNodeWithContentDescription(label(R.string.shared_activity)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.filter_and_sort)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.shared_activity)).performClick()
        compose.waitUntil(UI_WAIT) { compose.onAllNodes(hasText(label(R.string.shared_post_discussion)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(UI_WAIT) { compose.onAllNodes(hasText(label(R.string.shared_post_discussion)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.shared_post_discussion)).performScrollTo()
        compose.waitForIdle()
        // Invoke the restored button directly; sheet/scroll motion can move a touch target.
        compose.onNodeWithText(label(R.string.shared_post_discussion)).assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("shared-comment-input").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun sharedVideoStreamsAndReopensFromOfflineStorage() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-shared").performClick()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText("Family moments"))
        compose.onNodeWithText("Family moments").performClick()
        val id = "shared:demo-library:private:owner:two:video"
        compose.onNodeWithTag("media-$id").performClick()
        compose.waitForVideoPlaying()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        runBlocking { repository.keepOriginalOffline(id) }
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithTag("media-$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media-$id").performClick()
        compose.waitForVideoPlaying()
        assertEquals(MediaAvailability.ORIGINAL_CACHED, runBlocking { repository.observeGallery().first().first { it.id == id }.availability })
    }

    @Test fun createsBothGenerationsContributesAndRestoresSharedDestination() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-shared").performClick()
        for (generation in listOf(SharedAlbumGeneration.MODERN, SharedAlbumGeneration.LEGACY)) {
            compose.onNodeWithTag("collections-grid").performScrollToNode(hasTestTag("create-shared-album"))
            compose.onNodeWithTag("create-shared-album").performClick()
            val name = "Android ${generation.name} test"
            compose.onNodeWithTag("shared-album-name").performTextInput(name)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("shared-generation-$generation").performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.save)).performClick()
            compose.waitUntil(UI_WAIT) { compose.onAllNodesWithContentDescription(label(R.string.shared_add_photos)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.onNodeWithContentDescription(label(R.string.shared_add_photos)).performClick()
            compose.onNodeWithTag("shared-select-fixture:icloud:${if (generation == SharedAlbumGeneration.LEGACY) "0019" else "0020"}").performClick()
            compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.shared_add_selected, 1, 1)).performClick()
            var contributedId: String? = null
            compose.waitUntil(UI_WAIT) {
                runBlocking {
                    val album = repository.observeCollections().first().firstOrNull { it.name == name }
                    contributedId = album?.let { found -> repository.observeGallery().first().firstOrNull { found.id in it.collectionIds }?.id }
                    contributedId != null
                }
            }
            // A catalog commit can precede the new item reaching the Compose grid.
            compose.scrollToGalleryMedia(requireNotNull(contributedId))
            compose.onNodeWithTag("media-$contributedId").assertIsDisplayed().performClick()
            compose.onNodeWithTag("media-pager").assertIsDisplayed()
            compose.openViewerDetails()
            compose.onNodeWithTag("photo-details-panel").assertIsDisplayed()
            compose.onNodeWithText(label(R.string.shared_copy_notice), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithContentDescription(label(R.string.back_to_gallery)).performClick()
            compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithTag("shared-version-badge").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("shared-version-badge").performClick()
            compose.onNodeWithText(label(R.string.shared_role_owner)).assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(label(R.string.shared_role_owner)).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.shared_understood)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(label(R.string.back_to_collections)).performClick()
        }
        val personal = runBlocking { GalleryQuery().apply(repository.observeGallery().first()) }
        assertFalse(personal.any { it.isShared })
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText("Family archive"))
        compose.onNodeWithText("Family archive").performClick()
        compose.onNodeWithContentDescription(label(R.string.shared_add_photos)).assertDoesNotExist()
    }
}
