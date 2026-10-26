package me.lvca.alba.app.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.lvca.alba.app.DiagnosticsConsent
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.testAlbaActions
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.account.ICloudAccountScreen
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.TwoFactorDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The sign-in and code forms, and sign-out confirmations, from fixed account states. */
@RunWith(AndroidJUnit4::class)
class SignInFormTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(id: Int) = context.getString(id)
    private val opened = mutableListOf<String>()
    private var signOuts = 0
    private var smsRequests = 0

    @Test fun wrongPasswordShowsUnderTheFieldWithHelp() {
        render(SessionState(accountState = ICloudAccountState.SignedOut, signInProblem = SignInProblem.WRONG_PASSWORD))
        compose.onNodeWithTag("sign-in-unsupported").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.invalid_credentials)).performScrollTo().assertIsDisplayed()
        val password = compose.onNodeWithTag("sign-in-password").fetchSemanticsNode()
        assertTrue(SemanticsProperties.Error in password.config)
        compose.onNodeWithTag("sign-in-forgot-password").performScrollTo().performClick()
        assertEquals(listOf("https://iforgot.apple.com"), opened)
    }

    @Test fun passwordCanBeShownAndHidden() {
        render(SessionState(accountState = ICloudAccountState.SignedOut))
        compose.onNodeWithContentDescription(string(R.string.sign_in_show_password)).performClick()
        compose.onNodeWithContentDescription(string(R.string.sign_in_hide_password)).assertIsDisplayed()
    }

    @Test fun securityKeyAccountsGetAnExplanationAndApplesHelp() {
        render(SessionState(accountState = ICloudAccountState.SignedOut, signInProblem = SignInProblem.SECURITY_KEY))
        compose.onNodeWithText(string(R.string.sign_in_error_security_key)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sign_in_help_security_keys)).performScrollTo().performClick()
        assertEquals(listOf("https://support.apple.com/102637"), opened)
    }

    @Test fun wrongCodeShowsUnderTheCodeAndTextMessageIsOffered() {
        render(SessionState(
            accountState = ICloudAccountState.AwaitingTwoFactor("alex@example.com", TwoFactorDelivery.TRUSTED_DEVICE, smsAvailable = true),
            signInProblem = SignInProblem.WRONG_CODE,
        ))
        compose.onNodeWithText(string(R.string.invalid_two_factor_code)).assertIsDisplayed()
        compose.onNodeWithTag("code-by-sms").performScrollTo().performClick()
        assertEquals(1, smsRequests)
    }

    @Test fun textMessageIsNotOfferedWithoutATrustedNumber() {
        render(SessionState(accountState = ICloudAccountState.AwaitingTwoFactor("alex@example.com", TwoFactorDelivery.SMS)))
        compose.onNodeWithTag("code-by-sms").assertDoesNotExist()
    }

    @Test fun signOutAsksFirstAndSaysICloudIsUntouched() {
        render(SessionState(accountState = ICloudAccountState.SignedIn("alex@example.com")))
        compose.onNodeWithTag("profile-sign-out").performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.sign_out_confirm_body)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.cancel)).performClick()
        assertEquals(0, signOuts)
        compose.onNodeWithTag("profile-sign-out").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-sign-out").performClick()
        assertEquals(1, signOuts)
    }

    @Test fun removingASavedAccountAfterARestoreFailureAsksFirst() {
        render(SessionState(accountState = ICloudAccountState.Restoring, restoreFailed = true))
        compose.onNodeWithTag("remove-saved-account").performClick()
        compose.onNode(hasText(string(R.string.remove_saved_account_confirm_title))).assertIsDisplayed()
        assertEquals(0, signOuts)
        compose.onNodeWithTag("confirm-sign-out").performClick()
        assertEquals(1, signOuts)
    }

    @Test fun webAccessOffExplainsWhatToTurnOn() {
        render(SessionState(accountState = ICloudAccountState.SignedIn("alex@example.com"), webAccessOff = true))
        compose.onNodeWithTag("web-access-off").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.web_access_off_help)).performScrollTo().performClick()
        assertEquals(listOf("https://support.apple.com/102630"), opened)
    }

    private fun render(session: SessionState) {
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                AlbaTheme(darkTheme = false) {
                    ICloudAccountScreen(
                        session = session,
                        transfers = TransferState(backup = BackupView(false)),
                        phoneStorage = PhoneStorageState(),
                        mediaBusy = false,
                        diagnostics = DiagnosticsConsent(),
                        actions = testAlbaActions(signOut = { signOuts++ }, sendCodeBySms = { smsRequests++ }),
                    )
                }
            }
        }
    }
}
