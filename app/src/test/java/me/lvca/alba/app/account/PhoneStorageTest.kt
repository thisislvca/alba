package me.lvca.alba.app.account

import android.text.format.Formatter
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.testAlbaActions
import me.lvca.alba.app.ui.account.PhoneStorageDetails
import me.lvca.alba.app.ui.designsystem.AlbaPageScaffold
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.engine.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneStorageTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<AlbaApplication>()
    private fun text(id: Int) = app.getString(id)

    @Test fun phoneStorageCleanupRequiresConfirmationAndKeepsCategoriesSeparate() {
        var removed: LocalMediaCategory? = null
        val state = PhoneStorageState(usage = LocalMediaStorage(1024, 2048, 4096, 128L * 1024 * 1024))
        compose.setContent { AlbaTheme(darkTheme = false) {
            // The profile's phone storage page: the production scaffold around the production card.
            AlbaPageScaffold(text(R.string.phone_storage_title), onBack = {}) {
                PhoneStorageDetails(state, busy = false, testAlbaActions(clearLocalMedia = { removed = it }))
            }
        } }
        compose.onNodeWithTag("clear-storage-VIEWERS").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        assertNull(removed)
        compose.onNodeWithTag("clear-storage-VIEWERS").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-clear-storage").performClick()
        compose.runOnIdle { assertEquals(LocalMediaCategory.VIEWERS, removed) }
        compose.onNodeWithText(text(R.string.saved_thumbnails)).assertExists()
    }

    @Test fun offlinePreviewsOffersEachLimitAndEstimatesTheWholeLibrary() {
        val chosen = mutableListOf<OfflinePreviewLimit>()
        // 400 previews in 25,000,000 bytes measure 62,500 bytes each: 12,000 items need 750 MB.
        val state = PhoneStorageState(usage = LocalMediaStorage(25_000_000, 0, 0, 8_000_000_000, previewCount = 400),
            libraryItemCount = 12_000)
        compose.setContent { AlbaTheme(darkTheme = false) {
            AlbaPageScaffold(text(R.string.phone_storage_title), onBack = {}) {
                PhoneStorageDetails(state, busy = false, testAlbaActions(setOfflinePreviewLimit = { chosen += it }))
            }
        } }
        fun size(bytes: Long) = Formatter.formatShortFileSize(app, bytes)

        compose.onNodeWithTag("offline-previews-usage").performScrollTo()
            .assertTextEquals(app.getString(R.string.offline_previews_usage_estimate, size(25_000_000), size(750_000_000)))
        // The default is 500 MB.
        compose.onNodeWithTag("offline-previews-setting").performScrollTo()
            .assert(hasText(text(R.string.offline_previews_500_mb)))
            .performClick()

        compose.onNodeWithTag("offline-previews-MB_500").assertIsSelected()
        listOf("MB_250", "GB_1", "GB_2", "WHOLE_LIBRARY").forEach { compose.onNodeWithTag("offline-previews-$it").assertIsNotSelected() }
        compose.onNodeWithTag("offline-previews-WHOLE_LIBRARY")
            .assert(hasText(app.getString(R.string.offline_previews_whole_library_estimate, size(750_000_000))))

        // Cancel changes nothing.
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.onNodeWithTag("offline-previews-options").assertDoesNotExist()
        assertTrue(chosen.isEmpty())

        // Choosing the current limit again changes nothing either; another one is applied and closes the dialog.
        compose.onNodeWithTag("offline-previews-setting").performClick()
        compose.onNodeWithTag("offline-previews-MB_500").performClick()
        compose.runOnIdle { assertTrue(chosen.isEmpty()) }
        compose.onNodeWithTag("offline-previews-setting").performClick()
        compose.onNodeWithTag("offline-previews-WHOLE_LIBRARY").performClick()
        compose.onNodeWithTag("offline-previews-options").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(OfflinePreviewLimit.WHOLE_LIBRARY), chosen) }
    }

    @Test fun offlinePreviewsShowsTheSavedChoice() {
        val state = PhoneStorageState(usage = LocalMediaStorage(0, 0, 0, 8_000_000_000), previewLimit = OfflinePreviewLimit.GB_2)
        compose.setContent { AlbaTheme(darkTheme = false) {
            AlbaPageScaffold(text(R.string.phone_storage_title), onBack = {}) {
                PhoneStorageDetails(state, busy = false, testAlbaActions())
            }
        } }
        compose.onNodeWithTag("offline-previews-setting").performScrollTo()
            .assert(hasText(text(R.string.offline_previews_2_gb)))
            .performClick()
        compose.onNodeWithTag("offline-previews-GB_2").assertIsSelected()
        // No library yet to estimate: the option is plain.
        compose.onNodeWithTag("offline-previews-WHOLE_LIBRARY")
            .assert(hasText(text(R.string.offline_previews_whole_library)))
    }
}
