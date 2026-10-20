package me.lvca.alba.app.ui

import me.lvca.alba.app.GallerySection
import me.lvca.alba.engine.model.GalleryDate
import me.lvca.alba.engine.model.GalleryMedia
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId

/** Indexes include the month headers, so a jump lands at a date instead of an arbitrary photo. */
internal fun galleryMonthOffsets(sections: List<GallerySection>, permissionHeader: Boolean, days: Boolean = false, date: GalleryDate = GalleryDate.CAPTURED, leadingItems: Int = 0, showDateHeaders: Boolean = true): List<Int> {
    var offset = (if (permissionHeader) 1 else 0) + leadingItems
    return sections.map { section -> offset.also { offset += section.items.size + (if (showDateHeaders) 1 else 0) + if (days && showDateHeaders) section.items.map { galleryDay(it, date) }.distinct().size else 0 } }
}

internal fun galleryDay(media: GalleryMedia, date: GalleryDate): LocalDate =
    Instant.ofEpochMilli(if (date == GalleryDate.ADDED) media.addedAtEpochMillis ?: media.capturedAtEpochMillis else media.capturedAtEpochMillis)
        .atZone(ZoneId.systemDefault()).toLocalDate()

internal fun galleryPosition(sections: List<GallerySection>, permission: Boolean, days: Boolean,
    date: GalleryDate, id: String, leadingItems: Int = 0, showDateHeaders: Boolean = true): Int {
    var index = (if (permission) 1 else 0) + leadingItems
    for (section in sections) {
        if (showDateHeaders) index++
        val groups = if (days && showDateHeaders) section.items.groupBy { galleryDay(it, date) } else mapOf(null to section.items)
        for ((day, items) in groups) {
            if (day != null) index++
            val position = items.indexOfFirst { it.id == id }
            if (position >= 0) return index + position
            index += items.size
        }
    }
    return 0
}
