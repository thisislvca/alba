package me.lvca.alba.app.account

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.engine.model.CloudAccountProfile
import me.lvca.alba.engine.model.CloudPlanSource
import me.lvca.alba.engine.model.CloudStoragePlan
import me.lvca.alba.engine.model.CloudStorageUsage
import me.lvca.alba.engine.model.StorageCategory
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import me.lvca.alba.app.DiagnosticsConsent
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.testAlbaActions
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.account.ICloudAccountScreen
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme

class ProfileScreenTest {
    @get:Rule val compose = createComposeRule()
    private val avatar by lazy {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(83, 132, 161))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(239, 215, 166) }
        canvas.drawCircle(48f, 36f, 18f, paint)
        canvas.drawOval(13f, 59f, 83f, 125f, paint)
        java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
    }
    private var checks = 0
    private val account = ICloudAccountState.SignedIn("alex@example.com")
    private val usage = CloudStorageUsage(72_000_000_000, 200_000_000_000,
        listOf(StorageCategory("Photos", 64_000_000_000), StorageCategory("Documents", 4_000_000_000), StorageCategory("Backup", 2_000_000_000), StorageCategory("Other", 2_000_000_000)), 1_790_000_000_000)

    @Test fun signedInProfileLightAndDarkShowsAllowanceAndRoutesSettings() {
        var dark by mutableStateOf(false)
        val opened = mutableListOf<String>()
        render({ dark }, uriHandler = object : UriHandler { override fun openUri(uri: String) { opened += uri } })
        compose.onNodeWithText("alex@example.com").assertIsDisplayed()
        compose.onNodeWithText("200 GB").assertIsDisplayed()
        compose.onNodeWithText("Alex Example").assertIsDisplayed()
        compose.onNodeWithText("iCloud+").assertIsDisplayed()
        compose.waitUntil(SHORT_WAIT) { compose.onAllNodesWithTag("account-photo").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("profile-manage-plan").performScrollTo().performClick()
        assertEquals(listOf("https://www.icloud.com/plan"), opened)
        compose.onNodeWithTag("profile-backup").performScrollTo().performClick()
        compose.onNodeWithText("Cloud storage").assertIsDisplayed()
        compose.onNodeWithText("72 GB of 200 GB used").assertIsDisplayed()
        compose.onNodeWithText("Set up automatic backup").performScrollTo().assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.runOnIdle { dark = true }
        compose.onNodeWithText("alex@example.com").performScrollTo()
        compose.onNodeWithTag("profile-phone").performScrollTo().performClick()
        compose.onNodeWithTag("phone-storage").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Apple session verified").performScrollTo()
        compose.onNodeWithText("Apple session verified").assertIsDisplayed()
        compose.onNodeWithTag("profile-check-connection").performScrollTo()
        compose.onNodeWithTag("profile-check-connection").performClick()
        assertEquals(1, checks)
    }

    @Test fun italianLargeTextProfileKeepsActionsReachable() {
        render({ true }, locale = "it", scale = 1.5f)
        compose.onNodeWithText("200 GB").assertIsDisplayed()
        compose.onNodeWithTag("profile-manage-plan").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("profile-about").performScrollTo().assertIsDisplayed()
    }

    @Test fun expiredAccountDoesNotShowDemoAllowanceAndCanReconnect() {
        var dark by mutableStateOf(false)
        render({ dark }, state = ICloudAccountState.SignedIn("alex@example.com", SessionStatus.EXPIRED), demoStorage = true)
        compose.onNodeWithText("200 GB").assertDoesNotExist()
        compose.onNodeWithText("Storage usage is not available yet.").assertExists()
        compose.onNodeWithTag("profile-sign-in").performScrollTo().performClick()
        compose.onNodeWithText("alex@example.com").assertIsDisplayed()
        compose.onNodeWithText("Password").assertIsDisplayed()
        compose.runOnIdle { dark = true }
    }

    private fun render(dark: () -> Boolean, locale: String = "en", scale: Float = 1f,
        state: ICloudAccountState = account, demoStorage: Boolean = false, uriHandler: UriHandler? = null) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(locale)); fontScale = scale }
        val context = base.createConfigurationContext(config)
        compose.setContent {
            val handler = uriHandler ?: LocalUriHandler.current
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
                LocalDensity provides Density(context.resources.displayMetrics.density, scale), LocalUriHandler provides handler) {
                val darkTheme = dark()
                val activity = LocalView.current.context as? android.app.Activity
                SideEffect {
                    activity?.let {
                        androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                            isAppearanceLightStatusBars = !darkTheme
                            isAppearanceLightNavigationBars = !darkTheme
                        }
                    }
                }
                AlbaTheme(darkTheme = darkTheme) {
                    ICloudAccountScreen(
                        session = SessionState(accountState = state, network = NetworkStatus(true, false),
                            lastRefreshedAt = java.time.Instant.ofEpochMilli(1_790_000_000_000),
                            accountInfo = AccountInfo(storage = usage.copy(isDemo = demoStorage), photosAvailable = true,
                                profile = if (demoStorage) null else CloudAccountProfile("Alex Example", avatar,
                                    CloudStoragePlan(setOf(CloudPlanSource.ICLOUD_PLUS), 200L shl 30)))),
                        transfers = TransferState(backup = BackupView(false)),
                        phoneStorage = PhoneStorageState(usage = me.lvca.alba.engine.model.LocalMediaStorage(120_000_000, 480_000_000, 2_400_000_000, 42_000_000_000)),
                        mediaBusy = false,
                        diagnostics = DiagnosticsConsent(),
                        actions = testAlbaActions(checkConnection = { checks++ }))
                }
            }
        }
    }
}
