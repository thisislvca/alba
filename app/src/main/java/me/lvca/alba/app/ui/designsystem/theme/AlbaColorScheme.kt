package me.lvca.alba.app.ui.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Alba's static Material 3 colour schemes, generated once from the iCloud blue seed #0071E3 with Material's official
 * colour utilities (@material/material-color-utilities 0.3.0, HCT): a DynamicScheme of the Fidelity variant, standard
 * contrast, so the primary palette keeps the seed's hue and chroma (hue 264, chroma 64.6). The other palettes:
 *
 * - secondary: the seed hue at chroma 16 (Tonal Spot's secondary), for tonal buttons and chips;
 * - tertiary: hue 204 (the seed hue - 60, a quiet teal) at chroma 32;
 * - neutral and neutral variant: the seed hue at chroma 4 and 8, cool and quiet so photos stay the hero.
 *
 * Every role, the fixed accent roles included, comes from MaterialDynamicColors, so no Material baseline (purple)
 * value is left for a component to read. Primary is tone 38.7 in light (6.47:1 on surface, 5.26:1 on
 * surfaceContainerHighest) and tone 80 in dark (10.92:1 on surface, 7.21:1 on surfaceContainerHighest): above 4.5:1
 * for text and 3:1 for icons on every surface.
 * ColorSchemeContrastTest checks these ratios. Regenerate rather than hand-edit a value.
 */

internal val AlbaLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0059B5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF0071E3),
    onPrimaryContainer = Color(0xFFFCFBFF),
    inversePrimary = Color(0xFFABC7FF),
    secondary = Color(0xFF565E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF5C6477),
    tertiary = Color(0xFF14656B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF367E84),
    onTertiaryContainer = Color(0xFFF0FEFF),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFFAF9FD),
    onBackground = Color(0xFF1A1B1F),
    surface = Color(0xFFFAF9FD),
    onSurface = Color(0xFF1A1B1F),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceTint = Color(0xFF005CBB),
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF2F0F4),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFAF9FD),
    surfaceDim = Color(0xFFDBD9DD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F3F7),
    surfaceContainer = Color(0xFFEFEDF1),
    surfaceContainerHigh = Color(0xFFE9E7EC),
    surfaceContainerHighest = Color(0xFFE3E2E6),
    primaryFixed = Color(0xFFD7E2FF),
    primaryFixedDim = Color(0xFFABC7FF),
    onPrimaryFixed = Color(0xFF001B3F),
    onPrimaryFixedVariant = Color(0xFF00458F),
    secondaryFixed = Color(0xFFDAE2F9),
    secondaryFixedDim = Color(0xFFBEC6DC),
    onSecondaryFixed = Color(0xFF131C2B),
    onSecondaryFixedVariant = Color(0xFF3E4759),
    tertiaryFixed = Color(0xFFA8EEF5),
    tertiaryFixedDim = Color(0xFF8CD2D8),
    onTertiaryFixed = Color(0xFF002022),
    onTertiaryFixedVariant = Color(0xFF004F54),
)

/** Near-black, with containers that get lighter as they rise (lowest tone 4 … highest tone 22). */
internal val AlbaDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFABC7FF),
    onPrimary = Color(0xFF002F66),
    primaryContainer = Color(0xFF0071E3),
    onPrimaryContainer = Color(0xFFFCFBFF),
    inversePrimary = Color(0xFF005CBB),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283041),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFADB5CA),
    tertiary = Color(0xFF8CD2D8),
    onTertiary = Color(0xFF00363A),
    tertiaryContainer = Color(0xFF367E84),
    onTertiaryContainer = Color(0xFFF0FEFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceTint = Color(0xFFABC7FF),
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF2F3033),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF38393C),
    surfaceDim = Color(0xFF121316),
    surfaceContainerLowest = Color(0xFF0D0E11),
    surfaceContainerLow = Color(0xFF1A1B1F),
    surfaceContainer = Color(0xFF1F1F23),
    surfaceContainerHigh = Color(0xFF292A2D),
    surfaceContainerHighest = Color(0xFF343538),
    primaryFixed = Color(0xFFD7E2FF),
    primaryFixedDim = Color(0xFFABC7FF),
    onPrimaryFixed = Color(0xFF001B3F),
    onPrimaryFixedVariant = Color(0xFF00458F),
    secondaryFixed = Color(0xFFDAE2F9),
    secondaryFixedDim = Color(0xFFBEC6DC),
    onSecondaryFixed = Color(0xFF131C2B),
    onSecondaryFixedVariant = Color(0xFF3E4759),
    tertiaryFixed = Color(0xFFA8EEF5),
    tertiaryFixedDim = Color(0xFF8CD2D8),
    onTertiaryFixed = Color(0xFF002022),
    onTertiaryFixedVariant = Color(0xFF004F54),
)
