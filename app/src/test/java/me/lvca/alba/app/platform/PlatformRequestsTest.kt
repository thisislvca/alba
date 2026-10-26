package me.lvca.alba.app.platform

import android.Manifest
import me.lvca.alba.app.ui.gallery.trashDaysLeft
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaKind
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformRequestsTest {
    @Test fun phoneTrashCoversPhoneOriginalsAndLinkedCopies() {
        val phone = media("device:1", MediaOrigin.DEVICE, original = "content://media/1")
        val linked = media("icloud:1", MediaOrigin.ICLOUD, linked = "content://media/2")
        assertEquals(listOf("content://media/1", "content://media/2"), phoneTrashUris(listOf(phone, linked)))
    }

    @Test fun phoneTrashRefusesItemsWithoutAPhoneCopyRatherThanTrashingPart() {
        val phone = media("device:1", MediaOrigin.DEVICE, original = "content://media/1")
        val cloudOnly = media("icloud:1", MediaOrigin.ICLOUD)
        assertNull(phoneTrashUris(listOf(phone, cloudOnly)))
        // Two rows for one phone copy would trash fewer items than the user chose.
        assertNull(phoneTrashUris(listOf(phone, media("icloud:2", MediaOrigin.ICLOUD, linked = "content://media/1"))))
    }

    @Test fun phoneTrashIsLimitedToOneAndroidRequest() {
        val atLimit = List(PHONE_TRASH_LIMIT) { media("device:$it", MediaOrigin.DEVICE, original = "content://media/$it") }
        assertEquals(PHONE_TRASH_LIMIT, phoneTrashUris(atLimit)?.size)
        assertNull(phoneTrashUris(atLimit + media("device:x", MediaOrigin.DEVICE, original = "content://media/x")))
    }

    @Test fun exportProposesTheOriginalNameAndType() {
        val photo = media("icloud:1", MediaOrigin.ICLOUD).copy(fileName = "IMG_0001.HEIC", mimeType = "image/heic")
        assertEquals(ExportDocument("image/heic", "IMG_0001.HEIC"), ExportDocument.forMedia(photo, livePair = false))
    }

    @Test fun livePhotoExportIsOneZipNamedAfterTheStill() {
        val live = media("icloud:1", MediaOrigin.ICLOUD).copy(fileName = "IMG_0002.JPG", kind = MediaKind.LIVE_PHOTO)
        assertEquals(ExportDocument("application/zip", "IMG_0002-LivePhoto.zip"), ExportDocument.forMedia(live, livePair = true))
    }

    @Test fun exportNamesCannotEscapeTheChosenFolder() {
        assertEquals("evil.jpg", exportName(media("x", MediaOrigin.ICLOUD).copy(fileName = "../../evil.jpg")))
        assertEquals("Photo", exportName(media("x", MediaOrigin.ICLOUD).copy(fileName = "..")))
        assertEquals("a_b.jpg", exportName(media("x", MediaOrigin.ICLOUD).copy(fileName = "a\u0000b.jpg")))
        val live = media("x", MediaOrigin.ICLOUD).copy(fileName = "IMG_3.HEIC", motionMimeType = "video/mp4")
        assertEquals("IMG_3.mp4", exportName(live, motion = true))
        assertEquals("IMG_3.mov", exportName(live.copy(motionMimeType = "video/quicktime"), motion = true))
    }

    @Test fun uploadsAskForLocationAccessOnlyForRealAccounts() {
        val signedIn = ICloudAccountState.SignedIn("owner@example.test")
        assertTrue(uploadNeedsMediaLocationPermission(signedIn, granted = false))
        assertFalse(uploadNeedsMediaLocationPermission(signedIn, granted = true))
        assertFalse(uploadNeedsMediaLocationPermission(ICloudAccountState.Demo, granted = false))
    }

    @Test fun partialPhotoAccessIsDistinguishedFromFullAccess() {
        val partial = setOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertTrue(hasDevicePhotoAccess(34, partial::contains))
        assertTrue(hasLimitedPhotoAccess(34, partial::contains))
        val full = setOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        assertFalse(hasLimitedPhotoAccess(34, full::contains))
        assertFalse(hasDevicePhotoAccess(32, full::contains))
        assertTrue(hasDevicePhotoAccess(32, setOf(Manifest.permission.READ_EXTERNAL_STORAGE)::contains))
        assertEquals(listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED), requestedDevicePhotoPermissions(34))
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), requestedDevicePhotoPermissions(32))
    }

    @Test fun aDenialWithoutARationaleMeansAndroidStoppedAsking() {
        assertTrue(photoPromptBlocked(anyGranted = false, showRationale = false))
        // The first denial still lets Android ask again, and any grant clears the block.
        assertFalse(photoPromptBlocked(anyGranted = false, showRationale = true))
        assertFalse(photoPromptBlocked(anyGranted = true, showRationale = false))
    }

    @Test fun trashedItemsCountWholeDaysLeftRoundedUp() {
        val now = 1_800_000_000_000L
        val day = 86_400_000L
        val trashed = media("device:1", MediaOrigin.DEVICE).copy(isTrashed = true)
        assertEquals(30, trashDaysLeft(trashed.copy(expiresAtEpochMillis = now + 30 * day), now))
        assertEquals(3, trashDaysLeft(trashed.copy(expiresAtEpochMillis = now + 2 * day + 1), now))
        // The last hours, and an expiry already passed, still read "1 day" until the item is gone.
        assertEquals(1, trashDaysLeft(trashed.copy(expiresAtEpochMillis = now + 60_000), now))
        assertEquals(1, trashDaysLeft(trashed.copy(expiresAtEpochMillis = now - day), now))
        assertNull(trashDaysLeft(trashed, now))
        assertNull(trashDaysLeft(trashed.copy(isTrashed = false, expiresAtEpochMillis = now + day), now))
    }

    private fun media(id: String, origin: MediaOrigin, original: String? = null, linked: String? = null) = GalleryMedia(
        id, "$id.jpg", 0, 1, 1, origin,
        if (origin == MediaOrigin.DEVICE) MediaAvailability.DEVICE_ORIGINAL else MediaAvailability.CLOUD_ONLY,
        null, original, 0, 0,
    ).copy(linkedDeviceReference = linked)
}
