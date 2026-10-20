package me.lvca.alba.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.ui.theme.AlbaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A circular avatar frame; [content] is centred and takes [contentColor]. */
@Composable
internal fun AlbaAvatar(
    size: Dp,
    color: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    shadowElevation: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(modifier.size(size), shape = CircleShape, color = color, contentColor = contentColor, shadowElevation = shadowElevation) {
        Box(contentAlignment = Alignment.Center, content = content)
    }
}

/** The first letter of [name], or a generic account glyph when there is no name. */
@Composable
internal fun InitialAvatar(
    name: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = AlbaTheme.colors.avatar,
    contentColor: Color = AlbaTheme.colors.onAvatar,
    textStyle: TextStyle = MaterialTheme.typography.titleSmall,
    shadowElevation: Dp = 0.dp,
) {
    AlbaAvatar(size, color, contentColor, modifier, shadowElevation) {
        val initial = name?.trim()?.firstOrNull()?.uppercaseChar()?.toString()
        if (initial == null) Icon(Icons.Outlined.AccountCircle, null, Modifier.size(size * .77f))
        else Text(initial, style = textStyle)
    }
}

/** The signed-in account's photo, or a person glyph. Drawn in the surrounding content colour. */
@Composable
internal fun AccountAvatar(photo: ByteArray?, avatarSize: Dp = 56.dp) {
    // Bound decoded dimensions as well as download size; a compressed image can be enormous.
    val bitmap by produceState<ImageBitmap?>(null, photo) {
        value = null
        if (photo != null) value = withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(photo, 0, photo.size, bounds)
            if (bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384) null else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 256) inSampleSize *= 2
                }
                BitmapFactory.decodeByteArray(photo, 0, photo.size, options)?.asImageBitmap()
            }
        }
    }
    val ink = LocalContentColor.current
    AlbaAvatar(avatarSize, color = ink.copy(alpha = .12f), contentColor = ink) {
        bitmap?.let { Image(it, null, Modifier.size(avatarSize).testTag("account-photo"), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Outlined.Person, null, Modifier.size(avatarSize * .55f))
    }
}

@AlbaPreviews
@Composable
private fun AvatarPreview() = AlbaPreviewSurface {
    InitialAvatar("Luca", 34.dp, shadowElevation = 2.dp)
    InitialAvatar(null, 34.dp, shadowElevation = 2.dp)
    InitialAvatar("alba@example.com", 36.dp, color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer, textStyle = MaterialTheme.typography.titleMedium)
    AccountAvatar(null)
}
