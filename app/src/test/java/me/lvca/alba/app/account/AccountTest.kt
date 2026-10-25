package me.lvca.alba.app.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.waitForGalleryMedia
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose

    private fun openSignIn() {
        compose.waitForGalleryMedia()
        compose.onNodeWithTag("account-button").performClick()
        compose.onNodeWithTag("profile-sign-in").performClick()
    }

    @Test fun signInStartsDisabledAndExplainsCompatibility() {
        openSignIn()
        compose.onNodeWithText(app.string(R.string.sign_in_heading)).assertIsDisplayed()
        compose.onNodeWithText(app.string(R.string.sign_in_to_icloud)).assertIsNotEnabled()
        compose.onNodeWithText(app.string(R.string.sign_in_compatibility)).performScrollTo().performClick()
        compose.onNodeWithText(app.string(R.string.supported_in_v1_normal_two_factor_authentication_and_the_personal_library_security_ke))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.string(R.string.sign_in_compatibility_hide)).performScrollTo().performClick()
        compose.onNodeWithText(app.string(R.string.apple_account)).assertIsDisplayed()
        compose.onNodeWithText(app.string(R.string.password)).assertIsDisplayed()
        compose.onNodeWithText(app.string(R.string.sign_in_to_icloud)).assertIsDisplayed()
    }

    @Test fun accountDraftAndDestinationRestoreButPasswordDoesNot() {
        openSignIn()
        compose.onNodeWithText(app.string(R.string.apple_account)).performTextInput("local-restoration@example.invalid")
        compose.onNodeWithText(app.string(R.string.password)).performTextInput("never-persist-this")
        app.recreate()
        compose.onNodeWithText("local-restoration@example.invalid").assertIsDisplayed()
        compose.onNodeWithText("never-persist-this").assertDoesNotExist()
    }

    @Test fun demoProfileShowsTheDemoBadgeAndStorageAcrossRecreation() {
        compose.waitForGalleryMedia()
        compose.onNodeWithTag("account-button").performClick()
        compose.onNodeWithText(app.string(R.string.demo_badge)).assertIsDisplayed()
        compose.onNodeWithText(app.string(R.string.profile_example_allowance)).assertDoesNotExist()
        compose.onNodeWithText(app.string(R.string.icloud_storage)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.string(R.string.check_connection_and_storage)).performScrollTo().assertIsDisplayed()
        app.recreate()
        compose.onNodeWithText(app.string(R.string.icloud_storage)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.string(R.string.no_apple_account_connected)).performScrollTo().assertIsDisplayed()
    }
}
