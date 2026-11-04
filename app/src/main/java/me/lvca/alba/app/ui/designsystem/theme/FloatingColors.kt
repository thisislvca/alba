package me.lvca.alba.app.ui.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * The floating navigation's and selection toolbar's neutral colours, as Google Photos' floating surfaces: a quiet
 * container with a neutral tonal pill for the active item, never the brand blue. Derived from the scheme's neutral
 * roles so they follow any colour scheme, dynamic colour included, without changing the secondary roles that tonal
 * buttons, chips and the rail keep.
 */
@Immutable
internal data class FloatingColors(
    /** The navigation pill and the search circle. */
    val container: Color,
    /** Labels and icons of inactive destinations. */
    val content: Color,
    /** The active destination's pill: onSurface at a selected state layer's opacity over [container]. */
    val active: Color,
    val onActive: Color,
    /** The selection toolbar, a step up from the page like Material's standard floating toolbar. */
    val toolbar: Color,
    val onToolbar: Color,
)

/** Material's selected state layer opacity, used for the neutral active pill. */
private const val ACTIVE_LAYER_ALPHA = .12f

internal val ColorScheme.floating: FloatingColors
    get() = FloatingColors(
        container = surfaceContainerLow,
        content = onSurfaceVariant,
        active = onSurface.copy(alpha = ACTIVE_LAYER_ALPHA).compositeOver(surfaceContainerLow),
        onActive = onSurface,
        toolbar = surfaceContainer,
        onToolbar = onSurface,
    )

/** Alba's floating-surface colours for the current [MaterialTheme]. */
internal val AlbaTheme.floating: FloatingColors
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.floating
