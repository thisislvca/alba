package me.lvca.alba.app.ui.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The "Match wallpaper colors" setting as the UI sees it: whether the device offers it (Android 12 and later), whether
 * it is on, and how to change it. The root provides it from the saved preference; [AlbaTheme] reads it.
 */
@Immutable
internal class WallpaperColorsSetting(
    val available: Boolean,
    val enabled: Boolean,
    val onChange: (Boolean) -> Unit,
)

/** Off and unavailable until the root provides the saved setting, as in tests and previews. */
internal val LocalWallpaperColors = staticCompositionLocalOf { WallpaperColorsSetting(available = false, enabled = false) {} }
