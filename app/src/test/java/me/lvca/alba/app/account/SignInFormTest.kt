package me.lvca.alba.app.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.lvca.alba.app.DiagnosticsConsent
import me.lvca.alba.app.R
import me.lvca.alba.app.platform.demoEntry
import me.lvca.alba.app.testing.testAlbaActions
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.account.ICloudAccountScreen
import me.lvca.alba.app.ui.account.ProfilePage
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
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
    private val signIns = mutableListOf<Pair<String, String>>()
    private var closes = 0
    private var demos = 0

    @Test fun wrongPasswordShowsUnderTheFieldWithHelp() {
        render(SessionState(accountState = ICloudAccountState.SignedOut, signInProblem = SignInProblem.WRONG_PASSWORD))
        compose.onNodeWithTag("sign-in-wrong-password").performScrollTo().assertIsDisplayed()
            .assertTextEquals(string(R.string.invalid_credentials))
        val password = compose.onNodeWithTag("sign-in-password").fetchSemanticsNode()
        assertTrue(SemanticsProperties.Error in password.config)
        compose.onNodeWithTag("sign-in-forgot-password").performScrollTo().performClick()
        assertEquals(listOf("https://iforgot.apple.com"), opened)
    }

    @Test fun pageLeadsWithAlbaAndKeepsTheCompatibilityWarningInADialog() {
        render(SessionState(accountState = ICloudAccountState.SignedOut))
        // The brand shrinks to the space the sheet leaves, which on this small test window may be none.
        compose.onNodeWithTag("sign-in-brand").assertExists()
        compose.onNodeWithTag("sign-in-sheet").assertIsDisplayed()
        compose.onNodeWithTag("sign-in-title").performScrollTo().assertIsDisplayed()
        // The trust line and the details link sit below the button, outside the scrolling form.
        compose.onNodeWithTag("sign-in-trust").assertIsDisplayed()
        compose.onNodeWithTag("sign-in-unsupported").assertDoesNotExist()
        compose.onNodeWithTag("sign-in-compatibility").assertIsDisplayed().performClick()
        compose.onNodeWithTag("sign-in-compatibility-dialog").assertIsDisplayed()
        compose.onNode(hasTestTag("sign-in-unsupported") and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.supported_in_v1_normal_two_factor_authentication_and_the_personal_library_security_ke))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("sign-in-compatibility-close").performClick()
        compose.onNodeWithTag("sign-in-compatibility-dialog").assertDoesNotExist()
        compose.onNodeWithTag("sign-in-unsupported").assertDoesNotExist()
    }

    @Test fun signInHasNoBackButton() {
        render(SessionState(accountState = ICloudAccountState.SignedOut))
        compose.onNodeWithTag("sign-in-page").assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.back_action)).assertDoesNotExist()
        compose.onNodeWithTag("sign-in-back").assertDoesNotExist()
        assertEquals(0, closes)
    }

    @Test fun releaseBuildsOfferNoDemo() {
        render(SessionState(accountState = ICloudAccountState.SignedOut), tryDemo = demoEntry({ demos++ }, enabled = false))
        compose.onNodeWithTag("sign-in-compatibility").assertIsDisplayed()
        compose.onNodeWithTag("sign-in-demo").assertDoesNotExist()
        compose.onNodeWithText(string(R.string.welcome_demo)).assertDoesNotExist()
    }

    @Test fun debugBuildsOfferTheDemoAsAQuietLink() {
        render(SessionState(accountState = ICloudAccountState.SignedOut), tryDemo = demoEntry({ demos++ }, enabled = true))
        compose.onNodeWithTag("sign-in-demo").assertIsDisplayed().assertTextEquals(string(R.string.welcome_demo)).performClick()
        assertEquals(1, demos)
        assertEquals(0, closes)
    }

    @Test fun signInIsPinnedBelowTheFormAndSubmitsFromTheKeyboard() {
        render(SessionState(accountState = ICloudAccountState.SignedOut))
        // The button sits outside the scrolling form, so it is on screen without scrolling to it.
        compose.onNodeWithTag("sign-in-submit").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("sign-in-apple-id").performTextInput("alex@example.com")
        compose.onNodeWithTag("sign-in-password").performTextInput("correct horse")
        compose.onNodeWithTag("sign-in-submit").assertIsEnabled()
        compose.onNodeWithTag("sign-in-password").performImeAction()
        assertEquals(listOf("alex@example.com" to "correct horse"), signIns)
        compose.onNodeWithTag("sign-in-submit").performClick()
        assertEquals(2, signIns.size)
    }

    @Test fun anExpiredSessionSaysTheLibraryIsStillHere() {
        render(SessionState(accountState = ICloudAccountState.SignedIn("alex@example.com", SessionStatus.EXPIRED),
            requestedPage = ProfilePage.SIGN_IN))
        compose.onNodeWithTag("sign-in-expired").assertIsDisplayed()
        // The saved account is filled in, so only the password is missing.
        compose.onNodeWithText("alex@example.com").assertIsDisplayed()
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

    private fun render(session: SessionState, tryDemo: (() -> Unit)? = null) {
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
                        actions = testAlbaActions(signOut = { signOuts++ }, sendCodeBySms = { smsRequests++ },
                            signIn = { appleId, password -> signIns += appleId to password }, closeAccount = { closes++ }),
                        tryDemo = tryDemo,
                    )
                }
            }
        }
    }
}
