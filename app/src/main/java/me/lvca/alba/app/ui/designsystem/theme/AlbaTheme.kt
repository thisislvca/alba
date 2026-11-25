package me.lvca.alba.app.ui.designsystem.theme

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

/** Shared gallery chrome; the light palette uses a softer surface without changing dark mode. */
internal val ColorScheme.galleryOverlay: Color
    get() = if (surface.luminance() > .5f) surfaceContainerLow else surfaceContainer

/** Whether this device can theme Alba from the wallpaper (Android 12 and later). */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
internal fun wallpaperColorsAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Alba's theme: Material 3 with the iCloud blue scheme ([AlbaLightColorScheme], [AlbaDarkColorScheme]), Alba's type
 * scale and shapes. Material 3 1.4 keeps `MotionScheme` and the `MaterialTheme(motionScheme = …)` overload internal,
 * so stock components use Material's standard motion until Alba moves to 1.5.
 *
 * [wallpaperColors] swaps the iCloud scheme for the system's wallpaper scheme on Android 12 and later; it defaults to
 * the user's "Match wallpaper colors" setting ([LocalWallpaperColors]), so a screen that forces dark mode keeps it.
 */
@Composable
fun AlbaTheme(
    darkTheme: Boolean,
    wallpaperColors: Boolean = LocalWallpaperColors.current.enabled,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dynamic = wallpaperColors && wallpaperColorsAvailable()
    val colorScheme = when {
        dynamic && darkTheme -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        darkTheme -> AlbaDarkColorScheme
        else -> AlbaLightColorScheme
    }
    val colors = remember(colorScheme, darkTheme) { albaColors(colorScheme, darkTheme) }
    CompositionLocalProvider(LocalAlbaColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AlbaTypography,
            shapes = AlbaShapes,
            content = content,
        )
    }
}

/** Alba's tokens beyond [MaterialTheme]: `AlbaTheme.colors.favorite`, alongside `MaterialTheme.shapes`. */
internal object AlbaTheme {
    val colors: AlbaColors
        @Composable @ReadOnlyComposable get() = LocalAlbaColors.current
}
