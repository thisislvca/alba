package me.lvca.alba.app.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.editing.Adjustment
import me.lvca.alba.app.editing.Adjustments
import me.lvca.alba.app.editing.AutoEnhance
import me.lvca.alba.app.editing.ColorMatrices
import me.lvca.alba.app.editing.LookFilter
import me.lvca.alba.app.platform.PhotoCrop

internal val Adjustment.label: Int get() = when (this) {
    Adjustment.BRIGHTNESS -> R.string.adjust_brightness
    Adjustment.CONTRAST -> R.string.adjust_contrast
    Adjustment.HIGHLIGHTS -> R.string.adjust_highlights
    Adjustment.SHADOWS -> R.string.adjust_shadows
    Adjustment.SATURATION -> R.string.adjust_saturation
    Adjustment.WARMTH -> R.string.adjust_warmth
    Adjustment.TINT -> R.string.adjust_tint
    Adjustment.VIGNETTE -> R.string.adjust_vignette
    Adjustment.SHARPNESS -> R.string.adjust_sharpness
}

internal val LookFilter.label: Int get() = when (this) {
    LookFilter.NONE -> R.string.editor_original
    LookFilter.VIVID -> R.string.filter_vivid
    LookFilter.WARM -> R.string.filter_warm
    LookFilter.COOL -> R.string.filter_cool
    LookFilter.FILM -> R.string.filter_film
    LookFilter.FADE -> R.string.filter_fade
    LookFilter.PASTEL -> R.string.filter_pastel
    LookFilter.DRAMATIC -> R.string.filter_dramatic
    LookFilter.MONO -> R.string.filter_mono
    LookFilter.NOIR -> R.string.filter_noir
    LookFilter.SEPIA -> R.string.filter_sepia
}

/** One-tap auto-enhance. [enhanced] is null while the histogram is read. */
@Composable
internal fun SuggestionsPanel(edit: PhotoEditState, enhanced: Adjustments?, enabled: Boolean) {
    val adjustments = edit.current.recipe.adjustments
    val applied = enhanced != null && AutoEnhance.isApplied(adjustments, enhanced)
    Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = applied,
            enabled = enabled && enhanced != null && enhanced != Adjustments(),
            onClick = {
                val target = enhanced ?: return@FilterChip
                edit.adjust(if (applied) AutoEnhance.remove(adjustments) else AutoEnhance.applyTo(adjustments, target))
            },
            label = { Text(stringResource(R.string.editor_enhance)) },
            modifier = Modifier.testTag("suggestion-enhance"),
        )
        Text(stringResource(if (enhanced == Adjustments()) R.string.editor_enhance_nothing else R.string.editor_enhance_explanation),
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CropPanel(edit: PhotoEditState, preview: Bitmap?, enabled: Boolean, edges: List<String>, adjustmentLabels: List<Pair<String, String>>) {
    var adjustments by rememberSaveable { mutableStateOf(false) }
    val turns = edit.current.turns
    Column(Modifier.fillMaxWidth()) {
        // Explicit presets also make cropping usable without precise drag gestures.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(enabled = enabled && edit.current.hasCropOrRotation, onClick = edit::resetCrop, modifier = Modifier.testTag("reset-crop")) {
                Text(stringResource(R.string.reset))
            }
            TextButton(enabled = enabled && preview != null, onClick = {
                val bitmap = preview ?: return@TextButton
                val aspect = bitmap.width.toFloat() / bitmap.height
                val inset = if (aspect > 1) (1 - 1 / aspect) / 2 else (1 - aspect) / 2
                edit.change(edit.current.copy(crop = if (aspect > 1) PhotoCrop(inset, 0f, 1 - inset, 1f) else PhotoCrop(0f, inset, 1f, 1 - inset)))
            }) { Text(stringResource(R.string.square_crop)) }
            TextButton(enabled = enabled, onClick = { edit.change(edit.current.copy(turns = (turns + 1) % 4)) }) {
                Text(stringResource(R.string.rotate_degrees, turns * 90))
            }
            TextButton(enabled = enabled, onClick = { adjustments = !adjustments }) { Text(stringResource(R.string.adjust_crop)) }
        }
        if (adjustments) Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
            edges.forEachIndexed { edge, label ->
                val direction = if (edge < 2) 1f else -1f
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f))
                    TextButton(enabled = enabled, onClick = { edit.adjustEdge(edge, -CROP_NUDGE * direction) },
                        modifier = Modifier.semantics { contentDescription = adjustmentLabels[edge].first }) { Text("−") }
                    TextButton(enabled = enabled, onClick = { edit.adjustEdge(edge, CROP_NUDGE * direction) },
                        modifier = Modifier.semantics { contentDescription = adjustmentLabels[edge].second }) { Text("+") }
                }
            }
        }
        if (turns != 0) Text(stringResource(R.string.rotation_on_save, turns * 90), Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodySmall)
    }
}

internal const val CROP_NUDGE = .025f

/** The slider for the chosen tool above the row of tools. A dot marks tools that are not at 0. */
@Composable
internal fun AdjustPanel(edit: PhotoEditState, selected: Adjustment, onSelect: (Adjustment) -> Unit, enabled: Boolean) {
    val adjustments = edit.current.recipe.adjustments
    val label = stringResource(selected.label)
    val changedLabel = stringResource(R.string.adjustment_changed)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        EditorSlider(label, adjustments[selected], selected.min..selected.max, enabled = enabled,
            onChange = { edit.slide(selected, it) }, onFinished = edit::endDrag, onReset = { edit.reset(selected) },
            modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Adjustment.entries.forEach { tool ->
                val isSelected = tool == selected
                val changed = adjustments[tool] != 0
                Column(Modifier.clip(MaterialTheme.shapes.medium).clickable(enabled = enabled, role = Role.Tab) { onSelect(tool) }
                    .semantics { this.selected = isSelected; if (changed) stateDescription = changedLabel }
                    .padding(horizontal = 12.dp, vertical = 8.dp).testTag("adjust-tool-${tool.name.lowercase()}"),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(tool.label), style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    Box(Modifier.padding(top = 4.dp).size(5.dp).clip(CircleShape)
                        .background(if (changed) MaterialTheme.colorScheme.primary else Color.Transparent))
                }
            }
        }
    }
}

/** Looks as thumbnails of the current photo, and the chosen look's strength. */
@Composable
internal fun FiltersPanel(edit: PhotoEditState, thumbnail: Bitmap?, enabled: Boolean) {
    val recipe = edit.current.recipe
    val image = remember(thumbnail) { thumbnail?.asImageBitmap() }
    val base = remember(recipe.adjustments) { ColorMatrices.adjustments(recipe.adjustments) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (recipe.filter != LookFilter.NONE) {
            EditorSlider(stringResource(R.string.filter_strength), recipe.filterStrength, 0..100, enabled = enabled,
                onChange = edit::slideFilterStrength, onFinished = edit::endDrag,
                onReset = { edit.endDrag(); edit.change(edit.current.copy(recipe = edit.current.recipe.copy(filterStrength = 100))) },
                modifier = Modifier.padding(horizontal = 16.dp).testTag("filter-strength"))
        }
        LazyRow(Modifier.fillMaxWidth().testTag("filter-list"), contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LookFilter.entries, key = { it.name }) { look ->
                val isSelected = look == recipe.filter
                val matrix = remember(look, base) { ColorFilter.colorMatrix(ColorMatrix(ColorMatrices.concat(ColorMatrices.filter(look), base))) }
                Column(Modifier.width(72.dp).clip(MaterialTheme.shapes.small).clickable(enabled = enabled, role = Role.Tab) { edit.choose(look) }
                    .semantics { this.selected = isSelected }.testTag("filter-${look.name.lowercase()}"),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp).clip(MaterialTheme.shapes.small)
                        .border(2.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = matrix) }
                    }
                    Text(stringResource(look.label), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else LocalContentColor.current, maxLines = 1)
                }
            }
        }
    }
}
