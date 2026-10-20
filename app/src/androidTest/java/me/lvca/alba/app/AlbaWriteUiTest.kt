package me.lvca.alba.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import me.lvca.alba.app.ui.AlbaApp
import me.lvca.alba.app.ui.theme.AlbaTheme
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AlbaWriteUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun verifiedPhonePhotoOffersSystemTrashOnlyAfterProof() {
        var trashRequested = false
        val media = deviceMedia()
        val transfer = transfer(media.id, TransferViewState.VERIFIED)

        setAlbaContent(
            GalleryState(items = listOf(media), selectedMedia = media, deviceMediaAccess = true),
            SessionState(accountState = ICloudAccountState.SignedIn("owner@example.com"), isRefreshing = false),
            TransferState(queue = listOf(transfer), uploadsByMediaId = mapOf(media.id to transfer)),
            onTrash = { trashRequested = true },
        )

        compose.openViewerDetails()
        compose.onNodeWithText("Verified in iCloud").assertIsDisplayed()
        compose.onNodeWithTag("move-phone-original-to-trash").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(trashRequested) }
    }

    @Test
    fun pickerTransferAndSafeQueueReleaseAreVisible() {
        var pickerRequested = false
        var continued: TransferId? = null
        val unresolved = transfer(mediaId = null, state = TransferViewState.UNRESOLVED)

        setAlbaContent(
            GalleryState(deviceMediaAccess = true),
            SessionState(accountState = ICloudAccountState.SignedIn("owner@example.com"), isRefreshing = false),
            TransferState(queue = listOf(unresolved)),
            onPick = { pickerRequested = true },
            onContinue = { continued = it },
        )

        compose.onNodeWithTag("activity-button").performClick()
        compose.onNodeWithTag("upload-picker-button").assertIsDisplayed().performClick()
        compose.onNodeWithTag("transfer-queue").assertIsDisplayed()
        compose.onNodeWithText("selected.jpg").assertIsDisplayed()
        compose.onNodeWithText("Keep this item blocked; continue others").performClick()
        compose.runOnIdle {
            assertTrue(pickerRequested)
            assertEquals(unresolved.id, continued)
        }
    }

    @Test
    fun optionalPhoneSuggestionCanBeDismissedWithoutLosingPermissionEntry() {
        var requests = 0
        setAlbaContent(GalleryState(), SessionState(accountState = ICloudAccountState.Demo, isRefreshing = false), onPhotos = { requests++ })
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Include photos from this phone").assertDoesNotExist()
        compose.onNodeWithTag("gallery-menu-button").performClick()
        compose.onNodeWithText("Choose phone photos").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun offlineStatusAndLimitedPhotoReselectionStayAccessible() {
        var requests = 0
        setAlbaContent(GalleryState(deviceMediaAccess = true, limitedPhotoAccess = true),
            SessionState(accountState = ICloudAccountState.SignedIn("owner@example.com", me.lvca.alba.protocol.account.SessionStatus.OFFLINE), isRefreshing = false),
            onPhotos = { requests++ })
        compose.onNodeWithText("Offline · saved photos available").assertIsDisplayed()
        compose.onNodeWithTag("gallery-menu-button").performClick()
        compose.onNodeWithText("Change selected phone photos").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    private fun setAlbaContent(
        gallery: GalleryState,
        session: SessionState,
        transfers: TransferState = TransferState(),
        onPhotos: () -> Unit = {},
        onPick: () -> Unit = {},
        onTrash: (String) -> Unit = {},
        onContinue: (TransferId) -> Unit = {},
    ) {
        compose.setContent {
            AlbaTheme(darkTheme = false) {
                AlbaApp(
                    gallery = gallery,
                    session = session,
                    transfers = transfers,
                    phoneStorage = PhoneStorageState(),
                    mediaWork = MediaWork(),
                    previewRetryVersion = 0,
                    message = null,
                    diagnostics = DiagnosticsConsent(),
                    actions = testAlbaActions(requestDevicePhotos = onPhotos, pickUpload = onPick,
                        continuePastUnresolved = onContinue, moveVerifiedPhotoToTrash = onTrash),
                )
            }
        }
    }

    private fun deviceMedia() = GalleryMedia(
        id = "device:external:1",
        fileName = "camera.jpg",
        capturedAtEpochMillis = 1_000L,
        width = 20,
        height = 30,
        origin = MediaOrigin.DEVICE,
        availability = MediaAvailability.DEVICE_ORIGINAL,
        previewReference = null,
        originalReference = null,
        accentStartArgb = 0xff335533,
        accentEndArgb = 0xff112211,
    )

    private fun transfer(mediaId: String?, state: TransferViewState) = TransferView(
        id = TransferId("transfer-${mediaId ?: "picker"}"),
        mediaId = mediaId,
        displayName = if (mediaId == null) "selected.jpg" else "camera.jpg",
        state = state,
        byteCount = 8,
        updatedAtEpochMillis = 1_000L,
        message = state.name,
    )
}
