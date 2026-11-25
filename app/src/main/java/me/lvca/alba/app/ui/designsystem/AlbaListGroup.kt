package me.lvca.alba.app.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing

/**
 * Android 16's grouped list: a section of settings-style rows, each its own rounded segment on surfaceContainer,
 * separated by small gaps instead of dividers. The group rounds its outer corners with the theme's large shape; rows
 * inside keep small inner corners. There are no chevrons: a row that opens a page looks like any other row.
 */
internal object AlbaListDefaults {
    /** The gap between segments, through which the page shows. */
    val SegmentGap = 2.dp

    /** One padding for every row and segment, on both sides. */
    val Padding = AlbaSpacing.lg

    /** The inner corners of a segment; the group's outer corners come from [MaterialTheme.shapes.large]. */
    val segmentShape: Shape @Composable get() = MaterialTheme.shapes.extraSmall
}

/** True inside an [AlbaListGroup], where rows draw themselves as segments. */
internal val LocalInAlbaListGroup = compositionLocalOf { false }

/** Draws a row or block as one segment of the [AlbaListGroup] it is in; does nothing outside a group. */
@Composable
internal fun Modifier.albaListSegment(): Modifier =
    if (LocalInAlbaListGroup.current) fillMaxWidth().background(albaGroupColor(), AlbaListDefaults.segmentShape) else this

/**
 * A grouped list section: an optional [title] header in labelLarge and the primary colour, then [content]'s rows as
 * rounded segments. Use [AlbaListRow], [AlbaSwitchRow], [AlbaFeatureRow] and, for anything else, [AlbaListItem].
 */
@Composable
internal fun AlbaListGroup(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        title?.let { AlbaListHeader(it) }
        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large), verticalArrangement = Arrangement.spacedBy(AlbaListDefaults.SegmentGap)) {
            CompositionLocalProvider(LocalInAlbaListGroup provides true) { content() }
        }
    }
}

/** A grouped list's section header, for a group or for a note that sits between the header and its group. */
@Composable
internal fun AlbaListHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().padding(start = AlbaListDefaults.Padding, end = AlbaListDefaults.Padding, bottom = AlbaSpacing.sm)
        .semantics { heading() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/** A segment of an [AlbaListGroup] holding content that is not a row: a status, a note, a button. */
@Composable
internal fun AlbaListItem(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(AlbaListDefaults.Padding),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(AlbaSpacing.sm),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.albaListSegment().padding(contentPadding), verticalArrangement = verticalArrangement, content = content)
}

/**
 * A setting that is on or off: the whole row toggles, and the stock Material switch shows the state. [modifier] goes on
 * the toggleable row, so a test tag there finds the switch's state.
 */
@Composable
internal fun AlbaSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        Modifier.albaListSegment().clip(AlbaListDefaults.segmentShape).heightIn(min = 56.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .then(modifier).padding(horizontal = AlbaListDefaults.Padding, vertical = AlbaSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AlbaSpacing.lg),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AlbaSpacing.xxs)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@AlbaPreviews
@Composable
private fun AlbaListGroupPreview() = AlbaPreviewSurface {
    AlbaListGroup(title = "Library") {
        AlbaListRow("Backup", onClick = {}, subtitle = "On", leading = { Icon(Icons.Rounded.Info, null) })
        AlbaListRow("Phone storage", onClick = {}, subtitle = "Alba uses 1.2 GB", leading = { Icon(Icons.Rounded.Info, null) })
        AlbaSwitchRow("Pause backup", checked = false, onCheckedChange = {}, subtitle = "Until you turn it back on")
        AlbaListItem { Text("Thumbnails are kept unless you clear them.", style = MaterialTheme.typography.bodySmall) }
    }
}
