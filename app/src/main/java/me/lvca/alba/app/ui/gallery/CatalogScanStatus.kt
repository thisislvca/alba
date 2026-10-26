package me.lvca.alba.app.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.engine.model.CatalogScanProgress
import java.text.NumberFormat

/** "12,400 of 52,000 photos loaded", or without the total when iCloud did not say how many there are. */
@Composable
internal fun catalogScanLabel(progress: CatalogScanProgress): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val loaded = format.format(progress.loadedCount)
    val total = progress.totalCount?.takeIf { it > 0 && it >= progress.loadedCount }
    return if (total != null) pluralStringResource(R.plurals.catalog_scan_progress_of_total, total, loaded, format.format(total))
    else pluralStringResource(R.plurals.catalog_scan_progress, progress.loadedCount, loaded)
}

/** How far the first read of the library is, from 0 to 1, when iCloud said how many items there are. */
internal fun CatalogScanProgress.fraction(): Float? =
    totalCount?.takeIf { it > 0 }?.let { (loadedCount.toFloat() / it).coerceIn(0f, 1f) }

/**
 * The empty library while photos load: a spinner while a refresh runs, how many photos the first read has loaded so
 * far, and that a large library takes a while. Photos appear above it page by page, so it shows only until the first.
 */
@Composable
internal fun LibraryLoading(progress: CatalogScanProgress?, refreshing: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (refreshing) CircularProgressIndicator(Modifier.padding(bottom = 8.dp))
        Text(progress?.let { catalogScanLabel(it) } ?: stringResource(R.string.loading_photos),
            Modifier.testTag("catalog-scan-progress"), textAlign = TextAlign.Center)
        Text(stringResource(R.string.catalog_scan_large_library), Modifier.testTag("catalog-scan-note"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
