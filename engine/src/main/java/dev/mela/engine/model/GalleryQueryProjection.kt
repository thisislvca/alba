package dev.mela.engine.model

import java.time.ZoneId

/** Reuse matching and ordering when only downloaded file references changed. */
class GalleryQueryProjection {
    private var previous: List<GalleryMedia> = emptyList()
    private var previousQuery: GalleryQuery? = null
    private var previousZone: ZoneId? = null
    private var indices = emptyList<Int>()

    fun apply(items: List<GalleryMedia>, query: GalleryQuery, zone: ZoneId = ZoneId.systemDefault()): List<GalleryMedia> {
        val sameOrder = previousQuery == query && previousZone == zone && previous.size == items.size &&
            items.indices.all { sameMatch(previous[it], items[it], query.offlineOnly) }
        if (!sameOrder) {
            val byId = items.withIndex().associate { it.value.id to it.index }
            indices = query.apply(items, zone).map { byId.getValue(it.id) }
        }
        previous = items
        previousQuery = query
        previousZone = zone
        return indices.map(items::get)
    }

    private fun sameMatch(a: GalleryMedia, b: GalleryMedia, offlineOnly: Boolean) =
        a === b || (a.id == b.id && a.fileName == b.fileName && a.origin == b.origin &&
            a.capturedAtEpochMillis == b.capturedAtEpochMillis && a.addedAtEpochMillis == b.addedAtEpochMillis &&
            a.isTrashed == b.isTrashed && a.isFavorite == b.isFavorite && a.collectionIds == b.collectionIds &&
            (!offlineOnly || a.availability == b.availability))
}
