package me.lvca.alba.app.localization

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.lvca.alba.app.DiagnosticsConsent
import me.lvca.alba.app.R
import me.lvca.alba.app.account.PhoneStorageState
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.testing.hideKeyboard
import me.lvca.alba.app.testing.testAlbaActions
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.account.ICloudAccountScreen
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import me.lvca.alba.protocol.account.TwoFactorDelivery
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizedAccountTest {
    @get:Rule val compose = createComposeRule()

    // Strings come from the Italian resources rather than literal copy, so a translation edit does not break these.
    @Test fun italianTwoFactorAndBackupRemainUsableAtLargeTextSize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("it"))
            fontScale = 1.5f
        }
        val context = instrumentation.targetContext.createConfigurationContext(config)
        var state by mutableStateOf<ICloudAccountState>(ICloudAccountState.AwaitingTwoFactor("family@example.com", TwoFactorDelivery.SMS, "+39 ••• 1234"))
        var submitted: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
                LocalDensity provides Density(context.resources.displayMetrics.density, config.fontScale)) {
                AlbaTheme(darkTheme = true) {
                    androidx.compose.material3.Surface {
                        ICloudAccountScreen(SessionState(accountState = state), TransferState(backup = BackupView(false, message = "Automatic backup is off.")),
                            PhoneStorageState(), mediaBusy = false, DiagnosticsConsent(), testAlbaActions(submitTwoFactor = { submitted = it }))
                    }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.code_sent_sms_hint, "+39 ••• 1234")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.six_digit_code)).performScrollTo().performTextInput("123456")
        compose.hideKeyboard()
        compose.onNodeWithText(context.getString(R.string.verify_and_continue)).performScrollTo().performClick()
        assertEquals("123456", submitted)
        compose.runOnIdle { state = ICloudAccountState.SignedIn("family@example.com") }
        compose.onNodeWithTag("profile-backup").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.message_automatic_backup_is_off)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.set_up_automatic_backup)).performScrollTo().assertIsDisplayed()
    }

    @Test fun italianWebOnlyAccountExplainsActivationAndCanRetry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("it"))
        }
        val context = instrumentation.targetContext.createConfigurationContext(config)
        var checks = 0
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config) {
                AlbaTheme(darkTheme = false) {
                    androidx.compose.material3.Surface {
                        ICloudAccountScreen(
                            SessionState(accountState = ICloudAccountState.SignedIn("family@example.com", SessionStatus.PHOTOS_NOT_ENABLED)),
                            TransferState(), PhoneStorageState(), mediaBusy = false, DiagnosticsConsent(),
                            testAlbaActions(checkConnection = { checks++ }),
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("profile-sign-in").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.icloud_photos_is_not_active)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.icloud_plus_not_required))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.check_again)).performScrollTo().performClick()
        assertEquals(1, checks)
    }
}
