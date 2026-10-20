package me.lvca.alba.app.gallery

import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.GalleryQuery
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.protocol.account.ICloudAccountState
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
        assertEquals(listOf(0, 2), me.lvca.alba.app.ui.galleryMonthOffsets(sections, false))
        assertEquals(listOf(1, 3), me.lvca.alba.app.ui.galleryMonthOffsets(sections, true))
        assertEquals(listOf(1, 2), me.lvca.alba.app.ui.galleryMonthOffsets(sections, false, days = true, leadingItems = 1, showDateHeaders = false))
        assertEquals(1, me.lvca.alba.app.ui.galleryPosition(sections, false, true, me.lvca.alba.engine.model.GalleryDate.CAPTURED, sections.first().items.first().id, leadingItems = 1, showDateHeaders = false))
        assertEquals(listOf(2, 4), me.lvca.alba.app.ui.galleryMonthOffsets(sections, true, leadingItems = 1))
        assertEquals(3, me.lvca.alba.app.ui.galleryPosition(sections, true, false, me.lvca.alba.engine.model.GalleryDate.CAPTURED, sections.first().items.first().id, leadingItems = 1))
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
