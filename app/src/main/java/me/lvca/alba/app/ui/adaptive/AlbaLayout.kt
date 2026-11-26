package me.lvca.alba.app.ui.adaptive

import android.content.res.Configuration
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass

/** Material's window width classes: phones in portrait, then small tablets and unfolded foldables, then wide windows. */
internal enum class WindowWidth { COMPACT, MEDIUM, EXPANDED }

/**
 * Where the app's adaptive layouts stand for the current window. Every size decision reads this, so the breakpoints
 * live in one place: Material's window size classes (see [albaWindowInfo]), never a dp check of its own.
 */
@Immutable
internal data class AlbaLayout(val width: WindowWidth, val landscape: Boolean, val compactHeight: Boolean = false) {
    /** A navigation rail beside the content, with Search in its header, instead of the floating pill. */
    val navigationRail: Boolean get() = width != WindowWidth.COMPACT

    /** Collections keeps its list on the left and shows the opened album, collection or shared album on the right. */
    val listDetail: Boolean get() = width == WindowWidth.EXPANDED

    /** A photo's Info opens as a pane beside the photo rather than a sheet below it. Compact portrait keeps the sheet. */
    val infoSidePane: Boolean get() = width != WindowWidth.COMPACT || landscape

    /**
     * Screens open with a large title that collapses as they scroll. A short window (a phone in landscape) has no
     * height to spare, so its screens keep one small bar instead, with the title inline beside the actions.
     */
    val largeTitles: Boolean get() = !compactHeight

    companion object {
        fun of(sizeClass: WindowSizeClass, landscape: Boolean) = AlbaLayout(
            width = when {
                sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> WindowWidth.EXPANDED
                sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> WindowWidth.MEDIUM
                else -> WindowWidth.COMPACT
            },
            landscape = landscape,
            compactHeight = !sizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND),
        )
    }
}

/**
 * The window's adaptive info, as `currentWindowAdaptiveInfo()` computes it (Material's V1 breakpoints on the window's
 * size), without its fold posture. Alba has no tabletop layout, and collecting the posture starts the window library's
 * layout tracker, whose background work stalls Robolectric's whole-app tests (see configureJvmTests).
 */
@Composable
internal fun albaWindowInfo(): WindowAdaptiveInfo {
    val size = LocalWindowInfo.current.containerDpSize
    return WindowAdaptiveInfo(WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(size.width.value, size.height.value), Posture())
}

/** The adaptive layout for the window this composition is in. */
@Composable
internal fun albaLayout(): AlbaLayout = AlbaLayout.of(albaWindowInfo().windowSizeClass,
    landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE)
