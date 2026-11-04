package me.lvca.alba.app.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.designsystem.theme.ViewerColors

/** The viewer's colour set for the active [AlbaTheme]; see [ViewerColors]. */
@Composable
@ReadOnlyComposable
internal fun viewerColors(): ViewerColors = AlbaTheme.colors.viewer
