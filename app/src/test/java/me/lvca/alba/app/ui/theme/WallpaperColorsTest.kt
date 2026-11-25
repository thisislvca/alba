package me.lvca.alba.app.ui.theme

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.DiagnosticsConsent
import me.lvca.alba.app.account.PhoneStorageState
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.platform.AppearancePreferences
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.testing.testAlbaActions
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.account.ICloudAccountScreen
import me.lvca.alba.app.ui.designsystem.theme.AlbaDarkColorScheme
import me.lvca.alba.app.ui.designsystem.theme.AlbaLightColorScheme
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.designsystem.theme.LocalWallpaperColors
import me.lvca.alba.app.ui.designsystem.theme.WallpaperColorsSetting
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Match wallpaper colors": off by default, saved, offered in the profile, and applied by the theme. */
@RunWith(AndroidJUnit4::class)
class WallpaperColorsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun theSettingIsOffUntilChosenAndSurvivesTheProcess() {
        val preferences = AppearancePreferences(context)
        assertFalse(preferences.wallpaperColors.value)
        preferences.setWallpaperColors(true)
        assertTrue(preferences.wallpaperColors.value)
        assertTrue(AppearancePreferences(context).wallpaperColors.value)
    }

    @Test fun theThemeUsesICloudBlueUnlessWallpaperColorsAreOn() {
        val primary = mutableListOf<Color>()
        var wallpaper by mutableStateOf(false)
        var dark by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalWallpaperColors provides WallpaperColorsSetting(true, wallpaper) {}) {
                AlbaTheme(darkTheme = dark) { primary += MaterialTheme.colorScheme.primary }
            }
        }
        compose.runOnIdle { assertEquals(AlbaLightColorScheme.primary, primary.last()) }
        dark = true
        compose.runOnIdle { assertEquals(AlbaDarkColorScheme.primary, primary.last()) }
        wallpaper = true
        compose.runOnIdle {
            assertEquals(dynamicDarkColorScheme(context).primary, primary.last())
            assertNotEquals(AlbaDarkColorScheme.primary, primary.last())
        }
        dark = false
        compose.runOnIdle { assertEquals(dynamicLightColorScheme(context).primary, primary.last()) }
    }

    @Test fun theProfileOffersTheSettingWhereTheSystemHasWallpaperColors() {
        val changes = mutableListOf<Boolean>()
        var enabled by mutableStateOf(false)
        showProfile { WallpaperColorsSetting(available = true, enabled = enabled) { changes += it; enabled = it } }
        compose.onNodeWithTag("wallpaper-colors-switch").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("wallpaper-colors-switch").assertIsOn()
        assertEquals(listOf(true), changes)
    }

    @Test fun theProfileHidesTheSettingWithoutWallpaperColors() {
        showProfile { WallpaperColorsSetting(available = false, enabled = false) {} }
        compose.onNodeWithTag("profile-about").performScrollTo()
        compose.onNodeWithTag("wallpaper-colors-switch").assertDoesNotExist()
    }

    private fun showProfile(setting: () -> WallpaperColorsSetting) {
        compose.setContent {
            CompositionLocalProvider(LocalWallpaperColors provides setting()) {
                AlbaTheme(darkTheme = false) {
                    ICloudAccountScreen(
                        session = SessionState(accountState = ICloudAccountState.SignedIn("alex@example.com"), network = NetworkStatus(true, false)),
                        transfers = TransferState(backup = BackupView(false)),
                        phoneStorage = PhoneStorageState(),
                        mediaBusy = false,
                        diagnostics = DiagnosticsConsent(),
                        actions = testAlbaActions(),
                    )
                }
            }
        }
    }
}
