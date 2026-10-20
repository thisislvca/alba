package me.lvca.alba.app.account

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.testing.waitForGalleryMedia
import me.lvca.alba.engine.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AccountTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }

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

    @Test fun storageUsesTopDemoBadgeAndSurvivesScreenRecreation() {
        compose.scrollToGalleryMedia("fixture:icloud:0020")
        compose.onNodeWithTag("account-button").performClick()
        compose.onNodeWithText("Demo").assertIsDisplayed()
        compose.onNodeWithText("Example storage · not your account").assertDoesNotExist()
        compose.onNodeWithText("iCloud storage").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check connection and storage").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("iCloud storage").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No Apple account connected").performScrollTo().assertIsDisplayed()
    }
}
