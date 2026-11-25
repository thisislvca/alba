package me.lvca.alba.app.ui.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val MaterialDefaults = Typography()

/**
 * Material 3's type scale in the system font: every role keeps Material's size, line height, weight and line-height
 * style. Only the tracking is Alba's, and only where text is large: display and headline roles are set slightly
 * tighter, the bigger the tighter, as large type is. Title, body and label roles keep Material's tracking.
 */
internal val AlbaTypography = with(MaterialDefaults) {
    Typography(
        displayLarge = displayLarge.copy(letterSpacing = (-0.5).sp), // 57/64sp
        displayMedium = displayMedium.copy(letterSpacing = (-0.4).sp), // 45/52sp
        displaySmall = displaySmall.copy(letterSpacing = (-0.3).sp), // 36/44sp
        headlineLarge = headlineLarge.copy(letterSpacing = (-0.25).sp), // 32/40sp
        headlineMedium = headlineMedium.copy(letterSpacing = (-0.2).sp), // 28/36sp
        headlineSmall = headlineSmall.copy(letterSpacing = (-0.1).sp), // 24/32sp
        titleLarge = titleLarge,
        titleMedium = titleMedium,
        titleSmall = titleSmall,
        bodyLarge = bodyLarge,
        bodyMedium = bodyMedium,
        bodySmall = bodySmall,
        labelLarge = labelLarge,
        labelMedium = labelMedium,
        labelSmall = labelSmall,
    )
}

/** The album title laid over its cover in the album hero: emphasised so it holds its own over a photo. */
internal val Typography.heroTitle: TextStyle get() = headlineLarge.copy(fontWeight = FontWeight.Bold)

/** The capture date in the photo viewer header: titleMedium, so the photo leads. */
internal val Typography.viewerTitle: TextStyle get() = titleMedium

/** Floating navigation labels: GoogleMaterial3 LabelLarge in the inspected Photos resources (14sp medium, 20sp line, no tracking). */
internal val Typography.navigationLabel: TextStyle
    get() = labelLarge.copy(letterSpacing = 0.sp)
