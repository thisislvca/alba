package me.lvca.alba.app.account

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.testAlbaActions
import me.lvca.alba.app.ui.account.PhoneStorageDetails
import me.lvca.alba.app.ui.designsystem.AlbaPageScaffold
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.engine.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhoneStorageTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }
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
}
