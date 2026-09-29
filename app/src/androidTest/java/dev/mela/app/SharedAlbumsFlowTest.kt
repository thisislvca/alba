package dev.mela.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.engine.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Full native navigation and repository wiring, using the explicitly labelled demo account. */
class SharedAlbumsFlowTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val repository get() = (compose.activity.application as MelaApplication).graph.galleryRepository
    private fun label(id: Int) = compose.activity.getString(id)
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val dir = compose.activity.getExternalFilesDir("shared-albums")!!.apply { mkdirs() }
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun activityOpensFromAlbumAndRestoresAfterRecreation() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-shared").performClick()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText("Family moments"))
        compose.onNodeWithText("Family moments").performClick()
        compose.onNodeWithContentDescription(label(R.string.shared_activity)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.filter_and_sort)).assertIsDisplayed()
        capture("modern-album-toolbar")
        compose.onNodeWithContentDescription(label(R.string.shared_activity)).performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasText(label(R.string.shared_post_discussion)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000) { compose.onAllNodes(hasText(label(R.string.shared_post_discussion)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        capture("native-album-activity")
        compose.onNodeWithText(label(R.string.shared_post_discussion)).performScrollTo()
        compose.waitForIdle()
        // Invoke the restored button directly; sheet/scroll motion can move a touch target.
        compose.onNodeWithText(label(R.string.shared_post_discussion)).assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        try { compose.waitUntil(15000) { compose.onAllNodesWithTag("shared-comment-input").fetchSemanticsNodes().isNotEmpty() } }
        catch (failure: Throwable) { capture("post-discussion-failure"); throw failure }
    }
    @Test fun sharedVideoStreamsAndReopensFromOfflineStorage() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithTag("collection-shared").performClick()
        compose.onNodeWithTag("collections-grid").performScrollToNode(hasText("Family moments"))
        compose.onNodeWithText("Family moments").performClick()
        val id = "shared:demo-library:private:owner:two:video"
        compose.onNodeWithTag("media-$id").performClick()
        fun ready() = compose.waitUntil(15000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, label(R.string.ready))).fetchSemanticsNodes().isNotEmpty()
        }
        ready()
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(androidx.media3.ui.R.id.exo_play_pause)).perform(androidx.test.espresso.action.ViewActions.click())
        compose.waitUntil(5000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, label(R.string.playing))).fetchSemanticsNodes().isNotEmpty()
        }
        capture("shared-video-playing")
        compose.onNodeWithTag("floating-viewer-back").performClick()
        runBlocking { repository.keepOriginalOffline(id) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("media-$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media-$id").performClick()
        ready()
        assertEquals(MediaAvailability.ORIGINAL_CACHED, runBlocking { repository.observeGallery().first().first { it.id == id }.availability })
        capture("shared-video-offline")
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
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription(label(R.string.shared_add_photos)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.onNodeWithContentDescription(label(R.string.shared_add_photos)).performClick()
            compose.onNodeWithTag("shared-select-fixture:icloud:${if (generation == SharedAlbumGeneration.LEGACY) "0019" else "0020"}").performClick()
            compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.shared_add_selected, 1, 1)).performClick()
            var contributedId: String? = null
            compose.waitUntil(15000) {
                runBlocking {
                    val album = repository.observeCollections().first().firstOrNull { it.name == name }
                    contributedId = album?.let { found -> repository.observeGallery().first().firstOrNull { found.id in it.collectionIds }?.id }
                    contributedId != null
                }
            }
            // A catalog commit can precede the new item reaching the Compose grid.
            compose.scrollToGalleryMedia(requireNotNull(contributedId))
            compose.onNodeWithTag("media-$contributedId").assertIsDisplayed().performClick()
            compose.onNodeWithText(label(R.string.details)).assertIsDisplayed()
            compose.onNodeWithContentDescription(label(R.string.photo_details_and_actions)).performClick()
            compose.onNodeWithTag("photo-details-panel").assertIsDisplayed()
            capture("details-open-${generation.name.lowercase()}")
            compose.onNodeWithText(label(R.string.shared_copy_notice), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            capture("viewer-${generation.name.lowercase()}")
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithContentDescription(label(R.string.back_to_gallery)).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("shared-version-badge").fetchSemanticsNodes().size == 1 }
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
        capture("legacy-viewer-permission")
    }
}
