package me.lvca.alba.app.ui.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * Colours Material's scheme has no role for. Read them through [AlbaTheme.colors]; [AlbaTheme] derives them from the
 * active colour scheme ([albaColors]), so a screen that forces a palette, or wallpaper colour, gets matching values.
 */
@Immutable
internal data class AlbaColors(
    /** Whether this is the dark set. Screens can force a palette, so never read the system setting instead. */
    val isDark: Boolean,
    /** A healthy state, such as a verified connection. */
    val success: Color,
    /** A set favourite's filled heart on app surfaces. On photos the heart uses [onMedia]. */
    val favorite: Color,
    /** Base of gradients and badges drawn over photos. Photos are not themed, so this is the same in both sets. */
    val mediaScrim: Color,
    /** Text and icons drawn on photos or on [mediaScrim]. */
    val onMedia: Color,
    /** The drag handle of a panel that rises over the viewer. */
    val sheetHandle: Color,
    /** The photo viewer's canvas, chrome and details panel. */
    val viewer: ViewerColors,
    /** The account card and its artwork, which keep their own palette in both themes. */
    val profile: ProfileColors,
    /** The app icon's sunrise, behind the sign-in page. */
    val dawn: DawnColors,
)

/**
 * The app icon's dawn, top to bottom: violet sky, a magenta-pink band, coral, then the warm yellow of the sun. The light
 * set is sampled from the icon; the dark set is the same hues deepened to dusk, so it isn't a bright block at night.
 * White text belongs on [sky] and [skyHigh] only: the lower stops are too light for it.
 */
@Immutable
internal data class DawnColors(
    val sky: Color,
    val skyHigh: Color,
    val rose: Color,
    val coral: Color,
    val horizon: Color,
    val sun: Color,
    /** Text and icons drawn on the sky. */
    val onSky: Color = Color.White,
)

/** Neutral viewer surfaces that keep the media in focus: white or black canvas with soft greys around it. */
@Immutable
internal data class ViewerColors(
    val canvas: Color,
    val foreground: Color,
    val secondary: Color,
    val card: Color,
    val chip: Color,
    val disabled: Color,
    /** Behind a playing video or hidden chrome, black in both themes like Google Photos. */
    val immersive: Color = Color.Black,
    /** Video controls drawn over the video itself or the black canvas. */
    val videoOnDark: VideoControlColors = VideoControlsOnDark,
    /** Video controls drawn over the light canvas. */
    val videoOnLight: VideoControlColors = VideoControlsOnLight,
)

/** Video controls pick their ink by what is behind them (the video or the viewer canvas), not by theme alone. */
@Immutable
internal data class VideoControlColors(val ink: Color, val track: Color)

private val VideoControlsOnDark = VideoControlColors(ink = Color(0xFFF4F4F4), track = Color(0xFF929292))
private val VideoControlsOnLight = VideoControlColors(ink = Color(0xFF30323A), track = Color(0xFF838489))

/** The profile's fixed colours: settings-style icon tiles and the iCloud storage categories, the same in both themes. */
@Immutable
internal data class ProfileColors(
    /** The white glyph on each icon tile. */
    val onCard: Color = Color.White,
    // Settings-style icon tiles carrying a white glyph.
    val backupTile: Color = SuccessTile,
    val phoneTile: Color = Color(0xFF777C88),
    val aboutTile: Color = Color(0xFF8A5CCD),
    val storage: StorageColors = StorageColors(),
)

/** Fixed per-category colours for the iCloud storage bar and its legend. */
@Immutable
internal data class StorageColors(
    val photos: Color = Color(0xFFF2B82F),
    val documents: Color = Color(0xFFF18B29),
    val backups: Color = Color(0xFF8272E8),
    val messages: Color = Color(0xFF43AB69),
    val mail: Color = Color(0xFF238BE6),
    val family: Color = Color(0xFF18AAA8),
    val other: Color = Color(0xFF858B96),
)

/*
 * The two status hues, harmonized toward the iCloud seed (#0071E3) with Material's Blend.harmonize, then taken from
 * their tonal palettes at tones that pass 3:1 against every surface of the static schemes: the favourite red at tone
 * 50 (3.47:1 on light surfaceContainerHighest, 4.48:1 on white) and tone 70 in dark (5.37:1 on surfaceContainerHighest,
 * 9.15:1 on the black viewer); the success green at tone 40 (5.00:1) and tone 70 (5.37:1). ColorSchemeContrastTest
 * checks them.
 */
private val FavoriteLight = Color(0xFFE71B58)
private val FavoriteDark = Color(0xFFFF8798)
private val SuccessLight = Color(0xFF006C48)
private val SuccessDark = Color(0xFF00C385)
private val SuccessTile = Color(0xFF268858)

/** Material's opacity for a bottom sheet's drag handle (onSurfaceVariant at 40%). */
private const val HANDLE_ALPHA = .4f

/** Material's opacity for disabled content. */
private const val DISABLED_ALPHA = .38f

/** Alba's extra colours for [scheme]; everything but the status hues and the photo-bound colours follows its roles. */
internal fun albaColors(scheme: ColorScheme, isDark: Boolean) = AlbaColors(
    isDark = isDark,
    success = if (isDark) SuccessDark else SuccessLight,
    favorite = if (isDark) FavoriteDark else FavoriteLight,
    mediaScrim = Color.Black,
    onMedia = Color.White,
    sheetHandle = scheme.onSurfaceVariant.copy(alpha = HANDLE_ALPHA).compositeOver(scheme.surface),
    // The canvas stays pure white or black like Google Photos'; the chrome and panels use the scheme's neutrals.
    viewer = ViewerColors(
        canvas = if (isDark) Color.Black else Color.White,
        foreground = scheme.onSurface,
        secondary = scheme.onSurfaceVariant,
        card = scheme.surfaceContainer,
        chip = scheme.surfaceContainerHighest,
        disabled = scheme.onSurface.copy(alpha = DISABLED_ALPHA).compositeOver(if (isDark) Color.Black else Color.White),
    ),
    profile = ProfileColors(),
    dawn = if (isDark) DawnDark else DawnLight,
)

private val DawnLight = DawnColors(
    sky = Color(0xFF7558CC), skyHigh = Color(0xFF8C63CC), rose = Color(0xFFD372A4),
    coral = Color(0xFFF1888A), horizon = Color(0xFFF8A270), sun = Color(0xFFF5C46F),
)

private val DawnDark = DawnColors(
    sky = Color(0xFF241A4D), skyHigh = Color(0xFF34225E), rose = Color(0xFF6A2F62),
    coral = Color(0xFF8C3F55), horizon = Color(0xFF9F5644), sun = Color(0xFFC98A4A),
)

internal val LocalAlbaColors = staticCompositionLocalOf { albaColors(AlbaLightColorScheme, isDark = false) }
