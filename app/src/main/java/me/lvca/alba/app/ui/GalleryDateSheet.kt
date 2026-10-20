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
import me.lvca.alba.app.ui.theme.AlbaSpacing
import me.lvca.alba.engine.model.GalleryDate
import androidx.compose.ui.Alignment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryDateSheet(sections: List<GallerySection>, firstVisible: Int, permissionHeader: Boolean,
    days: Boolean = false, date: GalleryDate = GalleryDate.CAPTURED, leadingItems: Int = 0, showDateHeaders: Boolean = true, dismiss: () -> Unit, jump: (Int) -> Unit) {
    val offsets = remember(sections, permissionHeader, days, date, leadingItems, showDateHeaders) { galleryMonthOffsets(sections, permissionHeader, days, date, leadingItems, showDateHeaders) }
    val currentMonth = offsets.indexOfLast { it <= firstVisible }.coerceAtLeast(0)
    val monthList = rememberLazyListState(initialFirstVisibleItemIndex = currentMonth)
    AlbaBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AlbaSpacing.xl)) {
            AlbaSheetHeading(stringResource(R.string.browse_by_date), dismiss = dismiss)
            if (sections.isNotEmpty()) {
                Spacer(Modifier.height(AlbaSpacing.md))
                LazyColumn(state = monthList, contentPadding = PaddingValues(bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()), modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 384.dp).testTag("date-months")) {
                    itemsIndexed(sections, key = { _, item -> item.month.toString() }) { index, section ->
                        AlbaCard(Modifier.padding(bottom = AlbaSpacing.sm), onClick = { jump(offsets[index]); dismiss() }) {
                            Row(Modifier.padding(AlbaSpacing.lg), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AlbaSpacing.md)) {
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
