package dev.mela.app

import dev.mela.engine.model.GalleryMedia
import dev.mela.engine.model.MediaAvailability
import dev.mela.engine.model.MediaOrigin
import dev.mela.engine.model.TransferId
import dev.mela.engine.model.TransferView
import dev.mela.engine.model.TransferViewState
import dev.mela.protocol.account.ICloudAccountState
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryProjectionTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun `added dates determine sections and month offsets include headers`() {
        val old = media("a", MediaOrigin.ICLOUD, "2020-01-01T00:00:00Z")
            .copy(addedAtEpochMillis = Instant.parse("2026-09-22T00:00:00Z").toEpochMilli())
        val fallback = media("b", MediaOrigin.DEVICE, "2026-08-01T00:00:00Z")
        val sections = buildGallerySections(listOf(old, fallback), utc, dev.mela.engine.model.GalleryDate.ADDED)
        assertEquals(listOf(java.time.YearMonth.of(2026, 9), java.time.YearMonth.of(2026, 8)), sections.map { it.month })
        assertEquals(listOf(0, 2), dev.mela.app.ui.galleryMonthOffsets(sections, false))
        assertEquals(listOf(1, 3), dev.mela.app.ui.galleryMonthOffsets(sections, true))
        assertEquals(listOf(1, 2), dev.mela.app.ui.galleryMonthOffsets(sections, false, days = true, leadingItems = 1, showDateHeaders = false))
        assertEquals(1, dev.mela.app.ui.galleryPosition(sections, false, true, dev.mela.engine.model.GalleryDate.CAPTURED, sections.first().items.first().id, leadingItems = 1, showDateHeaders = false))
        assertEquals(listOf(2, 4), dev.mela.app.ui.galleryMonthOffsets(sections, true, leadingItems = 1))
        assertEquals(3, dev.mela.app.ui.galleryPosition(sections, true, false, dev.mela.engine.model.GalleryDate.CAPTURED, sections.first().items.first().id, leadingItems = 1))
    }

    @Test
    fun `projection preserves account access filter and selection semantics`() {
        val fixture = media("fixture:1", MediaOrigin.ICLOUD, "2026-01-03T00:00:00Z")
        val live = media("icloud:account:1", MediaOrigin.ICLOUD, "2026-02-03T00:00:00Z")
        val device = media("device:external:1", MediaOrigin.DEVICE, "2026-01-04T00:00:00Z")

        val signedIn = projectGalleryContent(
            allItems = listOf(live, device, fixture),
            filter = GalleryFilter.ALL,
            selectedId = device.id,
            hasDeviceAccess = false,
            accountState = ICloudAccountState.SignedIn("person@example.com"),
            zoneId = utc,
        )

        assertEquals(listOf(live), signedIn.items)
        assertEquals(listOf(live), signedIn.catalogItems)
        assertEquals(1, signedIn.cloudCount)
        assertEquals(0, signedIn.deviceCount)
        assertNull(signedIn.selectedMedia)

        val demo = projectGalleryContent(
            allItems = listOf(live, device, fixture),
            filter = GalleryFilter.DEVICE,
            selectedId = fixture.id,
            hasDeviceAccess = true,
            accountState = ICloudAccountState.Demo,
            zoneId = utc,
        )

        assertEquals(listOf(device), demo.items)
        assertEquals(listOf(device, fixture), demo.catalogItems)
        assertEquals(fixture, demo.selectedMedia)
        assertEquals(1, demo.cloudCount)
        assertEquals(1, demo.deviceCount)
        assertEquals(1, demo.sections.size)
        assertEquals(device, demo.sections.single().items.single())
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
