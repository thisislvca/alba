package dev.mela.engine.model

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class GalleryQueryTest {
    @Test fun largeLibraryKeepsOrderAndUpdatedFilesAcrossCacheChanges() {
        for (size in listOf(10_000, 50_000)) {
            val projection = GalleryQueryProjection()
            val base = item("base", "2026-01-01T00:00:00Z")
            val items = List(size) { index -> base.copy(id = "photo-$index",
                capturedAtEpochMillis = base.capturedAtEpochMillis + index,
                isFavorite = index % 5 == 0) }
            val query = GalleryQuery(collectionId = GalleryQuery.FAVORITES)
            val original = projection.apply(items, query, ZoneOffset.UTC)
            val changedIndex = size / 2
            val updated = items.toMutableList().apply {
                this[changedIndex] = this[changedIndex].copy(previewReference = "/preview",
                    originalReference = "/original", availability = MediaAvailability.ORIGINAL_CACHED)
            }
            val result = projection.apply(updated, query, ZoneOffset.UTC)
            assertEquals(original.map { it.id }, result.map { it.id })
            assertSame(updated[changedIndex], result.single { it.id == updated[changedIndex].id })
            assertSame(original.first(), result.first())
            assertEquals(query.copy(offlineOnly = true).apply(updated, ZoneOffset.UTC),
                projection.apply(updated, query.copy(offlineOnly = true), ZoneOffset.UTC))
        }
    }

    @Test fun cachedProjectionTracksFilesFiltersAndReorderedMetadata() {
        val projection = GalleryQueryProjection()
        val a = item("a", "2026-01-01T00:00:00Z")
        val b = item("b", "2026-02-01T00:00:00Z", true)
        val queries = listOf(GalleryQuery(), GalleryQuery(sort = GallerySort.OLDEST),
            GalleryQuery(offlineOnly = true), GalleryQuery(collectionId = GalleryQuery.FAVORITES))
        for (query in queries) {
            val snapshots = listOf(listOf(a, b), listOf(a.copy(previewReference = "/preview"), b),
                listOf(a.copy(availability = MediaAvailability.ORIGINAL_CACHED, originalReference = "/original"), b),
                listOf(b.copy(isFavorite = false), a.copy(capturedAtEpochMillis = b.capturedAtEpochMillis + 1)))
            for (items in snapshots) assertEquals(query.apply(items), projection.apply(items, query))
        }
    }

    private fun item(id: String, time: String, favorite: Boolean = false) = GalleryMedia(id, "Holiday_$id.JPG",
        Instant.parse(time).toEpochMilli(), 10, 10, MediaOrigin.ICLOUD, MediaAvailability.CLOUD_ONLY,
        null, null, 0, 0, isFavorite = favorite, collectionIds = setOf("album"))
    @Test fun `offline collection includes only complete originals and composes with favorites`() {
        val base = GalleryMedia("a", "one.jpg", 1, 10, 10, MediaOrigin.ICLOUD,
            MediaAvailability.ORIGINAL_CACHED, null, "/local/original", 0, 0, isFavorite = true)
        val items = listOf(base, base.copy(id = "b", availability = MediaAvailability.PREVIEW_CACHED),
            base.copy(id = "c", origin = MediaOrigin.DEVICE, availability = MediaAvailability.DEVICE_ORIGINAL),
            base.copy(id = "d", isFavorite = false))
        assertEquals(setOf("a", "d"), GalleryQuery(offlineOnly = true).apply(items).map { it.id }.toSet())
        assertEquals(listOf("a"), GalleryQuery(offlineOnly = true, collectionId = GalleryQuery.FAVORITES).apply(items).map { it.id })
    }

    @Test fun addedDateSortingAndRangesUseAddedTimeWithCaptureFallback() {
        val oldCapture = item("a", "2020-01-01T00:00:00Z").copy(addedAtEpochMillis = Instant.parse("2026-09-22T00:00:00Z").toEpochMilli())
        val recentCapture = item("b", "2026-09-21T00:00:00Z")
        val items = listOf(recentCapture, oldCapture)
        assertEquals(listOf("b", "a"), GalleryQuery().apply(items).map { it.id })
        assertEquals(listOf("a", "b"), GalleryQuery(date = GalleryDate.ADDED).apply(items).map { it.id })
        assertEquals(listOf("b", "a"), GalleryQuery(date = GalleryDate.ADDED, sort = GallerySort.OLDEST).apply(items).map { it.id })
        assertEquals(listOf("a"), GalleryQuery(date = GalleryDate.ADDED, fromDate = LocalDate.parse("2026-09-22"))
            .apply(items, ZoneOffset.UTC).map { it.id })
    }
    @Test fun filtersComposeAndDatesUseLocalCalendar() {
        val items = listOf(item("a", "2026-09-21T23:30:00Z", true), item("b", "2026-09-22T23:30:00Z"))
        val query = GalleryQuery(origin = MediaOrigin.ICLOUD, collectionId = GalleryQuery.FAVORITES, filename = "HOLIDAY",
            fromDate = LocalDate.parse("2026-09-22"), throughDate = LocalDate.parse("2026-09-22"))
        assertEquals(listOf("a"), query.apply(items, ZoneId.of("Europe/Rome")).map { it.id })
        assertTrue(query.copy(origin = MediaOrigin.DEVICE).apply(items).isEmpty())
        assertTrue(query.copy(filename = "absent").apply(items).isEmpty())
    }
    @Test fun sortIsStableForTiesAndAlbumsFilter() {
        val items = listOf(item("a", "2026-01-01T00:00:00Z"), item("b", "2026-01-01T00:00:00Z"))
        assertEquals(listOf("b", "a"), GalleryQuery(collectionId = "album").apply(items).map { it.id })
        assertEquals(listOf("a", "b"), GalleryQuery(sort = GallerySort.OLDEST).apply(items).map { it.id })
        assertTrue(GalleryQuery(collectionId = "other").apply(items).isEmpty())
    }
}
