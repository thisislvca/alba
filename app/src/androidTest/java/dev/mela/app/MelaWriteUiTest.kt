package dev.mela.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import dev.mela.app.ui.MelaApp
import dev.mela.app.ui.theme.MelaTheme
import dev.mela.engine.model.GalleryMedia
import dev.mela.engine.model.MediaAvailability
import dev.mela.engine.model.MediaOrigin
import dev.mela.engine.model.TransferId
import dev.mela.engine.model.TransferView
import dev.mela.engine.model.TransferViewState
import dev.mela.protocol.account.ICloudAccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MelaWriteUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun verifiedPhonePhotoOffersSystemTrashOnlyAfterProof() {
        var trashRequested = false
        val media = deviceMedia()
        val transfer = transfer(media.id, TransferViewState.VERIFIED)

        setMelaContent(
            GalleryUiState(
                items = listOf(media),
                selectedMedia = media,
                isRefreshing = false,
                deviceMediaAccess = true,
                accountState = ICloudAccountState.SignedIn("owner@example.com"),
                uploadTransfersByMediaId = mapOf(media.id to transfer),
                transferQueue = listOf(transfer),
            ),
            onTrash = { trashRequested = true },
        )

        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Verified in iCloud").assertIsDisplayed()
        compose.onNodeWithTag("move-phone-original-to-trash").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(trashRequested) }
    }

    @Test
    fun pickerTransferAndSafeQueueReleaseAreVisible() {
        var pickerRequested = false
        var continued: TransferId? = null
        val unresolved = transfer(mediaId = null, state = TransferViewState.UNRESOLVED)

        setMelaContent(
            GalleryUiState(
                isRefreshing = false,
                deviceMediaAccess = true,
                accountState = ICloudAccountState.SignedIn("owner@example.com"),
                transferQueue = listOf(unresolved),
            ),
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
        setMelaContent(GalleryUiState(isRefreshing = false, accountState = ICloudAccountState.Demo), onPhotos = { requests++ })
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Include photos from this phone").assertDoesNotExist()
        compose.onNodeWithTag("filter-button").performClick()
        compose.onNodeWithText("Choose phone photos").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun offlineStatusAndLimitedPhotoReselectionStayAccessible() {
        var requests = 0
        setMelaContent(GalleryUiState(isRefreshing = false, deviceMediaAccess = true, limitedPhotoAccess = true,
            accountState = ICloudAccountState.SignedIn("owner@example.com", dev.mela.protocol.account.SessionStatus.OFFLINE)), onPhotos = { requests++ })
        compose.onNodeWithText("Offline · saved photos available").assertIsDisplayed()
        compose.onNodeWithTag("filter-button").performClick()
        compose.onNodeWithText("Change selected phone photos").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    private fun setMelaContent(
        state: GalleryUiState,
        onPhotos: () -> Unit = {},
        onPick: () -> Unit = {},
        onTrash: (String) -> Unit = {},
        onContinue: (TransferId) -> Unit = {},
    ) {
        compose.setContent {
            MelaTheme(darkTheme = false) {
                MelaApp(
                    state = state,
                    onRefresh = {},
                    onSelectFilter = {},
                    onSelectMedia = {},
                    onCloseDetail = {},
                    onRequestPreview = {},
                    onRequestDevicePhotos = onPhotos,
                    onPickUpload = onPick,
                    onKeepOriginalOffline = {},
                    onRemoveCachedOriginal = {},
                    onRequestUpload = {},
                    onContinuePastUnresolved = onContinue,
                    onMoveVerifiedPhotoToTrash = onTrash,
                    onDismissMessage = {},
                    onOpenAccount = {},
                    onCloseAccount = {},
                    onSignIn = { _, _ -> },
                    onSubmitTwoFactor = {},
                    onResendTwoFactor = {},
                    onSignOut = {},
                    onEnableAutomaticBackup = {},
                    onDisableAutomaticBackup = {},
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
