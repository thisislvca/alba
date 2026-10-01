package dev.mela.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mela.app.R
import dev.mela.app.ui.theme.galleryOverlay

internal enum class GalleryTab(val title: Int) { LIBRARY(R.string.gallery_photos), COLLECTIONS(R.string.collections), SEARCH(R.string.search) }

private enum class TabDisplayMode { EXPANDED, COMPACT, ICON_ONLY }

/**
 * Photos 7.94's V2 navigation rules, implemented with our existing Compose Material components.
 * Measure translated labels before collapsing; font-scale/width cutoffs cannot predict their fit.
 * Two Alba destinations share the capsule, with Search as a separate 56dp control.
 */
@Composable
internal fun FloatingGalleryNavigation(tab: GalleryTab, navigate: (GalleryTab) -> Unit, modifier: Modifier = Modifier) {
    val destinations = listOf(GalleryTab.LIBRARY, GalleryTab.COLLECTIONS)
    val titles = destinations.map { stringResource(it.title) }
    // GoogleMaterial3 LabelLarge in the inspected resources: 14sp medium, 20sp line, no tracking.
    val labelStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.sp)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelWidths = titles.map { textMeasurer.measure(AnnotatedString(it), labelStyle, maxLines = 1, softWrap = false).size.width }
    val colors = MaterialTheme.colorScheme

    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        fun groupWidth(mode: TabDisplayMode): Int = with(density) {
            // Capsule padding, the space to Search, and Search itself.
            8.dp.roundToPx() + 8.dp.roundToPx() + 56.dp.roundToPx() + destinations.indices.sumOf { index ->
                val selected = tab == destinations[index]
                val label = mode != TabDisplayMode.ICON_ONLY && (selected || mode == TabDisplayMode.EXPANDED)
                val icon = selected || mode != TabDisplayMode.EXPANDED
                val content = (if (label) labelWidths[index] else 0) +
                    (if (icon) 20.dp.roundToPx() else 0) +
                    (if (icon && label) 4.dp.roundToPx() else 0)
                val padding = (if (!label) 24.dp else if (selected) 20.dp else 16.dp).roundToPx()
                (content + padding).coerceAtLeast(48.dp.roundToPx()) + (if (label) 8.dp.roundToPx() else 0)
            }
        }
        val availableWidth = with(density) { maxWidth.roundToPx() }
        val mode = TabDisplayMode.entries.firstOrNull { groupWidth(it) <= availableWidth } ?: TabDisplayMode.ICON_ONLY

        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = colors.galleryOverlay, contentColor = colors.onSurfaceVariant,
                shadowElevation = 3.dp, modifier = Modifier.testTag("gallery-destination-capsule")) {
                Row(Modifier.padding(4.dp).selectableGroup()) {
                    destinations.forEachIndexed { index, destination ->
                        val selected = tab == destination
                        val showLabel = mode != TabDisplayMode.ICON_ONLY && (selected || mode == TabDisplayMode.EXPANDED)
                        val showIcon = selected || mode != TabDisplayMode.EXPANDED
                        val interactions = remember { MutableInteractionSource() }
                        Box(Modifier.padding(horizontal = if (showLabel) 4.dp else 0.dp)
                            .widthIn(min = 48.dp).heightIn(min = 48.dp)
                            .selectable(selected, role = Role.Tab, interactionSource = interactions, indication = null,
                                onClick = { navigate(destination) })
                            .semantics { if (!showLabel) contentDescription = titles[index] }
                            .testTag("tab-${destination.name}"), contentAlignment = Alignment.Center) {
                            // V2 paints a 40dp pill inside the full 48dp touch target.
                            Box(Modifier.matchParentSize().padding(vertical = 4.dp).clip(CircleShape)
                                .background(if (selected) colors.secondaryContainer else Color.Transparent)
                                .indication(interactions, ripple()))
                            Row(Modifier.padding(start = if (showLabel) 8.dp else 12.dp,
                                end = if (!showLabel || selected) 12.dp else 8.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                val foreground = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
                                if (showIcon) Icon(painterResource(if (destination == GalleryTab.LIBRARY) R.drawable.icloud_library else R.drawable.icloud_albums),
                                    null, Modifier.size(20.dp), tint = foreground)
                                if (showLabel) Text(titles[index], color = foreground, style = labelStyle, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
            Surface(shape = CircleShape, color = if (tab == GalleryTab.SEARCH) colors.secondaryContainer else colors.galleryOverlay,
                contentColor = if (tab == GalleryTab.SEARCH) colors.onSecondaryContainer else colors.onSurfaceVariant, shadowElevation = 3.dp) {
                Box(Modifier.size(56.dp).clip(CircleShape)
                    .selectable(tab == GalleryTab.SEARCH, role = Role.Tab, onClick = { navigate(GalleryTab.SEARCH) })
                    .testTag("tab-SEARCH"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Search, stringResource(R.string.search), Modifier.size(24.dp))
                }
            }
        }
    }
}
