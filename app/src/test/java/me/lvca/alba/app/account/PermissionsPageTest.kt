package me.lvca.alba.app.account

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.ConnectivityManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.DiagnosticsConsent
import me.lvca.alba.app.R
import me.lvca.alba.app.platform.AppPermission
import me.lvca.alba.app.platform.AppPermissions
import me.lvca.alba.app.platform.ICLOUD_LINK_HOSTS
import me.lvca.alba.app.platform.ICloudLinksSource
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.platform.PermissionFix
import me.lvca.alba.app.platform.PermissionPromptPreferences
import me.lvca.alba.app.platform.PhotoAccess
import me.lvca.alba.app.platform.fix
import me.lvca.alba.app.platform.iCloudLinksOn
import me.lvca.alba.app.testing.testAlbaActions
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.account.ICloudAccountScreen
import me.lvca.alba.app.ui.account.LocalICloudLinks
import me.lvca.alba.app.ui.account.PermissionsPage
import me.lvca.alba.app.ui.account.ProfilePage
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Permissions page from Android's real permission state, shadowed: each status, its fix, the summary and refresh. */
@RunWith(AndroidJUnit4::class)
class PermissionsPageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var photoRequests = 0

    private val fullPhotos = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

    @Before fun clearRememberedPrompts() {
        app.getSharedPreferences("alba-permissions", 0).edit().clear().commit()
    }

    @Test fun everythingAllowedSaysSoAndOffersNothingToFix() {
        allowEverything()
        render()
        summary(app.getString(R.string.permissions_summary_allowed))
        status(AppPermission.PHOTOS, R.string.permission_photos_full)
        status(AppPermission.LOCATION, R.string.permission_location_allowed)
        status(AppPermission.NOTIFICATIONS, R.string.permission_notifications_allowed)
        status(AppPermission.BATTERY, R.string.permission_battery_unrestricted)
        status(AppPermission.BACKGROUND_DATA, R.string.permission_background_data_allowed)
        AppPermission.entries.forEach { compose.onNodeWithTag("permission-action-${it.id}").assertDoesNotExist() }
    }

    @Test fun nothingAllowedCountsEveryRowAndAsksForPhotosThroughTheAppsPhotoRequest() {
        render()
        // Photos, location, notifications and battery; background data is not restricted by default.
        summary(app.resources.getQuantityString(R.plurals.permissions_summary_attention, 4, 4))
        status(AppPermission.PHOTOS, R.string.permission_photos_none)
        action(AppPermission.PHOTOS, R.string.permission_allow).performClick()
        compose.runOnIdle { assertEquals(1, photoRequests) }
    }

    @Test fun theSummaryCountsWhatIsLeft() {
        allowEverything()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        render()
        summary(app.resources.getQuantityString(R.plurals.permissions_summary_attention, 1, 1))
    }

    @Test @Config(sdk = [34])
    fun selectedPhotosOnlyAsksAgainSoTheUserCanAllowAll() {
        allowEverything()
        shadowOf(app).denyPermissions(*fullPhotos)
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        render()
        status(AppPermission.PHOTOS, R.string.permission_photos_selected)
        action(AppPermission.PHOTOS, R.string.permission_change).performClick()
        compose.runOnIdle { assertEquals(1, photoRequests) }
    }

    @Test fun photosAndroidNoLongerAsksForOpenAlbasSettings() {
        PermissionPromptPreferences(app).setBlocked(PermissionPromptPreferences.PHOTOS, true)
        render()
        action(AppPermission.PHOTOS, R.string.permission_open_settings).performClick()
        assertAppSettingsOpened()
        compose.runOnIdle { assertEquals(0, photoRequests) }
    }

    @Test fun locationOffAsksAndroidForIt() {
        allowEverything()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        render()
        status(AppPermission.LOCATION, R.string.permission_location_denied)
        action(AppPermission.LOCATION, R.string.permission_allow).performClick()
        compose.runOnIdle {
            val requested = shadowOf(compose.activity).lastRequestedPermission
            assertEquals(listOf(Manifest.permission.ACCESS_MEDIA_LOCATION), requested.requestedPermissions.toList())
        }
    }

    @Test fun locationDeniedForGoodOpensAlbasSettingsInstead() {
        allowEverything()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        PermissionPromptPreferences(app).setBlocked(AppPermission.LOCATION.id, true)
        render()
        action(AppPermission.LOCATION, R.string.permission_open_settings).performClick()
        assertAppSettingsOpened()
        compose.runOnIdle { assertNull(shadowOf(compose.activity).lastRequestedPermission) }
    }

    @Test fun notificationsOffAskAndroid() {
        allowEverything()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render()
        status(AppPermission.NOTIFICATIONS, R.string.permission_notifications_denied)
        action(AppPermission.NOTIFICATIONS, R.string.permission_allow).performClick()
        compose.runOnIdle {
            assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS),
                shadowOf(compose.activity).lastRequestedPermission.requestedPermissions.toList())
        }
    }

    @Test fun notificationsTurnedOffInSettingsAreTurnedOnThere() {
        allowEverything()
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        render()
        status(AppPermission.NOTIFICATIONS, R.string.permission_notifications_denied)
        action(AppPermission.NOTIFICATIONS, R.string.permission_open_settings).performClick()
        val opened = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.action)
        assertEquals(app.packageName, opened.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test @Config(sdk = [32])
    fun beforeAndroid13ThereIsNoNotificationRow() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
        exemptFromBatteryOptimisation()
        render()
        compose.onNodeWithTag("permission-notifications").assertDoesNotExist()
        status(AppPermission.PHOTOS, R.string.permission_photos_full)
        summary(app.getString(R.string.permissions_summary_allowed))
    }

    @Test fun batteryOptimisationOpensTheSystemBatteryList() {
        allowEverything()
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, false)
        render()
        status(AppPermission.BATTERY, R.string.permission_battery_optimized)
        action(AppPermission.BATTERY, R.string.permission_open_settings).performClick()
        // The settings list, never the direct exemption request Play restricts.
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, shadowOf(compose.activity).nextStartedActivity.action)
    }

    @Test fun restrictedBackgroundDataOpensAlbasDataSettings() {
        allowEverything()
        shadowOf(app.getSystemService(ConnectivityManager::class.java))
            .setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
        render()
        status(AppPermission.BACKGROUND_DATA, R.string.permission_background_data_restricted)
        summary(app.resources.getQuantityString(R.plurals.permissions_summary_attention, 1, 1))
        action(AppPermission.BACKGROUND_DATA, R.string.permission_open_settings).performClick()
        val opened = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, opened.action)
        assertEquals(Uri.fromParts("package", app.packageName, null), opened.data)
    }

    @Test fun statusesRefreshWhenTheUserComesBackFromSettings() {
        allowEverything()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        render()
        status(AppPermission.LOCATION, R.string.permission_location_denied)

        // The user allows it in Settings and returns to Alba.
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        status(AppPermission.LOCATION, R.string.permission_location_allowed)
        summary(app.getString(R.string.permissions_summary_allowed))
        compose.onNodeWithTag("permission-action-location").assertDoesNotExist()
    }

    @Test fun iCloudLinksOffExplainAndOpenAlbasOpenByDefaultSettings() {
        allowEverything()
        render(links = false)
        status(AppPermission.ICLOUD_LINKS, R.string.permission_icloud_links_off)
        summary(app.resources.getQuantityString(R.plurals.permissions_summary_attention, 1, 1))
        action(AppPermission.ICLOUD_LINKS, R.string.permission_open_settings).performClick()
        val opened = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, opened.action)
        assertEquals(Uri.parse("package:" + app.packageName), opened.data)
    }

    @Test fun iCloudLinksOnShowAsAllowed() {
        allowEverything()
        render(links = true)
        status(AppPermission.ICLOUD_LINKS, R.string.permission_icloud_links_allowed)
        compose.onNodeWithTag("permission-action-icloud-links").assertDoesNotExist()
        summary(app.getString(R.string.permissions_summary_allowed))
    }

    @Test @Config(sdk = [30])
    fun beforeAndroid12ThereIsNoICloudLinksRow() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
        exemptFromBatteryOptimisation()
        render(links = false)
        compose.onNodeWithTag("permission-icloud-links").assertDoesNotExist()
        summary(app.getString(R.string.permissions_summary_allowed))
    }

    @Test fun iCloudLinksAreOnOnlyWhenEveryHostIsSelectedOrVerified() {
        val on = ICLOUD_LINK_HOSTS.associateWith { DomainVerificationUserState.DOMAIN_STATE_SELECTED }
        assertTrue(iCloudLinksOn(on))
        assertTrue(iCloudLinksOn(on + ("icloud.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)))
        assertEquals(false, iCloudLinksOn(on + ("icloud.com" to DomainVerificationUserState.DOMAIN_STATE_NONE)))
        // Other hosts the app declares don't count either way.
        assertTrue(iCloudLinksOn(on + ("example.com" to DomainVerificationUserState.DOMAIN_STATE_NONE)))
        assertEquals(false, iCloudLinksOn(emptyMap()))
    }

    @Test fun theProfileLeadsHere() {
        allowEverything()
        render(ProfilePage.OVERVIEW)
        compose.onNodeWithTag("profile-permissions").performScrollTo()
            .assert(hasText(app.getString(R.string.permissions_summary_allowed))).performClick()
        compose.onNodeWithTag("permissions-page").assertIsDisplayed()
        summary(app.getString(R.string.permissions_summary_allowed))
    }

    @Test fun theBackupPageLeadsHereWithWhatNeedsTheUser() {
        render(ProfilePage.BACKUP)
        compose.onNodeWithTag("backup-permissions").performScrollTo()
            .assert(hasText(app.resources.getQuantityString(R.plurals.permissions_summary_attention, 4, 4)))
            .performClick()
        compose.onNodeWithTag("permissions-page").assertIsDisplayed()
        compose.onAllNodesWithTag("permission-action-photos").assertCountEquals(1)
    }

    @Test fun aRowNeedsTheUserOnlyWhileItIsNotAllowed() {
        val allowed = AppPermissions(PhotoAccess.FULL, location = true, notifications = true, batteryUnrestricted = true,
            backgroundDataRestricted = false)
        AppPermission.entries.forEach { assertNull(allowed.fix(it, blocked = true)) }
        assertTrue(allowed.backupAccess)

        val none = AppPermissions(PhotoAccess.NONE, location = false, notifications = false, batteryUnrestricted = false,
            backgroundDataRestricted = true)
        assertEquals(PermissionFix.REQUEST, none.fix(AppPermission.LOCATION, blocked = false))
        assertEquals(PermissionFix.APP_SETTINGS, none.fix(AppPermission.LOCATION, blocked = true))
        assertEquals(PermissionFix.APP_SETTINGS, none.fix(AppPermission.PHOTOS, blocked = true))
        assertEquals(PermissionFix.NOTIFICATION_SETTINGS, none.fix(AppPermission.NOTIFICATIONS, blocked = true))
        assertEquals(PermissionFix.BATTERY_SETTINGS, none.fix(AppPermission.BATTERY, blocked = false))
        assertEquals(PermissionFix.DATA_SETTINGS, none.fix(AppPermission.BACKGROUND_DATA, blocked = false))
        // Selected photos ask again even after a denial, so the user can choose "Allow all".
        assertEquals(PermissionFix.REQUEST, none.copy(photos = PhotoAccess.SELECTED).fix(AppPermission.PHOTOS, blocked = true))
        // Backup needs every photo, with its location.
        assertEquals(false, none.copy(photos = PhotoAccess.FULL).backupAccess)
        assertEquals(false, none.copy(photos = PhotoAccess.SELECTED, location = true).backupAccess)
        assertEquals(5, none.needingAttention.size)
        assertEquals(4, none.copy(notifications = null).needingAttention.size)
    }

    private fun allowEverything() {
        shadowOf(app).grantPermissions(*fullPhotos, Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        exemptFromBatteryOptimisation()
    }

    private fun exemptFromBatteryOptimisation() =
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)

    /** The page, with iCloud links [links] on or off; null leaves Android's own answer, which a test can't set. */
    private fun render(links: Boolean? = null) = compose.setContent {
        AlbaTheme(darkTheme = false) {
            val source = links?.let { ICloudLinksSource { _ -> it } } ?: LocalICloudLinks.current
            CompositionLocalProvider(LocalICloudLinks provides source) {
                Column(Modifier.verticalScroll(rememberScrollState())) { PermissionsPage(requestPhotos = { photoRequests++ }) }
            }
        }
    }

    /** The whole account screen, opened at [page]. */
    private fun render(page: ProfilePage) = compose.setContent {
        AlbaTheme(darkTheme = false) {
            ICloudAccountScreen(
                session = SessionState(accountState = ICloudAccountState.SignedIn("alex@example.com"), network = NetworkStatus(true, false),
                    requestedPage = page),
                transfers = TransferState(backup = BackupView(enabled = true, destinationLabel = "alex@example.com", scope = BackupScope.CURRENT)),
                phoneStorage = PhoneStorageState(),
                mediaBusy = false,
                diagnostics = DiagnosticsConsent(),
                actions = testAlbaActions(requestDevicePhotos = { photoRequests++ }),
            )
        }
    }

    private fun summary(text: String) = compose.onNodeWithTag("permissions-summary").assertTextEquals(text)

    private fun status(permission: AppPermission, text: Int) =
        compose.onNodeWithTag("permission-status-${permission.id}", useUnmergedTree = true).performScrollTo().assertTextEquals(app.getString(text))

    private fun action(permission: AppPermission, label: Int) =
        compose.onNodeWithTag("permission-action-${permission.id}").performScrollTo().assertTextEquals(app.getString(label))

    private fun assertAppSettingsOpened() {
        val opened = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.action)
        assertEquals(Uri.fromParts("package", app.packageName, null), opened.data)
    }
}
