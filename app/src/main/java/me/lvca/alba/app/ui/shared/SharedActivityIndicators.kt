package me.lvca.alba.app.ui.shared

import androidx.compose.runtime.staticCompositionLocalOf
import me.lvca.alba.app.sharing.SharedActivityState

/** New shared-album activity for badges and dots; empty where the app does not provide it, such as in previews. */
internal val LocalSharedActivity = staticCompositionLocalOf { SharedActivityState() }
