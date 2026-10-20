package me.lvca.alba.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours Material's scheme has no role for. Read them through [AlbaTheme.colors]; [AlbaTheme] provides
 * the light or dark set, so a screen that forces a palette gets matching values.
 */
@Immutable
internal data class AlbaColors(
    /** Whether this is the dark set. Screens can force a palette, so never read the system setting instead. */
    val isDark: Boolean,
    /** iCloud's brand blue, for collection and media-type glyphs. */
    val accent: Color,
    /** A healthy state, such as a verified connection. */
    val success: Color,
    /** Base of gradients and badges drawn over photos. Photos are not themed, so this is the same in both sets. */
    val mediaScrim: Color,
    /** Text and icons drawn on photos or on [mediaScrim]. */
    val onMedia: Color,
    /** The drag handle of a panel that rises over the viewer. */
    val sheetHandle: Color,
    /** Initial avatars on album covers. */
    val avatar: Color,
    val onAvatar: Color,
    /** The account card and its artwork, which keep their own palette in both themes. */
    val profile: ProfileColors,
)

/** The profile hero card is a fixed illustration, so its colours do not change with the theme. */
@Immutable
internal data class ProfileColors(
    val card: Color = Color(0xFF082958),
    val onCard: Color = Color.White,
    val onCardMuted: Color = Color(0xFFE8ECFF),
    /** Translucent chips on the card, such as the demo badge and the storage allowance. */
    val badge: Color = Color(0xFF332878).copy(alpha = .40f),
    /** Text on the white call-to-action button. */
    val action: Color = Color(0xFF08386F),
    // Settings-style icon tiles carrying a white glyph.
    val backupTile: Color = SuccessGreen,
    val phoneTile: Color = Color(0xFF777C88),
    val aboutTile: Color = Color(0xFF8A5CCD),
    // Artwork: a cool sky gradient opening into lavender, a saturated plane and a faint facet.
    val skyStart: Color = Color(0xFF9BCFFF),
    val skyMiddle: Color = Color(0xFFA18FFF),
    val skyEnd: Color = Color(0xFF2D43D2),
    val planeStart: Color = Color(0xFF62C2FF),
    val planeAzure: Color = Color(0xFF388BE9),
    val planeIndigo: Color = Color(0xFF5364DE),
    val planeEnd: Color = Color(0xFF8750D6),
    val facetStart: Color = Color(0xFF5B53E9).copy(alpha = .16f),
    val facetEnd: Color = Color(0xFF8270FF).copy(alpha = .02f),
    val avatarCloudShadow: Color = Color(0x55202D70),
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

private val SuccessGreen = Color(0xFF268858)

internal val LightAlbaColors = AlbaColors(
    isDark = false,
    accent = Color(0xFF009FFF),
    success = SuccessGreen,
    mediaScrim = Color.Black,
    onMedia = Color.White,
    sheetHandle = Color(0xFFDADCE6),
    avatar = Color(0xFFEEECE8),
    onAvatar = Color(0xFF35383C),
    profile = ProfileColors(),
)

internal val DarkAlbaColors = AlbaColors(
    isDark = true,
    accent = Color(0xFF3DB4FF),
    success = Color(0xFF4CB782),
    mediaScrim = Color.Black,
    onMedia = Color.White,
    sheetHandle = Color(0xFF4A4D57),
    avatar = Color(0xFF3A3D44),
    onAvatar = Color(0xFFE5E8EF),
    profile = ProfileColors(),
)

internal val LocalAlbaColors = staticCompositionLocalOf { LightAlbaColors }
