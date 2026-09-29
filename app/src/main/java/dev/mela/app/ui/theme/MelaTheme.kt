package dev.mela.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

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

private val MelaTypography = Typography(
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
)

@Composable
fun MelaTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MelaTypography,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

