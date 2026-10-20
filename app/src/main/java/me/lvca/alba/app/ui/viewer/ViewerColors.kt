package me.lvca.alba.app.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme

/** Neutral viewer surfaces keep the media in focus and follow the active [AlbaTheme]. */
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
@ReadOnlyComposable
internal fun viewerColors(): ViewerColors = if (AlbaTheme.colors.isDark) DarkViewerColors else LightViewerColors

/** Video controls pick their ink by what is behind them (the video or the viewer canvas), not by theme alone. */
internal data class VideoControlColors(val ink: Color, val track: Color)

internal val VideoControlsOnDark = VideoControlColors(ink = Color(0xFFF4F4F4), track = Color(0xFF929292))
internal val VideoControlsOnLight = VideoControlColors(ink = LightViewerColors.foreground, track = Color(0xFF838489))
