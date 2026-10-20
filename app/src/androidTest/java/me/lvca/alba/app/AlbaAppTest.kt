package me.lvca.alba.app

import androidx.compose.ui.test.*

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.Screenshots
import me.lvca.alba.app.testing.UI_WAIT
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForGalleryMedia
import me.lvca.alba.app.testing.waitForVideoPlaying
import me.lvca.alba.app.testing.waitUntilDisplayed

@RunWith(AndroidJUnit4::class)
class AlbaAppTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun browseFixtureAndKeepAnOriginalOffline() {
        compose.waitForGalleryMedia()
        compose.onNodeWithTag("tab-LIBRARY").assertIsSelected()

        compose.scrollToGalleryMedia("fixture:icloud:0001")
        compose.onNodeWithTag("media-fixture:icloud:0001").performClick()
        compose.waitUntilDisplayed(hasContentDescription("IMG_8421.JPG"))
        compose.openViewerDetails()
        compose.onAllNodesWithText("IMG_8421.JPG").onFirst().assertIsDisplayed()

        compose.waitUntil(timeoutMillis = UI_WAIT) {
            hasNodeWithText("Keep original offline") || hasNodeWithText("Remove offline copy")
        }
        if (hasNodeWithText("Remove offline copy")) {
            compose.onNodeWithText("Remove offline copy").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = UI_WAIT) {
                hasNodeWithText("Keep original offline")
            }
        }
        compose.onNodeWithText("Keep original offline").performScrollTo().performClick()

        compose.waitUntil(timeoutMillis = UI_WAIT) {
            hasNodeWithText("Remove offline copy")
        }
        compose.onNodeWithText("Remove offline copy").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun demoVideoActuallyPlaysAndCapturesFeatureScreens() {
        compose.waitForGalleryMedia()
        compose.scrollToGalleryMedia("fixture:icloud:0019")
        // scripts/run_android_tests.sh collects the features-* captures.
        Screenshots.capture("features-gallery")
        compose.onNodeWithTag("media-fixture:icloud:0019").performClick()
        compose.waitForVideoPlaying()
        Screenshots.capture("features-video")
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForVideoPlaying()
        compose.openViewerDetails()
        if (hasNodeWithText("Remove offline copy")) {
            compose.onNodeWithText("Remove offline copy").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = UI_WAIT) { hasNodeWithText("Keep original offline") }
        }
        compose.onNodeWithText("Keep original offline").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT) { hasNodeWithText("Remove offline copy") }
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithContentDescription("Back to gallery").performClick()
        compose.waitUntil(timeoutMillis = SHORT_WAIT) {
            compose.onAllNodes(hasTestTag("video-player")).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun searchFavoritesAlbumsAndSelectionAreUsable() {
        compose.waitForGalleryMedia()
        compose.onNodeWithTag("tab-SEARCH").performClick()
        compose.onNodeWithText("Search filenames").performTextInput("Sea breeze")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(timeoutMillis = UI_WAIT) { hasNodeWithText("Sea breeze") }
        compose.scrollToGalleryMedia("fixture:icloud:0019")
        compose.onNodeWithTag("media-fixture:icloud:0019").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel selection").performClick()
        compose.onNodeWithTag("tab-COLLECTIONS").performClick()
        compose.onNodeWithText("1 selected").assertDoesNotExist()
        compose.onNodeWithText("Favorites").performClick()
        compose.scrollToGalleryMedia("fixture:icloud:0019")
        compose.onNodeWithContentDescription("Back to collections").performClick()
        compose.onNodeWithTag("collection-albums").performClick()
        compose.onNodeWithText("Journeys").performClick()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(timeoutMillis = UI_WAIT) { hasNodeWithText("By the sea") }
        compose.onNodeWithText("By the sea").performClick()
        compose.onNodeWithText("By the sea").assertIsDisplayed()
    }

    @Test
    fun opensLocalAppleAccountSignInWithoutStartingNetworkTraffic() {
        compose.waitForGalleryMedia()

        compose.onNodeWithTag("account-button").performClick()
        compose.onNodeWithTag("profile-sign-in").performClick()

        compose.onNodeWithText("Connect to iCloud").assertIsDisplayed()
        compose.onNodeWithText("Sign in to iCloud").assertIsNotEnabled()
        compose.onNodeWithText("Compatibility details").performClick()
        compose.onNodeWithText("Alba supports standard two-factor", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Hide compatibility details").performScrollTo().performClick()
        compose.onNodeWithText("Apple Account").assertIsDisplayed()
        compose.onNodeWithText("Password").assertIsDisplayed()
        compose.onNodeWithText("Sign in to iCloud").assertIsDisplayed()
    }

    @Test
    fun androidBackClearsSelectionAndDetailKeepsGalleryPosition() {
        compose.waitForGalleryMedia()
        val media = "media-fixture:icloud:0019"
        compose.scrollToGalleryMedia("fixture:icloud:0019")
        compose.onNodeWithTag(media).performTouchInput { longClick() }
        compose.onNodeWithTag(media).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag(media).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        compose.onNodeWithTag(media).performClick()
        compose.waitUntil(UI_WAIT) { compose.onAllNodesWithTag("media-pager").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilDisplayed(hasTestTag(media))
    }

    @Test
    fun accountDraftAndDestinationRestoreButPasswordDoesNot() {
        compose.waitForGalleryMedia()
        compose.onNodeWithTag("account-button").performClick()
        compose.onNodeWithTag("profile-sign-in").performClick()
        compose.onNodeWithText("Apple Account").performTextInput("local-restoration@example.invalid")
        compose.onNodeWithText("Password").performTextInput("never-persist-this")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("local-restoration@example.invalid").assertIsDisplayed()
        compose.onNodeWithText("never-persist-this").assertDoesNotExist()
    }

    private fun hasNodeWithText(text: String): Boolean =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
}
