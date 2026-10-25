package me.lvca.alba.app.gallery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.testing.setAlbaContent
import me.lvca.alba.engine.model.GalleryQuery
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmptyLibraryTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unconnectedEmptyLibraryOffersConnectingAndTheDemoInsteadOfClearingFilters() {
        var connects = 0
        var demos = 0
        compose.setAlbaContent(GalleryState(), SessionState(accountState = ICloudAccountState.SignedOut, isRefreshing = false),
            onOpenAccount = { connects++ }, onStartDemo = { demos++ })
        compose.onNodeWithText("Connect iCloud to see your photos").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Clear filters").assertDoesNotExist()
        compose.onNodeWithText("No photos in this view").assertDoesNotExist()
        compose.onNodeWithText("0 items").assertDoesNotExist()
        // The header's small status link is replaced by the empty state's primary action.
        compose.onNodeWithText("Connect to iCloud").assertDoesNotExist()
        // On short screens the phone-photo prompt pushes the buttons under the floating navigation.
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithTag("empty-connect-button").assertIsDisplayed().performClick()
        compose.onNodeWithTag("empty-demo-button").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, connects); assertEquals(1, demos) }
    }

    @Test
    fun clearFiltersAppearsOnlyWhenAFilterIsSet() {
        compose.setAlbaContent(GalleryState(), SessionState(accountState = ICloudAccountState.Demo, isRefreshing = false))
        compose.onNodeWithText("No photos in this view").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Clear filters").assertDoesNotExist()
    }

    @Test
    fun filteredEmptyViewOffersClearingFilters() {
        compose.setAlbaContent(GalleryState(query = GalleryQuery(origin = MediaOrigin.DEVICE)),
            SessionState(accountState = ICloudAccountState.SignedOut, isRefreshing = false))
        compose.onNodeWithText("No photos in this view").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Clear filters").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("empty-connect").assertDoesNotExist()
    }
}
