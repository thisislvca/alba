package me.lvca.alba.app.ui.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import me.lvca.alba.app.ui.designsystem.theme.cover

/**
 * The frame of every album and collection cover: one clip to [cover][androidx.compose.material3.Shapes.cover], with the
 * image filling it edge to edge. There is no inset and no container colour of its own, so the image's corners and the
 * cover's corners are the same corners. Clip the card that holds a cover (and its ripple) to the same shape.
 */
@Composable
internal fun AlbaCover(modifier: Modifier = Modifier, contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(MaterialTheme.shapes.cover), contentAlignment = contentAlignment, content = content)
}
