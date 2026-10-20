package me.lvca.alba.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.ui.theme.AlbaSpacing

/** Grouped cards keep utility screens quiet while the gallery remains image-led. */
@Composable
internal fun albaGroupColor() = MaterialTheme.colorScheme.surfaceContainerLowest

/**
 * Alba's grouped card: a full-width rounded surface holding a column of rows or a block of content.
 * Pass [onClick] for a card that is itself the tap target.
 */
@Composable
internal fun AlbaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = albaGroupColor(),
    contentColor: Color = contentColorFor(color),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(contentPadding), verticalArrangement = verticalArrangement, content = content)
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = color,
            contentColor = contentColor, content = body)
    } else {
        Surface(modifier.fillMaxWidth(), shape = shape, color = color, contentColor = contentColor, content = body)
    }
}

/** A hairline between rows of a grouped card. Inset it with [modifier] to align with the row text. */
@Composable
internal fun AlbaDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
}

@AlbaPreviews
@Composable
private fun AlbaCardPreview() = AlbaPreviewSurface {
    AlbaCard(contentPadding = PaddingValues(AlbaSpacing.lg)) {
        Text("Connection", style = MaterialTheme.typography.titleMedium)
        AlbaDivider(Modifier.padding(vertical = AlbaSpacing.md))
        Text("Apple session verified", style = MaterialTheme.typography.bodyMedium)
    }
}
