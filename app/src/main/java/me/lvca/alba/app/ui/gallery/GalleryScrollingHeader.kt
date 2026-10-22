package me.lvca.alba.app.ui.gallery

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.ui.designsystem.theme.galleryOverlay
import me.lvca.alba.app.ui.monthLabel
import me.lvca.alba.app.ui.monthNameLabel
import me.lvca.alba.app.ui.viewer.galleryEaseOut
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion
import java.time.YearMonth
import kotlin.math.roundToInt

/** Follow the nested scroll directly. Only the remaining header occupies space above the grid. */
@OptIn(ExperimentalMaterial3Api::class)
internal fun Modifier.scrollingGalleryHeader(state: TopAppBarState, enabled: Boolean): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val header = measurable.measure(constraints)
        val limit = -header.height.toFloat()
        if (state.heightOffsetLimit != limit) state.heightOffsetLimit = limit
        val offset = if (enabled) state.heightOffset.roundToInt().coerceIn(-header.height, 0) else 0
        layout(header.width, (header.height + offset).coerceAtLeast(0)) {
            header.placeRelative(0, offset)
        }
    }

/**
 * Photos pass under the system icons once the header scrolls away. An opaque bar in the header's [color] keeps the
 * icons readable over any photo, in both themes, and joins the header seamlessly when it returns.
 */
@Composable
internal fun GalleryStatusBarScrim(color: () -> Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).drawBehind { drawRect(color()) }
        .testTag("gallery-status-scrim"))
}

/** The date floats above the photo timeline, independent of the header's visibility. */
@Composable
internal fun FloatingGridDate(month: YearMonth?, dates: () -> Unit,
    modifier: Modifier = Modifier) {
    val reducedMotion = reduceGalleryMotion()
    val reveal by animateFloatAsState(if (month != null) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(160, easing = galleryEaseOut), label = "scroll-date-reveal")
    var lastMonth by remember { mutableStateOf(month) }
    SideEffect { if (month != null) lastMonth = month }
    val monthDescription = lastMonth?.let { monthLabel(it) }.orEmpty()
    val lift = with(LocalDensity.current) { 6.dp.toPx() }
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
        .testTag("floating-grid-controls")) {
        val pillWidth = (maxWidth - 112.dp).coerceAtMost(172.dp)
        if (reveal > 0f && lastMonth != null) {
            Surface(onClick = dates, enabled = month != null, shape = CircleShape,
                color = MaterialTheme.colorScheme.galleryOverlay, contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 2.dp, modifier = Modifier.align(Alignment.TopCenter).width(pillWidth)
                    .graphicsLayer { alpha = reveal; translationY = if (reducedMotion) 0f else -lift * (1f - reveal) }
                    .then(if (month == null) Modifier.clearAndSetSemantics {} else Modifier)
                    .testTag("floating-date-button")) {
                Crossfade(lastMonth, modifier = Modifier.clearAndSetSemantics {
                    contentDescription = monthDescription
                }, animationSpec = if (reducedMotion) snap() else tween(100, easing = galleryEaseOut), label = "scroll-month") { date ->
                    if (date != null) Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(monthNameLabel(date), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(date.year.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1)
                    }
                }
            }
        }
    }
}
