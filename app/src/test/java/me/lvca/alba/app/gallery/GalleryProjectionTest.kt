package me.lvca.alba.app.gallery

import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.GalleryQuery
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class GalleryProjectionTest {
    private val utc = ZoneId.of("UTC")

    @Test fun signedOutAndAuthenticatingHideCachedDemoMedia() {
        val fixture = media("fixture:1", MediaOrigin.ICLOUD, "2026-01-03T00:00:00Z")
        val device = media("device:1", MediaOrigin.DEVICE, "2026-01-03T00:00:00Z")
        listOf(ICloudAccountState.SignedOut, ICloudAccountState.SigningIn("test@example.test"),
            ICloudAccountState.AwaitingTwoFactor("test@example.test", me.lvca.alba.protocol.account.TwoFactorDelivery.TRUSTED_DEVICE)).forEach { state ->
            val projected = projectGalleryCatalog(listOf(fixture, device), true, state)
            assertEquals(listOf(device), projected.items)
            assertNull(projected.byId[fixture.id])
            assertEquals(0, projected.cloudCount)
        }
    }

    @Test
    fun `added dates determine sections and month offsets include headers`() {
        val old = media("a", MediaOrigin.ICLOUD, "2020-01-01T00:00:00Z")
            .copy(addedAtEpochMillis = Instant.parse("2026-09-22T00:00:00Z").toEpochMilli())
        val fallback = media("b", MediaOrigin.DEVICE, "2026-08-01T00:00:00Z")
        val sections = buildGallerySections(listOf(old, fallback), utc, me.lvca.alba.engine.model.GalleryDate.ADDED)
        assertEquals(listOf(java.time.YearMonth.of(2026, 9), java.time.YearMonth.of(2026, 8)), sections.map { it.month })
        assertEquals(listOf(0, 2), me.lvca.alba.app.ui.gallery.galleryMonthOffsets(sections, false))
        assertEquals(listOf(1, 3), me.lvca.alba.app.ui.gallery.galleryMonthOffsets(sections, true))
        assertEquals(listOf(1, 2), me.lvca.alba.app.ui.gallery.galleryMonthOffsets(sections, false, days = true, leadingItems = 1, showDateHeaders = false))
        assertEquals(1, me.lvca.alba.app.ui.gallery.galleryPosition(sections, false, true, me.lvca.alba.engine.model.GalleryDate.CAPTURED, sections.first().items.first().id, leadingItems = 1, showDateHeaders = false))
        assertEquals(listOf(2, 4), me.lvca.alba.app.ui.gallery.galleryMonthOffsets(sections, true, leadingItems = 1))
        assertEquals(3, me.lvca.alba.app.ui.gallery.galleryPosition(sections, true, false, me.lvca.alba.engine.model.GalleryDate.CAPTURED, sections.first().items.first().id, leadingItems = 1))
    }

    @Test
    fun `projection preserves account access, origin query and open photo semantics`() {
        val fixture = media("fixture:1", MediaOrigin.ICLOUD, "2026-01-03T00:00:00Z")
        val live = media("icloud:account:1", MediaOrigin.ICLOUD, "2026-02-03T00:00:00Z")
        val device = media("device:external:1", MediaOrigin.DEVICE, "2026-01-04T00:00:00Z")

        val signedIn = GalleryProjection().apply(
            allItems = listOf(live, device, fixture),
            query = GalleryQuery(),
            hasDeviceAccess = false,
            accountState = ICloudAccountState.SignedIn("person@example.com"),
            zone = utc,
        )

        assertEquals(listOf(live), signedIn.items)
        assertEquals(listOf(live), signedIn.catalog.items)
        assertEquals(1, signedIn.catalog.cloudCount)
        assertEquals(0, signedIn.catalog.deviceCount)
        assertNull(signedIn.catalog.byId[device.id])

        val demo = GalleryProjection().apply(
            allItems = listOf(live, device, fixture),
            query = GalleryQuery(origin = MediaOrigin.DEVICE),
            hasDeviceAccess = true,
            accountState = ICloudAccountState.Demo,
            zone = utc,
        )

        assertEquals(listOf(device), demo.items)
        assertEquals(listOf(device, fixture), demo.catalog.items)
        assertEquals(fixture, demo.catalog.byId[fixture.id])
        assertEquals(1, demo.catalog.cloudCount)
        assertEquals(1, demo.catalog.deviceCount)
        assertEquals(1, demo.sections.size)
        assertEquals(device, demo.sections.single().items.single())
    }

    @Test
    fun `phone folders become collections once, without trashed photos`() {
        val camera = media("device:1", MediaOrigin.DEVICE, "2026-01-04T00:00:00Z").copy(deviceFolderId = "device-folder:1", deviceFolderName = "Camera")
        val again = media("device:2", MediaOrigin.DEVICE, "2026-01-05T00:00:00Z").copy(deviceFolderId = "device-folder:1", deviceFolderName = "Camera")
        val trashed = media("device:3", MediaOrigin.DEVICE, "2026-01-06T00:00:00Z").copy(deviceFolderId = "device-folder:2", isTrashed = true)
        val catalog = projectGalleryCatalog(listOf(camera, again, trashed), true, ICloudAccountState.Demo)
        assertEquals(listOf(me.lvca.alba.engine.model.GalleryCollection("device-folder:1", "Camera")), catalog.deviceFolders)

        val album = me.lvca.alba.engine.model.GalleryCollection("album", "Trip")
        val demoAlbum = me.lvca.alba.engine.model.GalleryCollection("shared:demo-library:1", "Demo")
        assertEquals(listOf(album) + catalog.deviceFolders,
            visibleCollections(listOf(album, demoAlbum), ICloudAccountState.SignedIn("person@example.com"), catalog.deviceFolders))
        assertEquals(catalog.deviceFolders, visibleCollections(listOf(album), ICloudAccountState.SignedOut, catalog.deviceFolders))
    }

    @Test
    fun `transfer projection keeps the newest row for each media id`() {
        val newest = transfer("new", "device:1", 3)
        val older = transfer("old", "device:1", 2)
        val picker = transfer("picker", null, 1)

        val projected = projectTransfers(listOf(newest, older, picker))

        assertEquals(newest, projected.byMediaId["device:1"])
        assertEquals(1, projected.byMediaId.size)
        assertEquals(listOf(newest, older, picker), projected.queue)
    }

    /**
     * iCloud.com lists Recently Deleted soonest-to-expire first (the fewest days left at the top), in one flat grid with
     * no month sections.
     */
    @Test fun recentlyDeletedIsOneFlatGridSoonestToExpireFirst() {
        val day = 86_400_000L
        val deleted = { id: String, captured: String, expires: Long? ->
            media(id, MediaOrigin.ICLOUD, captured).copy(isTrashed = true, expiresAtEpochMillis = expires)
        }
        // Expiry is deletion + 30 days, so "old-capture" (expiring first) was deleted longest ago, whatever its capture date.
        val items = listOf(deleted("old-capture", "2019-05-01T00:00:00Z", 30 * day), deleted("recent", "2024-01-01T00:00:00Z", 40 * day),
            deleted("unknown", "2026-01-01T00:00:00Z", null), deleted("middle", "2026-03-01T00:00:00Z", 35 * day),
            media("library", MediaOrigin.ICLOUD, "2026-04-01T00:00:00Z"))
        val projected = GalleryProjection().apply(items, GalleryQuery(origin = MediaOrigin.ICLOUD, trashOnly = true), true,
            ICloudAccountState.SignedIn("person@example.com"), utc)
        assertEquals(listOf("old-capture", "middle", "recent", "unknown"), projected.items.map { it.id })
        assertEquals(1, projected.sections.size)
        assertEquals(projected.items, projected.sections.single().items)
        // The library keeps its month sections.
        val library = GalleryProjection().apply(items + media("other", MediaOrigin.ICLOUD, "2025-04-01T00:00:00Z"), GalleryQuery(), true,
            ICloudAccountState.SignedIn("person@example.com"), utc)
        assertEquals(2, library.sections.size)
    }

    /** Matched: in iCloud from another device, found by backup's existing-copy rule. Unmatched: badged. */
    @Test fun phoneItemsMatchingAnICloudOriginalAreNotPhoneOnly() {
        val at = "2026-01-01T10:00:00Z"
        fun phone(id: String, name: String, bytes: Long?, captured: String = at) =
            media(id, MediaOrigin.DEVICE, captured).copy(fileName = name, byteCount = bytes)
        fun cloud(id: String, name: String, bytes: Long, captured: String = at) =
            media("icloud:a:$id", MediaOrigin.ICLOUD, captured).copy(fileName = name, byteCount = bytes)
        // Reached iCloud from an iPhone: same size and name, no upload by Alba.
        val fromIPhone = phone("device:1", "IMG_0001.HEIC", 2_000_000)
        // Same size and capture time (within backup's 2 s), renamed on the way.
        val renamed = phone("device:2", "PXL_0002.jpg", 3_000_000, "2026-01-01T10:00:01Z")
        // Same name and time but another size: a different file, so not in iCloud.
        val edited = phone("device:3", "IMG_0003.HEIC", 4_000_001)
        val unmatched = phone("device:4", "IMG_0004.HEIC", 5_000_000, "2025-06-01T00:00:00Z")
        // A size Alba doesn't know can't be matched, so it is never badged.
        val unknownSize = phone("device:5", "IMG_0005.HEIC", null)
        val inPhoneTrash = phone("device:6", "IMG_0006.HEIC", 6_000_000).copy(isTrashed = true)
        val all = listOf(fromIPhone, renamed, edited, unmatched, unknownSize, inPhoneTrash,
            cloud("1", "IMG_0001.HEIC", 2_000_000, "2026-01-01T09:00:00Z"), cloud("2", "IMG_2002.JPG", 3_000_000),
            cloud("3", "IMG_0003.HEIC", 4_000_000))
        val signedIn = ICloudAccountState.SignedIn("person@example.com")
        assertEquals(setOf(edited.id, unmatched.id), projectGalleryCatalog(all, true, signedIn).phoneOnly)
        // Without an iCloud library there is nothing to be backed up to.
        assertEquals(emptySet<String>(), projectGalleryCatalog(all, true, ICloudAccountState.SignedOut).phoneOnly)
    }

    /** A verified upload is in iCloud through its backup link even when nothing else about the copy matches. */
    @Test fun verifiedBackupCopiesCountWhileInTheLibraryOrHidden() {
        fun phone(id: String) = media(id, MediaOrigin.DEVICE, "2026-01-01T00:00:00Z").copy(fileName = "$id.jpg", byteCount = 1_000)
        fun copy(id: String, of: String) = media(id, MediaOrigin.ICLOUD, "2027-01-01T00:00:00Z").copy(linkedDeviceId = of, byteCount = 9)
        val all = listOf(phone("device:1"), phone("device:2"), phone("device:3"),
            copy("icloud:a:1", "device:1"), copy("icloud:a:2", "device:2").copy(isHidden = true),
            copy("icloud:a:3", "device:3").copy(isTrashed = true))
        assertEquals(setOf("device:3"), projectGalleryCatalog(all, true, ICloudAccountState.SignedIn("person@example.com")).phoneOnly)
    }

    @Test fun phoneOnlyAnswersAreCachedUntilTheICloudLibraryChanges() {
        val index = PhoneOnlyIndex()
        val signedIn = ICloudAccountState.SignedIn("person@example.com")
        val phone = media("device:1", MediaOrigin.DEVICE, "2026-01-01T00:00:00Z").copy(fileName = "IMG_1.HEIC", byteCount = 7)
        assertEquals(setOf(phone.id), index.apply(listOf(phone), signedIn))
        // A thumbnail arriving changes the item but not its keys; the answer stays.
        assertEquals(setOf(phone.id), index.apply(listOf(phone.copy(previewReference = "/thumb")), signedIn))
        // Its copy arriving in iCloud clears the cache and the badge.
        val copy = media("icloud:a:1", MediaOrigin.ICLOUD, "2026-01-01T00:00:00Z").copy(fileName = "IMG_1.HEIC", byteCount = 7)
        assertEquals(emptySet<String>(), index.apply(listOf(phone, copy), signedIn))
    }

    private fun media(id: String, origin: MediaOrigin, capturedAt: String) = GalleryMedia(
        id = id,
        fileName = "$id.jpg",
        capturedAtEpochMillis = Instant.parse(capturedAt).toEpochMilli(),
        width = 100,
        height = 100,
        origin = origin,
        availability = if (origin == MediaOrigin.DEVICE) {
            MediaAvailability.DEVICE_ORIGINAL
        } else {
            MediaAvailability.CLOUD_ONLY
        },
        previewReference = null,
        originalReference = null,
        accentStartArgb = 0,
        accentEndArgb = 0,
    )

    private fun transfer(id: String, mediaId: String?, updatedAt: Long) = TransferView(
        id = TransferId(id),
        mediaId = mediaId,
        displayName = "$id.jpg",
        state = TransferViewState.WAITING,
        byteCount = 1,
        updatedAtEpochMillis = updatedAt,
    )
}
