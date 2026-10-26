package me.lvca.alba.app.gallery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.R
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.testing.setAlbaContent
import me.lvca.alba.engine.model.CatalogScanProgress
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The first read of a large library says how far it is, in the empty library and in the header. */
@RunWith(AndroidJUnit4::class)
class CatalogScanProgressTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun aFirstReadShowsHowManyPhotosHaveLoadedOutOfTheTotal() {
        compose.setAlbaContent(GalleryState(), SessionState(accountState = ICloudAccountState.SignedIn("alex@example.com"),
            isRefreshing = true, catalogScan = CatalogScanProgress(loadedCount = 12_400, totalCount = 52_000)))

        compose.onNodeWithTag("catalog-scan-progress").performScrollTo().assertTextEquals("12,400 of 52,000 photos loaded")
        compose.onNodeWithTag("catalog-scan-note").assertTextEquals("This can take a while for large libraries.").assertIsDisplayed()
        // The header's status line carries the same count, above a bar that fills as pages land.
        assertEquals(2, compose.onAllNodesWithText("12,400 of 52,000 photos loaded").fetchSemanticsNodes().size)
        compose.onNodeWithTag("catalog-scan-bar").assertExists()
        compose.onNodeWithText(context.getString(R.string.updating_library)).assertDoesNotExist()
    }

    @Test
    fun withoutATotalOnlyTheLoadedCountIsShown() {
        compose.setAlbaContent(GalleryState(), SessionState(accountState = ICloudAccountState.SignedIn("alex@example.com"),
            isRefreshing = true, catalogScan = CatalogScanProgress(loadedCount = 1)))

        compose.onNodeWithTag("catalog-scan-progress").performScrollTo().assertTextEquals("1 photo loaded")
        compose.onNodeWithTag("catalog-scan-bar").assertDoesNotExist()
    }

    @Test
    fun aRefreshBeforeTheReadStartsSaysPhotosAreLoadingAndThatItCanTakeAWhile() {
        compose.setAlbaContent(GalleryState(), SessionState(accountState = ICloudAccountState.SignedIn("alex@example.com"), isRefreshing = true))

        compose.onNodeWithTag("catalog-scan-progress").performScrollTo().assertTextEquals(context.getString(R.string.loading_photos))
        compose.onNodeWithTag("catalog-scan-note").assertIsDisplayed()
    }

    @Test
    fun anExpiredSignInPausesTheReadAndAsksToSignInAgain() {
        compose.setAlbaContent(GalleryState(), SessionState(
            accountState = ICloudAccountState.SignedIn("alex@example.com", SessionStatus.EXPIRED),
            isRefreshing = false, catalogScan = CatalogScanProgress(loadedCount = 3_000, totalCount = 60_000)))

        compose.onNodeWithText(context.getString(R.string.sign_in_again_saved_photos_available)).assertIsDisplayed()
        // The paused read keeps its count in the empty library, without a spinner suggesting it is still running.
        compose.onNodeWithTag("catalog-scan-progress").performScrollTo().assertTextEquals("3,000 of 60,000 photos loaded")
        assertEquals(1, compose.onAllNodesWithText("3,000 of 60,000 photos loaded").fetchSemanticsNodes().size)
    }
}
