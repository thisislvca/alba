package dev.mela.app.ui

import androidx.compose.ui.res.stringResource
import dev.mela.app.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.mela.app.GallerySection

/** Indexes include the month headers, so a jump lands at a date instead of an arbitrary photo. */
internal fun galleryMonthOffsets(sections: List<GallerySection>, permissionHeader: Boolean, days: Boolean = false, date: dev.mela.engine.model.GalleryDate = dev.mela.engine.model.GalleryDate.CAPTURED, leadingItems: Int = 0, showDateHeaders: Boolean = true): List<Int> {
    var offset = (if (permissionHeader) 1 else 0) + leadingItems
    return sections.map { section -> offset.also { offset += section.items.size + (if (showDateHeaders) 1 else 0) + if (days && showDateHeaders) section.items.map { galleryDay(it, date) }.distinct().size else 0 } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryDateSheet(sections: List<GallerySection>, firstVisible: Int, permissionHeader: Boolean,
    days: Boolean = false, date: dev.mela.engine.model.GalleryDate = dev.mela.engine.model.GalleryDate.CAPTURED, leadingItems: Int = 0, showDateHeaders: Boolean = true, dismiss: () -> Unit, jump: (Int) -> Unit) {
    val offsets = remember(sections, permissionHeader, days, date, leadingItems, showDateHeaders) { galleryMonthOffsets(sections, permissionHeader, days, date, leadingItems, showDateHeaders) }
    val currentMonth = offsets.indexOfLast { it <= firstVisible }.coerceAtLeast(0)
    val monthList = rememberLazyListState(initialFirstVisibleItemIndex = currentMonth)
    MelaBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            MelaSheetHeading(stringResource(R.string.browse_by_date), dismiss = dismiss)
            if (sections.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                LazyColumn(state = monthList, contentPadding = PaddingValues(bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()), modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 384.dp).testTag("date-months")) {
                    itemsIndexed(sections, key = { _, item -> item.month.toString() }) { index, section ->
                        Surface(onClick = { jump(offsets[index]); dismiss() }, color = melaGroupColor(), shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(monthLabel(section.month), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Text(itemCount(section.items.size), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun galleryDay(media: dev.mela.engine.model.GalleryMedia, date: dev.mela.engine.model.GalleryDate): java.time.LocalDate =
    java.time.Instant.ofEpochMilli(if (date == dev.mela.engine.model.GalleryDate.ADDED) media.addedAtEpochMillis ?: media.capturedAtEpochMillis else media.capturedAtEpochMillis)
        .atZone(java.time.ZoneId.systemDefault()).toLocalDate()

internal fun galleryPosition(sections: List<GallerySection>, permission: Boolean, days: Boolean,
    date: dev.mela.engine.model.GalleryDate, id: String, leadingItems: Int = 0, showDateHeaders: Boolean = true): Int {
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
