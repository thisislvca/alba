package dev.mela.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.mela.app.ui.ICloudAccountScreen
import dev.mela.app.ui.theme.MelaTheme
import dev.mela.engine.model.CloudAccountProfile
import dev.mela.engine.model.CloudPlanSource
import dev.mela.engine.model.CloudStoragePlan
import dev.mela.engine.model.BackupView
import dev.mela.engine.model.CloudStorageUsage
import dev.mela.engine.model.StorageCategory
import dev.mela.protocol.account.ICloudAccountState
import dev.mela.protocol.account.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

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
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("account-photo").fetchSemanticsNodes().isNotEmpty() }
        capture("profile-light")
        compose.onNodeWithTag("profile-manage-plan").performScrollTo().performClick()
        assertEquals(listOf("https://www.icloud.com/plan"), opened)
        compose.onNodeWithTag("profile-backup").performScrollTo().performClick()
        compose.onNodeWithText("Cloud storage").assertIsDisplayed()
        compose.onNodeWithText("72 GB of 200 GB used").assertIsDisplayed()
        compose.onNodeWithText("Set up automatic backup").performScrollTo().assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.runOnIdle { dark = true }
        compose.onNodeWithText("alex@example.com").performScrollTo()
        capture("profile-dark")
        compose.onNodeWithTag("profile-phone").performScrollTo().performClick()
        compose.onNodeWithTag("phone-storage").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Apple session verified").performScrollTo()
        compose.onNodeWithText("Apple session verified").assertIsDisplayed()
        compose.onNodeWithTag("profile-check-connection").performScrollTo()
        capture("profile-inline-connection")
        compose.onNodeWithTag("profile-check-connection").performClick()
        assertEquals(1, checks)
    }

    @Test fun italianLargeTextProfileKeepsActionsReachable() {
        render({ true }, locale = "it", scale = 1.5f)
        compose.onNodeWithText("200 GB").assertIsDisplayed()
        capture("profile-it-large-top")
        compose.onNodeWithTag("profile-manage-plan").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("profile-about").performScrollTo().assertIsDisplayed()
        capture("profile-it-large-settings")
    }

    @Test fun expiredAccountDoesNotShowDemoAllowanceAndCanReconnect() {
        var dark by mutableStateOf(false)
        render({ dark }, state = ICloudAccountState.SignedIn("alex@example.com", SessionStatus.EXPIRED), demoStorage = true)
        compose.onNodeWithText("200 GB").assertDoesNotExist()
        compose.onNodeWithText("Storage usage is not available yet.").assertExists()
        compose.onNodeWithTag("profile-sign-in").performScrollTo().performClick()
        compose.onNodeWithText("alex@example.com").assertIsDisplayed()
        compose.onNodeWithText("Password").assertIsDisplayed()
        capture("sign-in-light")
        compose.runOnIdle { dark = true }
        capture("sign-in-dark")
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
                MelaTheme(darkTheme = darkTheme) {
                    ICloudAccountScreen(state = state,
                        diagnostics = GalleryUiState(accountState = state, network = NetworkStatus(true, false),
                            lastRefreshedAt = java.time.Instant.ofEpochMilli(1_790_000_000_000),
                            phoneStorage = dev.mela.engine.model.LocalMediaStorage(120_000_000, 480_000_000, 2_400_000_000, 42_000_000_000),
                            accountInfo = AccountInfo(storage = usage.copy(isDemo = demoStorage), photosAvailable = true,
                                profile = if (demoStorage) null else CloudAccountProfile("Alex Example", avatar,
                                    CloudStoragePlan(setOf(CloudPlanSource.ICLOUD_PLUS), 200L shl 30)))),
                        onCheckConnection = { checks++ },
                        onBack = {}, onSignIn = { _, _ -> }, onSubmitTwoFactor = {}, onResendTwoFactor = {}, onSignOut = {},
                        backup = BackupView(false), onEnableAutomaticBackup = {}, onDisableAutomaticBackup = {})
                }
            }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = instrumentation.targetContext.getExternalFilesDir("profile-redesign")!!.apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
