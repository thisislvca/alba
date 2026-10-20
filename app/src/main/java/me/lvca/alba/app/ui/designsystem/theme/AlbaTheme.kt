package me.lvca.alba.app.ui.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** Shared gallery chrome; the light palette uses a softer surface without changing dark mode. */
internal val ColorScheme.galleryOverlay: Color
    get() = if (surface.luminance() > .5f) surfaceContainerLow else surfaceContainer

private val LightColors = lightColorScheme(
    primary = Color(0xFF0065D0), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEBFF), onPrimaryContainer = Color(0xFF004493),
    secondary = Color(0xFF53637B), secondaryContainer = Color(0xFFE7EEFA), onSecondaryContainer = Color(0xFF0065D0),
    background = Color(0xFFF2F2F7), onBackground = Color(0xFF191C20),
    surface = Color.White, onSurface = Color(0xFF191C20),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F2F7), surfaceContainer = Color(0xFFECECF1),
    surfaceContainerHighest = Color(0xFFE3E3E8),
    surfaceContainerHigh = Color(0xFFE6EAF0), surfaceVariant = Color(0xFFE6EAF0),
    onSurfaceVariant = Color(0xFF56606E), outline = Color(0xFF737D8A), outlineVariant = Color(0xFFDCE1E9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DCBFF), onPrimary = Color(0xFF003363),
    primaryContainer = Color(0xFF173F69), onPrimaryContainer = Color(0xFFDDEBFF),
    secondary = Color(0xFFBBC8DD), secondaryContainer = Color(0xFF2F3F55), onSecondaryContainer = Color(0xFF9DCBFF),
    background = Color(0xFF101114), onBackground = Color(0xFFE5E8EF),
    surface = Color(0xFF101114), onSurface = Color(0xFFE5E8EF),
    surfaceContainerLowest = Color(0xFF1C1C1E), surfaceContainerLow = Color(0xFF1C1C1E), surfaceContainer = Color(0xFF20232A),
    surfaceContainerHigh = Color(0xFF292D35), surfaceVariant = Color(0xFF303540),
    onSurfaceVariant = Color(0xFFB9C2D1), outline = Color(0xFF8590A1), outlineVariant = Color(0xFF373E4B),
)

@Composable
fun AlbaTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAlbaColors provides if (darkTheme) DarkAlbaColors else LightAlbaColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AlbaTypography,
            shapes = AlbaShapes,
            content = content,
        )
    }
}

/** Alba's tokens beyond [MaterialTheme]: `AlbaTheme.colors.accent`, alongside `MaterialTheme.shapes`. */
internal object AlbaTheme {
    val colors: AlbaColors
        @Composable @ReadOnlyComposable get() = LocalAlbaColors.current
}
