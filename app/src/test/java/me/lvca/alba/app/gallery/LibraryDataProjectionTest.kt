package me.lvca.alba.app.gallery

import me.lvca.alba.app.ui.gallery.TileBadge
import me.lvca.alba.app.ui.gallery.matchingAlbums
import me.lvca.alba.app.ui.gallery.tileBadge
import me.lvca.alba.app.ui.viewer.captureTimeParts
import me.lvca.alba.app.ui.viewer.coordinatesLabel
import me.lvca.alba.app.ui.viewer.geoUri
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.GalleryQuery
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaDetails
import me.lvca.alba.engine.model.MediaKind
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.SmartCollection
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class LibraryDataProjectionTest {
    private val account = ICloudAccountState.SignedIn("test@example.test")

    @Test fun burstsStackOutsideRecentlyDeletedAndExpandOnRequest() {
        val items = listOf(photo("a", "2026-01-04"), photo("b", "2026-01-03", "burst"), photo("c", "2026-01-02", "burst"),
            photo("d", "2026-01-01", "burst"))
        val projection = GalleryProjection()
        val stacked = projection.apply(items, GalleryQuery(), true, account, ZoneOffset.UTC)
        assertEquals(listOf("icloud:x:a", "icloud:x:b"), stacked.items.map { it.id })
        assertEquals(3, stacked.items[1].burstCount)
        assertEquals(listOf("icloud:x:b", "icloud:x:c", "icloud:x:d"), stacked.catalog.bursts.members["burst"])
        val expanded = projection.apply(items, GalleryQuery(), true, account, ZoneOffset.UTC, expandedBursts = setOf("burst"))
        assertEquals(4, expanded.items.size)
        val trash = items.map { it.copy(isTrashed = true) }
        assertEquals(4, GalleryProjection().apply(trash, GalleryQuery(trashOnly = true), true, account, ZoneOffset.UTC).items.size)
    }

    @Test fun anActionOnAStackedBurstActsOnEveryPhotoInIt() {
        val items = listOf(photo("a", "2026-01-04"), photo("b", "2026-01-03", "burst"), photo("c", "2026-01-02", "burst"),
            photo("d", "2026-01-01", "burst"))
        val projected = GalleryProjection().apply(items, GalleryQuery(), true, account, ZoneOffset.UTC)
        val state = GalleryState(items = projected.items, bursts = projected.catalog.bursts)
        assertEquals(listOf("icloud:x:a", "icloud:x:b", "icloud:x:c", "icloud:x:d"),
            state.withBurstFrames(listOf("icloud:x:a", "icloud:x:b")))
        assertEquals(listOf("icloud:x:a"), state.withBurstFrames(listOf("icloud:x:a")))
        // Once the burst is opened, each photo acts on its own.
        val opened = GalleryProjection().apply(items, GalleryQuery(), true, account, ZoneOffset.UTC, expandedBursts = setOf("burst"))
        assertEquals(listOf("icloud:x:c"), GalleryState(items = opened.items, bursts = opened.catalog.bursts,
            expandedBursts = setOf("burst")).withBurstFrames(listOf("icloud:x:c")))
    }

    @Test fun aPhoneCopyOfAHiddenPhotoIsHiddenToo() {
        val hidden = photo("a", "2026-01-02").copy(isHidden = true, linkedDeviceId = "device:1")
        val phone = GalleryMedia("device:1", "a.jpg", hidden.capturedAtEpochMillis, 10, 10, MediaOrigin.DEVICE,
            MediaAvailability.DEVICE_ORIGINAL, "content://a", "content://a", 0, 0)
        val visible = GalleryProjection().apply(listOf(hidden, phone), GalleryQuery(), true, account, ZoneOffset.UTC).items
        assertTrue(visible.isEmpty())
        assertTrue(GalleryProjection().apply(listOf(hidden, phone), GalleryQuery(origin = MediaOrigin.DEVICE), true, account, ZoneOffset.UTC)
            .items.isEmpty())
    }

    @Test fun hiddenPhotosStayOutOfTheLibraryButFillHidden() {
        val items = listOf(photo("a", "2026-01-02"), photo("b", "2026-01-01").copy(isHidden = true))
        assertEquals(listOf("icloud:x:a"), GalleryProjection().apply(items, GalleryQuery(), true, account, ZoneOffset.UTC).items.map { it.id })
        assertEquals(listOf("icloud:x:b"), GalleryProjection().apply(items, GalleryQuery(collectionId = GalleryQuery.HIDDEN), true,
            account, ZoneOffset.UTC).items.map { it.id })
    }

    @Test fun tileBadgesFollowIcloudTypes() {
        val base = photo("a", "2026-01-01")
        assertEquals(TileBadge.BURST, tileBadge(base.copy(details = MediaDetails(burstId = "b"), burstCount = 3)))
        // An opened burst's photos, or a burst of one, carry no badge.
        assertNull(tileBadge(base.copy(details = MediaDetails(burstId = "b"))))
        assertEquals(TileBadge.PANORAMA, tileBadge(base.copy(collectionIds = setOf(SmartCollection.PANORAMAS.id))))
        assertEquals(TileBadge.SLO_MO, tileBadge(base.copy(kind = MediaKind.VIDEO, collectionIds = setOf(SmartCollection.SLO_MO.id))))
        assertEquals(TileBadge.TIME_LAPSE, tileBadge(base.copy(collectionIds = setOf(SmartCollection.TIME_LAPSE.id))))
        assertEquals(TileBadge.RAW, tileBadge(base.copy(mimeType = "image/x-adobe-dng")))
        assertNull(tileBadge(base))
    }

    @Test fun captureTimesUseTheRecordedZoneAndPlacesStayLocal() {
        val millis = Instant.parse("2026-07-20T19:48:00Z").toEpochMilli()
        assertEquals("Jul 20, 2026, 21:48" to "GMT+02:00", captureTimeParts(millis, 7200, Locale.US, true))
        assertEquals("Jul 20, 2026, 7:48 PM" to "GMT", captureTimeParts(millis, 0, Locale.US, false))
        assertEquals("Jul 20, 2026, 14:48" to "GMT-05:00",
            captureTimeParts(millis, null, Locale.US, true, deviceZone = ZoneOffset.ofHours(-5)))
        assertEquals("44.303600, -9.210100", coordinatesLabel(44.3036, -9.2101))
        assertEquals("geo:44.303600,-9.210100?q=44.303600,-9.210100", geoUri(44.3036, -9.2101))
    }

    @Test fun searchFindsAlbumsByName() {
        val albums = listOf(GalleryCollection("trips", "Summer trips"), GalleryCollection("folder", "Trips folder", isFolder = true),
            GalleryCollection(SmartCollection.VIDEOS.id, "Videos"), GalleryCollection("family", "Family"))
        assertEquals(listOf("trips"), matchingAlbums(albums, "trips").map { it.id })
        assertEquals(emptyList<String>(), matchingAlbums(albums, "videos").map { it.id })
    }

    private fun photo(id: String, day: String, burst: String? = null) = GalleryMedia("icloud:x:$id", "$id.jpg",
        Instant.parse("${day}T12:00:00Z").toEpochMilli(), 10, 10, MediaOrigin.ICLOUD, MediaAvailability.CLOUD_ONLY, null, null, 0, 0,
        details = MediaDetails(burstId = burst))
}
