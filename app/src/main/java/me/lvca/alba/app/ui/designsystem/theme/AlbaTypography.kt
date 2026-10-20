package me.lvca.alba.app.ui.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val MaterialDefaults = Typography()

internal val AlbaTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        letterSpacing = (-1.1).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.35).sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 17.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    // These roles keep Material's metrics (and its line-height style), set in Alba's family and weights.
    headlineLarge = MaterialDefaults.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold), // 32/40sp
    titleSmall = MaterialDefaults.titleSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium), // 14/20sp
    labelSmall = MaterialDefaults.labelSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium), // 11/16sp
)

/** The album title laid over its cover in the album hero. */
internal val Typography.heroTitle: TextStyle get() = headlineLarge.copy(fontWeight = FontWeight.Bold)

/** The capture date in the photo viewer header: quieter than titleLarge so the photo leads. */
internal val Typography.viewerTitle: TextStyle
    get() = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 22.sp)

/** Floating navigation labels: GoogleMaterial3 LabelLarge in the inspected Photos resources (14sp medium, 20sp line, no tracking). */
internal val Typography.navigationLabel: TextStyle
    get() = labelLarge.copy(fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.sp)
