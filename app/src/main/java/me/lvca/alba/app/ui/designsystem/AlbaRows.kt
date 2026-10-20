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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
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
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing

/**
 * Alba's tappable list row: an optional [leading] slot, a title with an optional subtitle, and an
 * optional [trailing] slot such as [AlbaChevron]. Pass [selected] for a radio-style row inside a
 * selectableGroup; otherwise the row is a button.
 *
 * The title and leading icon take LocalContentColor, so a caller can dim a disabled row or recolour it
 * for the viewer by providing a different content colour.
 */
@Composable
internal fun AlbaListRow(
    title: String,
    onClick: () -> Unit,
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
    val interaction = if (selected != null) {
        Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    } else {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    Row(modifier.fillMaxWidth().clip(shape).then(interaction).heightIn(min = minHeight).padding(contentPadding),
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

/** The trailing "opens another page" affordance of a list row. */
@Composable
internal fun AlbaChevron(modifier: Modifier = Modifier) {
    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, modifier, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** An explanatory row with an accent icon: onboarding and About use these to describe features. */
@Composable
internal fun AlbaFeatureRow(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(AlbaSpacing.lg), horizontalArrangement = Arrangement.spacedBy(AlbaSpacing.lg)) {
        Icon(icon, null, Modifier.padding(top = AlbaSpacing.xxs).size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AlbaSpacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Where an [AlbaFeatureRow]'s text starts, for dividers that align with it. */
internal val AlbaFeatureRowTextInset = AlbaSpacing.lg + 24.dp + AlbaSpacing.lg

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
        AlbaListRow("Videos", onClick = {}, subtitle = "128 items", leading = { Icon(Icons.Outlined.Info, null) },
            trailing = { AlbaChevron() })
        AlbaDivider(Modifier.padding(start = 68.dp))
        AlbaListRow("Favorites", onClick = {}, leading = { Icon(Icons.Outlined.Info, null) }, trailing = { AlbaChevron() })
    }
    AlbaCard {
        AlbaListRow("Selected option", onClick = {}, selected = true, leading = { Icon(Icons.Outlined.Info, null) })
    }
}

@AlbaPreviews
@Composable
private fun AlbaFeatureRowPreview() = AlbaPreviewSurface {
    AlbaCard {
        AlbaFeatureRow(Icons.Outlined.Info, "Your whole library", "Browse every photo and album without downloading originals.")
        AlbaDivider(Modifier.padding(start = AlbaFeatureRowTextInset, end = AlbaSpacing.lg))
        AlbaFeatureRow(Icons.Outlined.Info, "Offline copies", "Keep the originals you choose on this phone.")
    }
    AlbaFact("Network", "Unmetered internet")
}
