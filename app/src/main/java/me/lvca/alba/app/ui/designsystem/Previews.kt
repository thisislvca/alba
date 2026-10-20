package me.lvca.alba.app.ui.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.gallery.EmptyLibrary
import me.lvca.alba.app.ui.gallery.FloatingGalleryNavigation
import me.lvca.alba.app.ui.gallery.FloatingGridDate
import me.lvca.alba.app.ui.gallery.GalleryTab
import me.lvca.alba.app.ui.gallery.MonthHeader
import java.time.YearMonth

/** Shows a preview in both of Alba's palettes. Keep preview functions private to their component's file. */
@Preview(name = "Light", showBackground = true, widthDp = 360)
@Preview(name = "Dark", showBackground = true, widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
internal annotation class AlbaPreviews

/** The preview's night mode picks the palette, as the device setting does in the app. */
@Composable
internal fun AlbaPreviewSurface(content: @Composable ColumnScope.() -> Unit) {
    AlbaTheme(darkTheme = isSystemInDarkTheme()) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(AlbaSpacing.lg), verticalArrangement = Arrangement.spacedBy(AlbaSpacing.md), content = content)
        }
    }
}

// Previews of existing stateless leaf components. The design-system components preview in their own files.

@AlbaPreviews
@Composable
private fun GalleryChromePreview() = AlbaPreviewSurface {
    MonthHeader(YearMonth.of(2026, 10), 128)
    FloatingGridDate(YearMonth.of(2026, 10), dates = {})
    FloatingGalleryNavigation(GalleryTab.LIBRARY, navigate = {})
    FloatingGalleryNavigation(GalleryTab.SEARCH, navigate = {})
}

@AlbaPreviews
@Composable
private fun EmptyStatesPreview() = AlbaPreviewSurface {
    AlbaSheetHeading("Browse by date", "Jump to any month", dismiss = {})
    EmptyLibrary()
}
