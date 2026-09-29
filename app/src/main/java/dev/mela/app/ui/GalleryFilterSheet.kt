package dev.mela.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.mela.app.GalleryUiState
import dev.mela.app.R
import dev.mela.engine.model.*
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryFilterSheet(state: GalleryUiState, change: (GalleryQuery) -> Unit, requestPhotos: () -> Unit,
    refresh: () -> Unit, density: Int, changeDensity: (Int) -> Unit, dismiss: () -> Unit) {
    var dates by rememberSaveable { mutableStateOf(false) }
    MelaBottomSheet(onDismissRequest = dismiss, maxHeightFraction = .82f) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("gallery-filter-sheet")) {
            MelaSheetHeading(stringResource(R.string.filter_and_sort), dismiss = dismiss)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("filter-options")) {
                FilterSectionLabel(stringResource(R.string.photo_size))
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(80 to R.string.small, 112 to R.string.medium, 160 to R.string.large).forEachIndexed { index, (size, label) ->
                        val selected = density == size
                        Surface(Modifier.weight(1f), color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .09f) else Color.Transparent,
                            shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.clip(RoundedCornerShape(12.dp)).selectable(selected, role = Role.RadioButton, onClick = { changeDensity(size) })
                                .padding(horizontal = 4.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                DensityGridIcon(4 - index, tint)
                                Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = tint)
                            }
                        }
                    }
                }
                FilterDivider()
                FilterSectionLabel(stringResource(R.string.show))
                Column(Modifier.selectableGroup()) {
                    FilterMenuRow(stringResource(R.string.all_items), state.query.collectionId == null,
                        { change(state.query.copy(collectionId = null)) }, icon = { DensityGridIcon(3, LocalContentColor.current) })
                    FilterMenuRow(stringResource(R.string.favorites), state.query.collectionId == GalleryQuery.FAVORITES,
                        { change(state.query.copy(collectionId = GalleryQuery.FAVORITES)) }, icon = { Icon(Icons.Filled.Favorite, null, Modifier.size(22.dp)) })
                    SmartCollection.entries.forEach { smart ->
                        FilterMenuRow(smart.localizedTitle(), state.query.collectionId == smart.id,
                            { change(state.query.copy(collectionId = smart.id)) }, icon = { Icon(painterResource(smart.icon()), null, Modifier.size(22.dp)) })
                    }
                }
                FilterDivider()
                FilterSectionLabel(stringResource(R.string.source))
                Column(Modifier.selectableGroup()) {
                    listOf(null to R.string.all_photos, MediaOrigin.ICLOUD to R.string.icloud, MediaOrigin.DEVICE to R.string.this_phone).forEach { (origin, label) ->
                        FilterMenuRow(stringResource(label), state.query.origin == origin, {
                            if (origin == MediaOrigin.DEVICE && !state.deviceMediaAccess) requestPhotos() else change(state.query.copy(origin = origin))
                        }, icon = { Icon(when (origin) { MediaOrigin.ICLOUD -> MelaIcons.CloudQueue; MediaOrigin.DEVICE -> MelaIcons.PhoneAndroid; else -> MelaIcons.PhotoLibrary }, null, Modifier.size(22.dp)) })
                    }
                }
                if (state.limitedPhotoAccess || !state.deviceMediaAccess) TextButton(onClick = requestPhotos) {
                    Text(stringResource(if (state.limitedPhotoAccess) R.string.change_selected_phone_photos else R.string.choose_phone_photos))
                }
                FilterDivider()
                FilterSectionLabel(stringResource(R.string.sort_by))
                Column(Modifier.selectableGroup()) {
                    GalleryDate.entries.forEach { date ->
                        FilterMenuRow(stringResource(if (date == GalleryDate.CAPTURED) R.string.captured else R.string.added), state.query.date == date,
                            { change(state.query.copy(date = date)) })
                    }
                }
                if (state.query.date == GalleryDate.ADDED) Text(stringResource(R.string.uses_the_capture_date_when_the_added_date_is_unavailable),
                    Modifier.padding(start = 36.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup()) {
                    GallerySort.entries.forEach { sort ->
                        FilterMenuRow(stringResource(if (sort == GallerySort.NEWEST) R.string.newest_first else R.string.oldest_first), state.query.sort == sort,
                            { change(state.query.copy(sort = sort)) })
                    }
                }
                TextButton(onClick = { dates = true }) { Text(if (state.query.fromDate == null && state.query.throughDate == null) stringResource(R.string.choose_date_range) else stringResource(R.string.range_dates, state.query.fromDate?.let { dateLabel(it) } ?: stringResource(R.string.any_date), state.query.throughDate?.let { dateLabel(it) } ?: stringResource(R.string.any_date))) }
                if (state.query.fromDate != null || state.query.throughDate != null) TextButton(onClick = { change(state.query.copy(fromDate = null, throughDate = null)) }) { Text(stringResource(R.string.clear_dates)) }
                FilterDivider()
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { change(GalleryQuery()) }) { Text(stringResource(R.string.reset_filters)) }
                    TextButton(onClick = { refresh(); dismiss() }) { Text(stringResource(R.string.refresh_library)) }
                }
                Spacer(Modifier.height(8.dp))
            }
            TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).navigationBarsPadding()) {
                Text(stringResource(R.string.show_photos))
            }
        }
    }
    if (dates) {
        val picker = rememberDateRangePickerState(
            initialSelectedStartDateMillis = state.query.fromDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            initialSelectedEndDateMillis = state.query.throughDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(onDismissRequest = { dates = false }, confirmButton = {
            TextButton(onClick = {
                fun date(value: Long?) = value?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                change(state.query.copy(fromDate = date(picker.selectedStartDateMillis), throughDate = date(picker.selectedEndDateMillis)))
                dates = false
            }) { Text(stringResource(R.string.apply)) }
        }, dismissButton = { TextButton(onClick = { dates = false }) { Text(stringResource(R.string.cancel)) } }) { DateRangePicker(picker, modifier = Modifier.height(450.dp)) }
    }
}

@Composable
private fun FilterSectionLabel(title: String) {
    Text(title, Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FilterDivider() {
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
}

@Composable
private fun FilterMenuRow(label: String, selected: Boolean, onClick: () -> Unit, icon: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).selectable(selected, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
        if (icon != null) CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { icon() }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DensityGridIcon(columns: Int, tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val gap = 2.dp.toPx()
        val cell = (size.width - gap * (columns - 1)) / columns
        repeat(columns) { row -> repeat(columns) { column ->
            drawRoundRect(tint, Offset(column * (cell + gap), row * (cell + gap)), Size(cell, cell), CornerRadius(1.dp.toPx()))
        } }
    }
}
