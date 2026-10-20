package me.lvca.alba.app.gallery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.testing.setAlbaContent

class PhoneAccessTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun optionalPhoneSuggestionCanBeDismissedWithoutLosingPermissionEntry() {
        var requests = 0
        compose.setAlbaContent(GalleryState(), SessionState(accountState = ICloudAccountState.Demo, isRefreshing = false), onPhotos = { requests++ })
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Include photos from this phone").assertDoesNotExist()
        compose.onNodeWithTag("gallery-menu-button").performClick()
        compose.onNodeWithText("Choose phone photos").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun offlineStatusAndLimitedPhotoReselectionStayAccessible() {
        var requests = 0
        compose.setAlbaContent(GalleryState(deviceMediaAccess = true, limitedPhotoAccess = true),
            SessionState(accountState = ICloudAccountState.SignedIn("owner@example.com", me.lvca.alba.protocol.account.SessionStatus.OFFLINE), isRefreshing = false),
            onPhotos = { requests++ })
        compose.onNodeWithText("Offline · saved photos available").assertIsDisplayed()
        compose.onNodeWithTag("gallery-menu-button").performClick()
        compose.onNodeWithText("Change selected phone photos").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }
}
