package me.lvca.alba.app.ui.designsystem.theme

import androidx.compose.ui.unit.dp

/**
 * The icon sizes shared by Alba's screens. Material's 24dp is the default; the others are for glyphs that sit
 * inside a small badge or beside a button label, or that lead a card or an empty state.
 */
internal object AlbaIconSize {
    /** A glyph on a small disc or badge drawn over a photo. */
    val badge = 14.dp

    /** Beside a button or chip label, Material's 18dp. */
    val inline = 18.dp

    /** A dense glyph, inside a 32dp tile such as the profile rows' coloured squares or in a compact notice. */
    val compact = 20.dp

    /** Material's standard icon. */
    val standard = 24.dp

    /** A leading glyph on a card, a notice or a tonal tile. */
    val large = 32.dp

    /** The glyph that heads an empty or error state. */
    val hero = 40.dp

    /** The glyph of an onboarding illustration. */
    val display = 48.dp
}
