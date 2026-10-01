package dev.mela.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import dev.mela.app.ui.theme.galleryOverlay

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

/** Photos can pass under the system icons; a translucent surface keeps their contrast stable. */
@Composable
internal fun GalleryStatusBarScrim(modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
        .background(Brush.verticalGradient(listOf(surface.copy(alpha = .92f), surface.copy(alpha = .78f))))
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
    val locale = LocalConfiguration.current.locales[0]
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
                    contentDescription = lastMonth?.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)).orEmpty()
                }, animationSpec = if (reducedMotion) snap() else tween(100, easing = galleryEaseOut), label = "scroll-month") { date ->
                    if (date != null) Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(date.format(DateTimeFormatter.ofPattern("MMMM", locale)), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(date.year.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1)
                    }
                }
            }
        }
    }
}
