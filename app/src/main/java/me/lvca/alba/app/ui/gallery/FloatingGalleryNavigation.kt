package me.lvca.alba.app.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.designsystem.AlbaFloatingDefaults
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.designsystem.theme.floating
import me.lvca.alba.app.ui.designsystem.theme.navigationLabel
import me.lvca.alba.app.ui.navigation.NavigationMotion
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion

internal enum class GalleryTab(val title: Int) { LIBRARY(R.string.gallery_photos), COLLECTIONS(R.string.collections), SEARCH(R.string.search) }

/**
 * One glyph per destination, shared by the floating capsule and the navigation rail: outlined, and filled while it is
 * the [selected] destination.
 */
@Composable
internal fun GalleryTab.icon(selected: Boolean = false): Painter = rememberVectorPainter(when (this) {
    GalleryTab.LIBRARY -> if (selected) AlbaIcons.PhotoLibraryFilled else AlbaIcons.PhotoLibrary
    GalleryTab.COLLECTIONS -> if (selected) AlbaIcons.PhotoAlbumFilled else AlbaIcons.PhotoAlbum
    GalleryTab.SEARCH -> AlbaIcons.Search
})

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
    val floating = AlbaTheme.floating
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
                Surface(shape = CircleShape, color = floating.container, contentColor = floating.content,
                    shadowElevation = AlbaFloatingDefaults.ToolbarShadow, modifier = Modifier.testTag("gallery-destination-capsule")) {
                    // Each destination reports where its pill belongs, and one indicator travels to the selected one.
                    // The destinations resize while it travels, so it follows one spring from where it was toward
                    // wherever the destination is in that frame, rather than restarting at every new size.
                    val pills = remember { mutableStateMapOf<GalleryTab, Rect>() }
                    val indicator = remember { PillIndicator() }
                    val reduce = motion.reduce
                    LaunchedEffect(tab) {
                        // Search lives outside the capsule: with it selected, no destination here has a pill.
                        val from = indicator.drawn
                        if (from == null || tab !in destinations || reduce) indicator.land()
                        else indicator.travel(from, motion.travel)
                    }
                    val pillColor = floating.active
                    Row(Modifier.padding(4.dp).selectableGroup().drawBehind {
                        val pill = indicator.at(pills[tab])
                        if (pill != null) drawRoundRect(pillColor, pill.topLeft, pill.size, CornerRadius(pill.height / 2f))
                    }) {
                        destinations.forEachIndexed { index, destination ->
                            val selected = tab == destination
                            val showLabel = mode != TabDisplayMode.ICON_ONLY && (selected || mode == TabDisplayMode.EXPANDED)
                            val showIcon = selected || mode != TabDisplayMode.EXPANDED
                            val foreground = motion.color(if (selected) floating.onActive else floating.content, "destination-foreground")
                            val margin = motion.dp(if (showLabel) 4.dp else 0.dp, "destination-margin")
                            val start = motion.dp(if (showLabel) 8.dp else 12.dp, "destination-start")
                            val end = motion.dp(if (!showLabel || selected) 12.dp else 8.dp, "destination-end")
                            Box(Modifier.padding(horizontal = margin)
                                .widthIn(min = 48.dp).heightIn(min = 48.dp)
                                .onPlaced { coordinates ->
                                    // V2 paints a 40dp pill inside the full 48dp touch target.
                                    val inset = with(density) { 4.dp.toPx() }
                                    val box = coordinates.boundsInParent()
                                    val pill = Rect(box.left, box.top + inset, box.right, box.bottom - inset)
                                    if (pills[destination] != pill) pills[destination] = pill
                                }
                                // No press ripple: the pill itself answers the tap, and a second pill-shaped highlight
                                // under the finger would read as another selection while the pill travels to it.
                                .selectable(selected, role = Role.Tab, interactionSource = null, indication = null,
                                    onClick = { navigate(destination) })
                                .semantics { if (!showLabel) contentDescription = titles[index] }
                                .testTag("tab-${destination.name}"), contentAlignment = Alignment.Center) {
                                Row(Modifier.padding(start = start, end = end, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    motion.Shown(showIcon) {
                                        Icon(destination.icon(selected), null, Modifier.padding(end = if (showLabel) 4.dp else 0.dp).size(20.dp),
                                            tint = foreground)
                                    }
                                    motion.Shown(showLabel) {
                                        Text(titles[index], color = foreground, style = labelStyle, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                }
                val searchSelected = tab == GalleryTab.SEARCH
                val searchColor = motion.color(if (searchSelected) floating.active else floating.container, "search-container")
                // Photos' search glyph keeps the strong ink whether or not Search is the destination.
                Surface(shape = CircleShape, color = searchColor, contentColor = floating.onActive, shadowElevation = AlbaFloatingDefaults.ToolbarShadow) {
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

/**
 * The selection pill: where it was drawn last, and how far it has travelled from where it set off toward the selected
 * destination. Not state: drawing records [drawn], and only [travelled] invalidates the drawing.
 */
private class PillIndicator {
    private val travelled = Animatable(1f, visibilityThreshold = TRAVEL_THRESHOLD)
    private var from: Rect? = null

    /** The pill as last drawn, where the next journey sets off from; null before the first or with Search selected. */
    var drawn: Rect? = null
        private set

    /** Where to draw the pill for a destination at [target] in this frame, or nowhere without one. */
    fun at(target: Rect?): Rect? {
        val start = from
        val pill = if (target == null || start == null) target else lerp(start, target, travelled.value)
        drawn = pill
        return pill
    }

    /** Sets off from [start], wherever the last journey had reached, so a second tap turns it round mid-flight. */
    suspend fun travel(start: Rect, spec: AnimationSpec<Float>) {
        from = start
        travelled.snapTo(0f)
        travelled.animateTo(1f, spec)
        from = null
    }

    /** Puts the pill on its destination at once. */
    suspend fun land() {
        from = null
        travelled.snapTo(1f)
    }

    private companion object {
        /** A thousandth of the journey: under a pixel for any pill, so the spring never ends in a visible step. */
        const val TRAVEL_THRESHOLD = .001f
    }
}

/** Material's fast spatial and effects springs for the capsule, or plain changes when animations are off. */
private class DestinationMotion(val reduce: Boolean) {
    val travel: AnimationSpec<Float> = NavigationMotion.fastSpatial()
    private val dpSpec: FiniteAnimationSpec<Dp> = NavigationMotion.fastSpatial()
    private val colorSpec: FiniteAnimationSpec<Color> = NavigationMotion.fastEffects()
    private val size: FiniteAnimationSpec<IntSize> = NavigationMotion.fastSpatial()
    private val fade: FiniteAnimationSpec<Float> = NavigationMotion.fastEffects()
    private val appear = fadeIn(fade) + expandHorizontally(size)
    private val disappear = fadeOut(fade) + shrinkHorizontally(size)

    /** A destination's spacing, which springs with the pill, or simply changes when animations are off. */
    @Composable
    fun dp(target: Dp, label: String): Dp = if (reduce) target else animateDpAsState(target, dpSpec, label = label).value

    /** A destination's colour, which follows the pill, or simply changes when animations are off. */
    @Composable
    fun color(target: Color, label: String): Color = if (reduce) target else animateColorAsState(target, colorSpec, label = label).value

    /** A destination's icon or label, which grows in and out with the pill, or simply shows when animations are off. */
    @Composable
    fun Shown(visible: Boolean, content: @Composable () -> Unit) {
        if (reduce) {
            if (visible) content()
        } else AnimatedVisibility(visible, enter = appear, exit = disappear) { content() }
    }
    val reveal = if (reduce) fadeIn(snap()) else fadeIn(tween(NavigationMotion.SHORT4, delayMillis = NavigationMotion.SHORT2)) +
        slideInVertically(tween(NavigationMotion.MEDIUM1, easing = NavigationMotion.EmphasizedDecelerate)) { it / 2 }
    val hide = fadeOut(if (reduce) snap() else tween(NavigationMotion.SHORT2))
}
