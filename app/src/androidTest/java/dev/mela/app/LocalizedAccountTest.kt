package dev.mela.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.app.ui.ICloudAccountScreen
import dev.mela.app.ui.theme.MelaTheme
import dev.mela.engine.model.BackupView
import dev.mela.protocol.account.ICloudAccountState
import dev.mela.protocol.account.SessionStatus
import dev.mela.protocol.account.TwoFactorDelivery
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

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
                MelaTheme(darkTheme = true) {
                    androidx.compose.material3.Surface {
                        ICloudAccountScreen(state, onBack = {}, onSignIn = { _, _ -> }, onSubmitTwoFactor = { submitted = it },
                            onResendTwoFactor = {}, onSignOut = {}, backup = BackupView(false, message = "Automatic backup is off."),
                            onEnableAutomaticBackup = {}, onDisableAutomaticBackup = {})
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
                MelaTheme(darkTheme = false) {
                    androidx.compose.material3.Surface {
                        ICloudAccountScreen(
                            state = ICloudAccountState.SignedIn("family@example.com", SessionStatus.PHOTOS_NOT_ENABLED),
                            onCheckConnection = { checks++ },
                            onBack = {}, onSignIn = { _, _ -> }, onSubmitTwoFactor = {}, onResendTwoFactor = {},
                            onSignOut = {}, backup = BackupView(false), onEnableAutomaticBackup = {},
                            onDisableAutomaticBackup = {},
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
