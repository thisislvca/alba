package me.lvca.alba.app.ui.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Screens that draw a dark backdrop behind the status bar, in either theme, ask for light status-bar icons here. The
 * root reads [lightIcons] where it styles the system bars, so the request wins over the theme while the screen is shown.
 */
@Stable
internal class StatusBarIcons {
    private var requests by mutableIntStateOf(0)
    val lightIcons: Boolean get() = requests > 0

    fun request() { requests++ }
    fun release() { requests-- }
}

internal val LocalStatusBarIcons = staticCompositionLocalOf { StatusBarIcons() }

/** Light status-bar icons for as long as the caller is composed. */
@Composable
internal fun LightStatusBarIcons() {
    val icons = LocalStatusBarIcons.current
    DisposableEffect(icons) {
        icons.request()
        onDispose { icons.release() }
    }
}
