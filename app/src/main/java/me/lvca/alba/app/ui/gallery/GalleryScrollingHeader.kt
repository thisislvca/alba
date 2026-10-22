@file:OptIn(ExperimentalLayoutApi::class)

package me.lvca.alba.app.ui.gallery

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animate
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
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
 * Google Photos' scrolling toolbar: the header follows the scroll 1:1, but a shown header only starts hiding when the
 * content moves forward at [hidePxPerSecond] or faster, and a hidden one only starts revealing when it moves back at
 * [revealPxPerSecond]. Once partly visible it tracks every delta, so it never stutters mid-way.
 */
internal class HeaderScrollGate(private val revealPxPerSecond: Float, private val hidePxPerSecond: Float) {
    private var velocity = 0f
    private var lastNanos = 0L

    /** The direction of the last delta the header followed: negative hides, positive reveals. */
    var direction = 0f
        private set

    /**
     * The header offset after a scroll [delta] (positive reveals) at [nanos], from [offset] within [limit]..0.
     * A gap of more than 100 ms starts a new velocity estimate.
     */
    fun offsetAfter(delta: Float, nanos: Long, offset: Float, limit: Float): Float {
        if (delta == 0f) return offset
        val gap = (nanos - lastNanos) / NANOS_PER_SECOND
        val fresh = lastNanos == 0L || gap > NEW_GESTURE_GAP_SECONDS || gap <= 0f
        val seconds = if (fresh) FRAME_SECONDS else gap.coerceAtLeast(MIN_FRAME_SECONDS)
        lastNanos = nanos
        val instant = delta / seconds
        velocity = if (fresh || velocity == 0f || velocity * instant < 0f) instant else velocity * (1f - SMOOTHING) + instant * SMOOTHING
        val moving = offset < 0f && offset > limit
        val starts = if (delta < 0f) -velocity >= hidePxPerSecond else velocity >= revealPxPerSecond
        if (!moving && !starts) return offset
        direction = delta
        return (offset + delta).coerceIn(limit, 0f)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val NEW_GESTURE_GAP_SECONDS = .1f
        const val FRAME_SECONDS = 1f / 60f
        const val MIN_FRAME_SECONDS = 1f / 240f

        /** How much each new delta moves the velocity estimate. */
        const val SMOOTHING = .6f
    }

    /** Where a partly visible header settles when scrolling stops: back the way it was last moving. */
    fun settleTarget(offset: Float, limit: Float): Float? = when {
        offset >= 0f || offset <= limit -> null
        direction < 0f -> limit
        else -> 0f
    }
}

/** Drives [state] from nested scrolling with [HeaderScrollGate]'s velocity gates, snapping when scrolling stops. */
@OptIn(ExperimentalMaterial3Api::class)
internal class GalleryHeaderScroll(
    private val state: TopAppBarState,
    private val gate: HeaderScrollGate,
    private val enabled: () -> Boolean,
    private val reducedMotion: () -> Boolean,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!enabled()) return Offset.Zero
        val before = state.heightOffset
        state.heightOffset = gate.offsetAfter(available.y, System.nanoTime(), before, state.heightOffsetLimit)
        return Offset(0f, state.heightOffset - before)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Content that reached its start returns the header whatever the speed.
        if (!enabled() || available.y <= 0f || state.heightOffset >= 0f) return Offset.Zero
        val before = state.heightOffset
        state.heightOffset = (before + available.y).coerceAtMost(0f)
        return Offset(0f, state.heightOffset - before)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val target = gate.settleTarget(state.heightOffset, state.heightOffsetLimit)
        if (enabled() && target != null) {
            if (reducedMotion()) state.heightOffset = target
            else animate(state.heightOffset, target, animationSpec = tween(GalleryFeel.HEADER_SNAP_MILLIS, easing = GalleryFeel.HeaderSnapEasing)) { value, _ ->
                state.heightOffset = value
            }
        }
        return Velocity.Zero
    }
}

/**
 * Photos pass under the system icons once the header scrolls away. Like Google Photos, a strong tint in the header's
 * [color] keeps the icons readable while the photos stay faintly visible, and joins the header when it returns.
 */
@Composable
internal fun GalleryStatusBarScrim(color: () -> Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBarsIgnoringVisibility)
        .drawBehind { drawRect(color().copy(alpha = GalleryFeel.STATUS_SCRIM_ALPHA)) }
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
