package me.lvca.alba.app.ui.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.ui.designsystem.theme.AlbaIconSize
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing

/**
 * Alba's list row: an optional [leading] slot, a title with an optional subtitle, and an optional [trailing] slot.
 * Pass [selected] for a radio-style row inside a selectableGroup; otherwise the row is a button, or plain when
 * [onClick] is null (its trailing slot holding the action). Inside an [AlbaListGroup] it draws itself as a segment.
 *
 * The title and leading icon take LocalContentColor, so a caller can dim a disabled row or recolour it
 * for the viewer by providing a different content colour.
 */
@Composable
internal fun AlbaListRow(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    selected: Boolean? = null,
    leading: (@Composable () -> Unit)? = null,
    /** The box the leading content is centred in, so titles align across rows; null lets it size itself. */
    leadingSize: Dp? = 40.dp,
    trailing: (@Composable () -> Unit)? = null,
    /** Clips the press ripple. Rows inside an [AlbaCard] keep the card's shape and stay rectangular. */
    shape: Shape = RectangleShape,
    contentPadding: PaddingValues = PaddingValues(horizontal = AlbaSpacing.lg, vertical = 14.dp),
    gap: Dp = AlbaSpacing.md,
    minHeight: Dp = 56.dp,
) {
    val interaction = when {
        onClick == null -> Modifier
        selected != null -> Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        else -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    val grouped = LocalInAlbaListGroup.current
    Row(modifier.albaListSegment().fillMaxWidth().clip(if (grouped) AlbaListDefaults.segmentShape else shape).then(interaction)
        .heightIn(min = minHeight).padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
        if (leading != null) {
            if (leadingSize == null) leading()
            else Box(Modifier.size(leadingSize), contentAlignment = Alignment.Center) { leading() }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AlbaSpacing.xxs)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

/** The trailing "opens another page" affordance of a browsing row. Settings rows in an [AlbaListGroup] have none. */
@Composable
internal fun AlbaChevron(modifier: Modifier = Modifier) {
    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, modifier, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** An explanatory row with an accent icon: onboarding and About use these to describe features. */
@Composable
internal fun AlbaFeatureRow(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Row(modifier.albaListSegment().fillMaxWidth().padding(AlbaSpacing.lg), horizontalArrangement = Arrangement.spacedBy(AlbaSpacing.lg)) {
        Icon(icon, null, Modifier.padding(top = AlbaSpacing.xxs).size(AlbaIconSize.standard), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AlbaSpacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A labelled value, such as a connection detail or a photo's camera. */
@Composable
internal fun AlbaFact(label: String, value: String, modifier: Modifier = Modifier,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AlbaSpacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@AlbaPreviews
@Composable
private fun AlbaListRowPreview() = AlbaPreviewSurface {
    AlbaCard {
        AlbaListRow("Videos", onClick = {}, subtitle = "128 items", leading = { Icon(Icons.Rounded.Info, null) },
            trailing = { AlbaChevron() })
        AlbaDivider(Modifier.padding(start = 68.dp))
        AlbaListRow("Favorites", onClick = {}, leading = { Icon(Icons.Rounded.Info, null) }, trailing = { AlbaChevron() })
    }
    AlbaCard {
        AlbaListRow("Selected option", onClick = {}, selected = true, leading = { Icon(Icons.Rounded.Info, null) })
    }
}

@AlbaPreviews
@Composable
private fun AlbaFeatureRowPreview() = AlbaPreviewSurface {
    AlbaListGroup(title = "Features") {
        AlbaFeatureRow(Icons.Rounded.Info, "Your whole library", "Browse every photo and album without downloading originals.")
        AlbaFeatureRow(Icons.Rounded.Info, "Offline copies", "Keep the originals you choose on this phone.")
    }
    AlbaFact("Network", "Unmetered internet")
}
