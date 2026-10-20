package me.lvca.alba.app.ui

import androidx.compose.ui.res.stringResource
import me.lvca.alba.app.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.GallerySection
import me.lvca.alba.engine.model.GalleryDate
import androidx.compose.ui.Alignment
import me.lvca.alba.engine.model.GalleryMedia
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId

/** Indexes include the month headers, so a jump lands at a date instead of an arbitrary photo. */
internal fun galleryMonthOffsets(sections: List<GallerySection>, permissionHeader: Boolean, days: Boolean = false, date: GalleryDate = GalleryDate.CAPTURED, leadingItems: Int = 0, showDateHeaders: Boolean = true): List<Int> {
    var offset = (if (permissionHeader) 1 else 0) + leadingItems
    return sections.map { section -> offset.also { offset += section.items.size + (if (showDateHeaders) 1 else 0) + if (days && showDateHeaders) section.items.map { galleryDay(it, date) }.distinct().size else 0 } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryDateSheet(sections: List<GallerySection>, firstVisible: Int, permissionHeader: Boolean,
    days: Boolean = false, date: GalleryDate = GalleryDate.CAPTURED, leadingItems: Int = 0, showDateHeaders: Boolean = true, dismiss: () -> Unit, jump: (Int) -> Unit) {
    val offsets = remember(sections, permissionHeader, days, date, leadingItems, showDateHeaders) { galleryMonthOffsets(sections, permissionHeader, days, date, leadingItems, showDateHeaders) }
    val currentMonth = offsets.indexOfLast { it <= firstVisible }.coerceAtLeast(0)
    val monthList = rememberLazyListState(initialFirstVisibleItemIndex = currentMonth)
    AlbaBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            AlbaSheetHeading(stringResource(R.string.browse_by_date), dismiss = dismiss)
            if (sections.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                LazyColumn(state = monthList, contentPadding = PaddingValues(bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()), modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 384.dp).testTag("date-months")) {
                    itemsIndexed(sections, key = { _, item -> item.month.toString() }) { index, section ->
                        AlbaCard(Modifier.padding(bottom = 8.dp), onClick = { jump(offsets[index]); dismiss() }) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
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
