package me.lvca.alba.app.ui

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A hairline between rows of a grouped card. Inset it with [modifier] to align with the row text. */
@Composable
internal fun AlbaDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
}
