package me.lvca.alba.app.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme

/** How far a [MediaScrim] darkens the photo beneath it, as alpha stops from top to bottom. */
internal enum class MediaScrimStrength(vararg val alphas: Float) {
    /** Grid tiles: just enough behind a duration or an offline badge. */
    Subtle(0f, .6f),

    /** Covers with titles: keeps the top of the photo clear, then darkens strongly behind the text. */
    Strong(0f, .12f, .85f),
}

/** A vertical gradient that keeps text and icons drawn over a photo legible. Size and place it with [modifier]. */
@Composable
internal fun MediaScrim(modifier: Modifier = Modifier, strength: MediaScrimStrength = MediaScrimStrength.Subtle) {
    val scrim = AlbaTheme.colors.mediaScrim
    val brush = remember(scrim, strength) { Brush.verticalGradient(strength.alphas.map { scrim.copy(alpha = it) }) }
    Box(modifier.background(brush))
}

/**
 * A dark capsule for labels drawn directly on photos, such as the Live badge. [strong] is for labels that
 * float over a moving grid. Content defaults to [me.lvca.alba.app.ui.designsystem.theme.AlbaColors.onMedia].
 */
@Composable
internal fun MediaPill(
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 7.dp, vertical = AlbaSpacing.xs),
    content: @Composable RowScope.() -> Unit,
) {
    val colors = AlbaTheme.colors
    Row(modifier.background(colors.mediaScrim.copy(alpha = if (strong) .82f else .65f), CircleShape).padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AlbaSpacing.xs)) {
        CompositionLocalProvider(LocalContentColor provides colors.onMedia) { content() }
    }
}

/** A small capsule label on app chrome, such as the demo marker. [large] is for hero surfaces. */
@Composable
internal fun AlbaBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = contentColorFor(color),
    large: Boolean = false,
) {
    Surface(modifier, shape = CircleShape, color = color, contentColor = contentColor) {
        Text(text, Modifier.padding(horizontal = if (large) 10.dp else 6.dp, vertical = if (large) 5.dp else 3.dp),
            style = if (large) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Marks the sample library wherever the account would otherwise be named. */
@Composable
internal fun DemoBadge(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = contentColorFor(color),
    large: Boolean = false,
) = AlbaBadge(stringResource(R.string.demo_badge), modifier, color, contentColor, large)

@AlbaPreviews
@Composable
private fun MediaOverlaysPreview() = AlbaPreviewSurface {
    Box(Modifier.fillMaxWidth().height(96.dp).background(MaterialTheme.colorScheme.primary)) {
        MediaScrim(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(44.dp))
        MediaPill(Modifier.align(Alignment.TopStart).padding(6.dp)) {
            Icon(painterResource(R.drawable.icloud_live_photos), null, Modifier.size(13.dp))
            Text(stringResource(R.string.live_badge), style = MaterialTheme.typography.labelSmall)
        }
        MediaPill(Modifier.align(Alignment.TopEnd).padding(6.dp), strong = true) {
            Text("2024", style = MaterialTheme.typography.labelMedium)
        }
    }
    DemoBadge()
    DemoBadge(color = AlbaTheme.colors.profile.badge, contentColor = AlbaTheme.colors.profile.onCard, large = true)
}
