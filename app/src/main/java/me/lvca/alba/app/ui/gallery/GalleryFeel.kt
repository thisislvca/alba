package me.lvca.alba.app.ui.gallery

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.unit.dp

/**
 * The library grid's feel numbers in one place. Each one matches the value Google Photos 7.95 uses for the same
 * interaction (decompiled resource or bytecode constant, noted per value) so the grid behaves like the platform's
 * reference gallery; only the numbers are shared, not code or assets.
 */
internal object GalleryFeel {
    // Pinch between grid levels.
    /** Zooming in reaches full progress at a finger scale of 1.8: progress = (scale − 1) / 0.8. */
    const val PINCH_IN_SPAN = .8f

    /** Zooming out reaches full progress at a finger scale of 0.36: progress = (1 − scale) / 0.64. */
    const val PINCH_OUT_SPAN = .64f

    /** Releasing at or below this progress reverts; above it commits to the next level. */
    const val PINCH_COMMIT_PROGRESS = .2f

    /** The release settles over 100 ms, LinearOutSlowIn. */
    const val PINCH_SETTLE_MILLIS = 100

    /** Past the last level the grid rubber-bands and springs back over 200 ms, LinearOutSlowIn. */
    const val PINCH_RUBBER_RETURN_MILLIS = 200

    /** Full-bleed grid spacing (photos_allphotos_fragment_full_bleed_grid_spacing). */
    val GRID_SPACING = 2.dp

    /** Below this width the default day level has 3 columns instead of 4 (grid redesign, sw413dp). */
    const val DAY_FOUR_COLUMNS_FROM_DP = 413

    // Selection.

    /** A checked tile shrinks by this much on every side (photos_photoadapteritem_checked_padding). */
    val SELECTED_INSET = 12.dp

    /** A checked tile's corner radius (photos_theme_rounded_corner_radius). */
    val SELECTED_CORNER = 16.dp

    /** Toggling one tile: inset and scale over 105 ms, LinearOutSlowIn. */
    const val SELECT_TOGGLE_MILLIS = 105

    /** Entering or leaving selection mode: overlay, inset and scale over 270 ms, FastOutSlowIn. */
    const val SELECTION_MODE_MILLIS = 270

    /** The check circle fades in over 150 ms after 75 ms, and out over 120 ms. */
    const val CHECK_FADE_IN_DELAY_MILLIS = 75
    const val CHECK_FADE_IN_MILLIS = 150
    const val CHECK_FADE_OUT_MILLIS = 120

    /** Unchecked circles are drawn at half opacity in selection mode. */
    const val UNCHECKED_RING_ALPHA = .5f

    /** Drag-select scrolls inside this band at the top and bottom of the padded grid (photogrid_drag_scroll_zone). */
    val AUTOSCROLL_EDGE = 56.dp

    /** Drag-select autoscroll speed at the very edge; it ramps linearly with depth into the band. */
    const val AUTOSCROLL_MAX_DP_PER_SECOND = 600f

    /** The contextual selection bar slides in and the navigation slides away over 100 ms. */
    const val CONTEXT_BAR_SLIDE_MILLIS = 100

    // Fast scroller.

    /** The scrubber appears after this much scrolling since the scroll began (photos_list_fastscroll_distance_threshold). */
    val SCRUBBER_REVEAL_DISTANCE = 15.dp

    /** Thumb size (photos_list_fastscroll_thumb_width / _height). */
    val SCRUBBER_THUMB_WIDTH = 40.dp
    val SCRUBBER_THUMB_HEIGHT = 69.dp

    /** Slide in over 100 ms, out over 150 ms. */
    const val SCRUBBER_SHOW_MILLIS = 100
    const val SCRUBBER_HIDE_MILLIS = 150

    /** Hide 2500 ms after scrolling stops, or 1000 ms after a scrubber drag ends. */
    const val SCRUBBER_IDLE_HIDE_MILLIS = 2500L
    const val SCRUBBER_DRAG_HIDE_MILLIS = 1000L

    /** Year labels on the track fade in over 100 ms and out over 150 ms, only while dragging. */
    const val SCRUBBER_LABEL_IN_MILLIS = 100
    const val SCRUBBER_LABEL_OUT_MILLIS = 150

    // Scrolling header.

    /** A hidden header starts revealing only when scrolling back at this speed (scrollingtoolbar_entry_threshold). */
    const val HEADER_REVEAL_DP_PER_SECOND = 400f

    /** A shown header starts hiding only when scrolling forward at this speed (scrollingtoolbar_exit_threshold). */
    const val HEADER_HIDE_DP_PER_SECOND = 80f

    /** A partly visible header snaps in or out over 210 ms when scrolling stops. */
    const val HEADER_SNAP_MILLIS = 210

    /** The snap's PathInterpolator(0.4, 0, 0.6, 1). */
    val HeaderSnapEasing = CubicBezierEasing(.4f, 0f, .6f, 1f)

    /**
     * The status-bar tint over scrolled photos. Photos tints rather than hides them (they stay faintly visible under
     * the clock); the opacity is estimated from its 7.94 screenshots, since the value comes from the theme.
     */
    const val STATUS_SCRIM_ALPHA = .88f

    // Tiles.

    /**
     * A decoded thumbnail fades in over its flat placeholder. Photos' image-library crossfade was not traced; this is
     * shorter than its generic 200 ms fade (photos_animations_fade_in) so fast scrolling never looks washed out.
     */
    const val TILE_FADE_IN_MILLIS = 150
}
