package me.lvca.alba.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import me.lvca.alba.protocol.account.TwoFactorDelivery
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import me.lvca.alba.app.account.PhoneStorageState
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.ICloudAccountScreen
import me.lvca.alba.app.ui.theme.AlbaTheme

class LocalizedAccountTest {
    @get:Rule val compose = createComposeRule()
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
        compose.onNodeWithText("Apple ha inviato un codice a sei cifre al tuo numero di telefono autorizzato (+39 ••• 1234).").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Codice a sei cifre").performScrollTo().performTextInput("123456")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Verifica e continua").performScrollTo().performClick()
        assertEquals("123456", submitted)
        capture("08-two-factor-it-large")
        compose.runOnIdle { state = ICloudAccountState.SignedIn("family@example.com") }
        compose.onNodeWithTag("profile-backup").performScrollTo().performClick()
        compose.onNodeWithText("Il backup automatico è disattivato.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Configura backup automatico").performScrollTo().assertIsDisplayed()
        capture("09-backup-it-large")
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
        compose.onNodeWithText("Foto di iCloud non è attivo").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("iCloud+ è facoltativo. Attivando l’account iCloud completo ottieni già 5 GB di spazio gratuito.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ricontrolla").performScrollTo().performClick()
        assertEquals(1, checks)
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val dir = instrumentation.targetContext.getExternalFilesDir("localization")!!.apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
