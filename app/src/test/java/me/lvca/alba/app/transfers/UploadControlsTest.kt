package me.lvca.alba.app.transfers

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.gallery.GalleryState
import me.lvca.alba.app.testing.openViewerDetails
import me.lvca.alba.app.testing.setAlbaContent
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
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadControlsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun verifiedPhonePhotoOffersSystemTrashOnlyAfterProof() {
        var trashRequested = false
        val media = deviceMedia()
        val transfer = transfer(media.id, TransferViewState.VERIFIED)

        compose.setAlbaContent(
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

        compose.setAlbaContent(
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
        compose.onNodeWithText("Skip this item").performClick()
        compose.runOnIdle {
            assertTrue(pickerRequested)
            assertEquals(unresolved.id, continued)
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
