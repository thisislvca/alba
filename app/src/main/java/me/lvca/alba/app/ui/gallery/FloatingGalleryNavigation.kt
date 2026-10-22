package me.lvca.alba.app.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.designsystem.theme.galleryOverlay
import me.lvca.alba.app.ui.designsystem.theme.navigationLabel
import me.lvca.alba.app.ui.navigation.NavigationMotion
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion

internal enum class GalleryTab(val title: Int) { LIBRARY(R.string.gallery_photos), COLLECTIONS(R.string.collections), SEARCH(R.string.search) }

/** One glyph per destination, shared by the floating capsule and the navigation rail. */
@Composable
internal fun GalleryTab.icon(): Painter = when (this) {
    GalleryTab.LIBRARY -> painterResource(R.drawable.icloud_library)
    GalleryTab.COLLECTIONS -> painterResource(R.drawable.icloud_albums)
    GalleryTab.SEARCH -> rememberVectorPainter(Icons.Outlined.Search)
}

private enum class TabDisplayMode { EXPANDED, COMPACT, ICON_ONLY }

/**
 * Photos 7.94's V2 navigation rules, implemented with our existing Compose Material components.
 * Measure translated labels before collapsing; font-scale/width cutoffs cannot predict their fit.
 * Two Alba destinations share the capsule, with Search as a separate 56dp control.
 *
 * One selection pill slides and resizes between destinations while the selected label expands in, and the
 * control steps aside while the keyboard is open, as Photos' bottom navigation does.
 */
@Composable
internal fun FloatingGalleryNavigation(tab: GalleryTab, navigate: (GalleryTab) -> Unit, modifier: Modifier = Modifier) {
    val destinations = listOf(GalleryTab.LIBRARY, GalleryTab.COLLECTIONS)
    val titles = destinations.map { stringResource(it.title) }
    val labelStyle = MaterialTheme.typography.navigationLabel
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelWidths = titles.map { textMeasurer.measure(AnnotatedString(it), labelStyle, maxLines = 1, softWrap = false).size.width }
    val colors = MaterialTheme.colorScheme
    val motion = DestinationMotion(reduceGalleryMotion())

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

        AnimatedVisibility(!keyboardOpen(), Modifier.align(Alignment.Center), enter = motion.reveal, exit = motion.hide) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = colors.galleryOverlay, contentColor = colors.onSurfaceVariant,
                    shadowElevation = 3.dp, modifier = Modifier.testTag("gallery-destination-capsule")) {
                    // Each destination reports where its pill belongs; one indicator follows the selected one.
                    val pills = remember { mutableStateMapOf<GalleryTab, Rect>() }
                    val target = pills[tab]
                    val indicator = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
                    LaunchedEffect(target) {
                        val bounds = target ?: return@LaunchedEffect
                        if (indicator.value == Rect.Zero) indicator.snapTo(bounds) else indicator.animateTo(bounds, motion.rect)
                    }
                    val pillColor = colors.secondaryContainer
                    Row(Modifier.padding(4.dp).selectableGroup().drawBehind {
                        val pill = indicator.value
                        if (pill != Rect.Zero) drawRoundRect(pillColor, pill.topLeft, pill.size, CornerRadius(pill.height / 2f))
                    }) {
                        destinations.forEachIndexed { index, destination ->
                            val selected = tab == destination
                            val showLabel = mode != TabDisplayMode.ICON_ONLY && (selected || mode == TabDisplayMode.EXPANDED)
                            val showIcon = selected || mode != TabDisplayMode.EXPANDED
                            val interactions = remember { MutableInteractionSource() }
                            val foreground by animateColorAsState(if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                                motion.color, label = "destination-foreground")
                            val margin by animateDpAsState(if (showLabel) 4.dp else 0.dp, motion.dp, label = "destination-margin")
                            val start by animateDpAsState(if (showLabel) 8.dp else 12.dp, motion.dp, label = "destination-start")
                            val end by animateDpAsState(if (!showLabel || selected) 12.dp else 8.dp, motion.dp, label = "destination-end")
                            Box(Modifier.padding(horizontal = margin)
                                .widthIn(min = 48.dp).heightIn(min = 48.dp)
                                .onPlaced { coordinates ->
                                    // V2 paints a 40dp pill inside the full 48dp touch target.
                                    val inset = with(density) { 4.dp.toPx() }
                                    val box = coordinates.boundsInParent()
                                    val pill = Rect(box.left, box.top + inset, box.right, box.bottom - inset)
                                    if (pills[destination] != pill) pills[destination] = pill
                                }
                                .selectable(selected, role = Role.Tab, interactionSource = interactions, indication = null,
                                    onClick = { navigate(destination) })
                                .semantics { if (!showLabel) contentDescription = titles[index] }
                                .testTag("tab-${destination.name}"), contentAlignment = Alignment.Center) {
                                Box(Modifier.matchParentSize().padding(vertical = 4.dp).clip(CircleShape)
                                    .indication(interactions, ripple()))
                                Row(Modifier.padding(start = start, end = end, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    AnimatedVisibility(showIcon, enter = motion.appear, exit = motion.disappear) {
                                        Icon(destination.icon(), null, Modifier.padding(end = if (showLabel) 4.dp else 0.dp).size(20.dp), tint = foreground)
                                    }
                                    AnimatedVisibility(showLabel, enter = motion.appear, exit = motion.disappear) {
                                        Text(titles[index], color = foreground, style = labelStyle, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                }
                val searchSelected = tab == GalleryTab.SEARCH
                val searchColor by animateColorAsState(if (searchSelected) colors.secondaryContainer else colors.galleryOverlay,
                    motion.color, label = "search-container")
                val searchContent by animateColorAsState(if (searchSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    motion.color, label = "search-content")
                Surface(shape = CircleShape, color = searchColor, contentColor = searchContent, shadowElevation = 3.dp) {
                    Box(Modifier.size(56.dp).clip(CircleShape)
                        .selectable(searchSelected, role = Role.Tab, onClick = { navigate(GalleryTab.SEARCH) })
                        .testTag("tab-SEARCH"), contentAlignment = Alignment.Center) {
                        Icon(GalleryTab.SEARCH.icon(), stringResource(R.string.search), Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun keyboardOpen(): Boolean = WindowInsets.isImeVisible

/** Material's fast spatial and effects springs for the capsule, or snaps when animations are off. */
private class DestinationMotion(reduce: Boolean) {
    val rect: FiniteAnimationSpec<Rect> = if (reduce) snap() else NavigationMotion.fastSpatial()
    val dp: FiniteAnimationSpec<androidx.compose.ui.unit.Dp> = if (reduce) snap() else NavigationMotion.fastSpatial()
    val color: FiniteAnimationSpec<androidx.compose.ui.graphics.Color> = if (reduce) snap() else NavigationMotion.fastEffects()
    private val size: FiniteAnimationSpec<IntSize> = if (reduce) snap() else NavigationMotion.fastSpatial()
    private val fade: FiniteAnimationSpec<Float> = if (reduce) snap() else NavigationMotion.fastEffects()
    val appear = fadeIn(fade) + expandHorizontally(size)
    val disappear = fadeOut(fade) + shrinkHorizontally(size)
    val reveal = if (reduce) fadeIn(snap()) else fadeIn(tween(NavigationMotion.SHORT4, delayMillis = NavigationMotion.SHORT2)) +
        slideInVertically(tween(NavigationMotion.MEDIUM1, easing = NavigationMotion.EmphasizedDecelerate)) { it / 2 }
    val hide = fadeOut(if (reduce) snap() else tween(NavigationMotion.SHORT2))
}
