package dev.mela.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Neutral viewer surfaces keep the media in focus and follow the device theme. */
internal data class ViewerColors(
    val canvas: Color,
    val foreground: Color,
    val secondary: Color,
    val card: Color,
    val chip: Color,
    val disabled: Color,
)

private val LightViewerColors = ViewerColors(
    Color.White, Color(0xFF30323A), Color(0xFF5D5F68),
    Color(0xFFEDEDF6), Color(0xFFE3E3EA), Color(0xFF9A9BA0),
)
private val DarkViewerColors = ViewerColors(
    Color.Black, Color(0xFFE3E3E3), Color(0xFFC4C7C5),
    Color(0xFF1F1F1F), Color(0xFF303030), Color(0xFF777777),
)

@Composable
internal fun viewerColors(): ViewerColors = if (isSystemInDarkTheme()) DarkViewerColors else LightViewerColors
