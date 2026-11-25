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

    private fun show(state: PhoneStorageState, budgets: MutableList<ThumbnailBudget> = mutableListOf(),
        limits: MutableList<CacheLimit> = mutableListOf()) {
        compose.setContent { AlbaTheme(darkTheme = false) {
            AlbaPageScaffold(text(R.string.phone_storage_title), onBack = {}) {
                PhoneStorageDetails(state, busy = false,
                    testAlbaActions(setThumbnailBudget = { budgets += it }, setCacheLimit = { limits += it }))
            }
        } }
    }

    @Test fun offlinePhotosSaysHowManyAreAvailableAndWhyItWaits() {
        show(PhoneStorageState(usage = LocalMediaStorage(0, 0, 0, 8_000_000_000),
            offlinePhotos = OfflinePhotosStatus(OfflineThumbnailProgress(libraryCount = 12_606, setSize = 12_606, savedCount = 8_400),
                wait = OfflinePhotosWait.WIFI_AND_CHARGING)))

        compose.onNodeWithTag("offline-photos-setting").performScrollTo().assert(hasText(text(R.string.storage_limit_1_gb)))
        compose.onNodeWithTag("offline-photos-progress").performScrollTo()
            .assertTextEquals(app.getString(R.string.offline_photos_progress, "8,400", "12,606"))
        compose.onNodeWithTag("offline-photos-status").assertTextEquals(text(R.string.offline_photos_waiting))
        // 1 GB holds the whole library: no suggestion.
        compose.onNodeWithTag("offline-photos-suggestion").assertDoesNotExist()
    }

    @Test fun offlinePhotosSaysWhenStorageIsLow() {
        val progress = OfflineThumbnailProgress(libraryCount = 500, setSize = 500, savedCount = 120)
        show(PhoneStorageState(offlinePhotos = OfflinePhotosStatus(progress, wait = OfflinePhotosWait.STORAGE_LOW)))
        compose.onNodeWithTag("offline-photos-status").performScrollTo().assertTextEquals(text(R.string.offline_photos_storage_low))
    }

    @Test fun aCompleteSetSaysNothingMore() {
        show(PhoneStorageState(offlinePhotos = OfflinePhotosStatus(OfflineThumbnailProgress(libraryCount = 500, setSize = 500, savedCount = 500))))
        compose.onNodeWithTag("offline-photos-progress").performScrollTo()
            .assertTextEquals(app.getString(R.string.offline_photos_progress, "500", "500"))
        compose.onNodeWithTag("offline-photos-status").assertDoesNotExist()
    }

    @Test fun aBudgetTooSmallSuggestsRaisingIt() {
        val budgets = mutableListOf<ThumbnailBudget>()
        val status = OfflinePhotosStatus(OfflineThumbnailProgress(libraryCount = 22_000, setSize = 15_873, savedCount = 15_873),
            suggestedBudget = ThumbnailBudget.GB_2)
        show(PhoneStorageState(offlinePhotos = status), budgets)

        compose.onNodeWithTag("offline-photos-suggestion").performScrollTo()
            .assertTextEquals(app.getString(R.string.offline_photos_needs, Formatter.formatShortFileSize(app, status.libraryBytes)))
        compose.onNodeWithTag("offline-photos-raise").assert(hasText(app.getString(R.string.offline_photos_raise, text(R.string.storage_limit_2_gb))))
            .performClick()
        compose.runOnIdle { assertEquals(listOf(ThumbnailBudget.GB_2), budgets) }
    }

    @Test fun theBudgetAndTheCacheLimitAreChosenInTheirOwnDialogs() {
        val budgets = mutableListOf<ThumbnailBudget>()
        val limits = mutableListOf<CacheLimit>()
        show(PhoneStorageState(usage = LocalMediaStorage(0, 0, 0, 8_000_000_000, cacheBytes = 120_000_000)), budgets, limits)

        compose.onNodeWithTag("media-cache-usage").performScrollTo()
            .assertTextEquals(app.getString(R.string.media_cache_usage, Formatter.formatShortFileSize(app, 120_000_000)))
        compose.onNodeWithTag("offline-photos-setting").performScrollTo().performClick()
        compose.onNodeWithTag("offline-photos-GB_1").assertIsSelected()
        listOf("GB_2", "GB_4", "NO_LIMIT").forEach { compose.onNodeWithTag("offline-photos-$it").assertIsNotSelected() }
        // Choosing the current one changes nothing.
        compose.onNodeWithTag("offline-photos-GB_1").performClick()
        compose.onNodeWithTag("offline-photos-options").assertDoesNotExist()
        compose.onNodeWithTag("offline-photos-setting").performClick()
        compose.onNodeWithTag("offline-photos-NO_LIMIT").assert(hasText(text(R.string.storage_limit_none))).performClick()
        compose.runOnIdle { assertEquals(listOf(ThumbnailBudget.NO_LIMIT), budgets) }

        compose.onNodeWithTag("media-cache-setting").performScrollTo()
            .assert(hasText(text(R.string.storage_limit_500_mb))).performClick()
        compose.onNodeWithTag("media-cache-MB_500").assertIsSelected()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.onNodeWithTag("media-cache-options").assertDoesNotExist()
        compose.onNodeWithTag("media-cache-setting").performClick()
        compose.onNodeWithTag("media-cache-GB_2").performClick()
        compose.runOnIdle {
            assertEquals(listOf(CacheLimit.GB_2), limits)
            assertEquals(listOf(ThumbnailBudget.NO_LIMIT), budgets)
        }
    }
}
