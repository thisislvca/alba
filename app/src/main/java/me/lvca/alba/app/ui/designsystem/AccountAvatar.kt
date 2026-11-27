package me.lvca.alba.app.ui.designsystem

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion
import java.text.Normalizer
import java.util.Locale

/** A circular avatar frame; [content] is centred and takes [contentColor]. */
@Composable
internal fun AlbaAvatar(
    size: Dp,
    color: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(modifier.size(size), shape = CircleShape, color = color, contentColor = contentColor, shadowElevation = shadowElevation,
        border = border) {
        Box(contentAlignment = Alignment.Center, content = content)
    }
}

/** The top and bottom of a monogram's vertical gradient, muted as Apple's Contacts monograms are. */
internal data class MonogramGradient(val top: Color, val bottom: Color)

/**
 * Monogram backgrounds. The first is Apple's grey, also used for an avatar with no name; the others are muted tints of
 * the same depth, so white initials read on all of them, in the light and dark themes alike.
 */
internal val MonogramGradients = listOf(
    MonogramGradient(Color(0xFFA5ABB6), Color(0xFF858A94)), // grey
    MonogramGradient(Color(0xFFAFA6C6), Color(0xFF8A80A3)), // lavender
    MonogramGradient(Color(0xFF9DAEC2), Color(0xFF78899E)), // blue-grey
    MonogramGradient(Color(0xFFA2B3A7), Color(0xFF7E8F83)), // sage
    MonogramGradient(Color(0xFFBBAC9C), Color(0xFF968877)), // sand
    MonogramGradient(Color(0xFFBDA5AD), Color(0xFF988089)), // rose
)

/** The gradient for [name]: always the same for the same person, and Apple's grey when there is no name. */
internal fun monogramGradient(name: String?): MonogramGradient {
    val key = name?.let { Normalizer.normalize(it.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT) }
    if (key.isNullOrEmpty()) return MonogramGradients.first()
    return MonogramGradients[key.hashCode().mod(MonogramGradients.size)]
}

private val Whitespace = Regex("\\s+")

/**
 * The initials Apple draws for [name]: the given and family name's ("Giulia Ferri" is "GF"), one initial for a single
 * name, and the first letter of the local part for an email address. Null when there is nothing to draw.
 */
internal fun monogram(name: String?): String? {
    val text = name?.let { Normalizer.normalize(it, Normalizer.Form.NFC).trim() }.orEmpty()
    val words = if ('@' in text && text.none(Char::isWhitespace)) listOf(text.substringBefore('@')) else text.split(Whitespace)
    val initials = words.mapNotNull(::initial)
    return when (initials.size) {
        0 -> null
        1 -> initials.first()
        else -> initials.first() + initials.last()
    }
}

/** The first letter or digit of [word], upper-cased, with any accent NFC could not fold into it. */
private fun initial(word: String): String? {
    var index = 0
    while (index < word.length) {
        val codePoint = word.codePointAt(index)
        if (Character.isLetterOrDigit(codePoint)) {
            var end = index + Character.charCount(codePoint)
            while (end < word.length && Character.getType(word.codePointAt(end)) == Character.NON_SPACING_MARK.toInt()) {
                end += Character.charCount(word.codePointAt(end))
            }
            return word.substring(index, end).uppercase(Locale.ROOT)
        }
        index += Character.charCount(codePoint)
    }
    return null
}

/**
 * Apple's monogram: the initials of [name] in white on a vertical gradient chosen from the name, or a white person
 * silhouette on the grey gradient when there is no name to draw.
 */
@Composable
internal fun MonogramAvatar(
    name: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.titleSmall,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
) {
    val initials = monogram(name)
    val gradient = monogramGradient(name.takeIf { initials != null })
    AlbaAvatar(size, Color.Transparent, Color.White, modifier, shadowElevation, border) {
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(gradient.top, gradient.bottom))))
        if (initials == null) Icon(AlbaIcons.PersonFilled, null, Modifier.size(size * SILHOUETTE_SCALE).testTag("avatar-silhouette"))
        else Text(initials, style = textStyle.copy(fontWeight = FontWeight.Medium), maxLines = 1, softWrap = false)
    }
}

/** Who "You" is wherever the app shows the account's avatar: its photo when Apple has one, and its name. */
@Stable
internal class AccountIdentity(val photo: ByteArray?, val name: String?)

/** The signed-in account's identity; AlbaApp provides it. Empty in previews and tests that render a screen alone. */
internal val LocalAccountIdentity = compositionLocalOf { AccountIdentity(null, null) }

/**
 * The account's avatar, the same wherever it appears: its photo when Apple has one, otherwise the monogram of [name]
 * (its name, or the Apple Account). The photo fades in over the monogram once decoded, at once under reduced motion.
 */
@Composable
internal fun AccountAvatar(
    photo: ByteArray?,
    name: String?,
    avatarSize: Dp = 56.dp,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.titleSmall,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
) {
    // Keyed by content, and the old image stays until the new one is decoded, so the same photo loaded again never
    // falls back to the monogram for a frame. Decoded photos are kept, so an avatar created again (a card coming back
    // into view, the grid returning from the viewer) starts with the photo instead of fading in from the monogram.
    val key = remember(photo) { photo?.let { AvatarKey(it.contentHashCode(), it.size) } }
    val bitmap by produceState(key?.let(decodedAvatars::get), key) {
        if (photo == null || key == null) { value = null; return@produceState }
        value = decodedAvatars.get(key) ?: withContext(Dispatchers.Default) { decodeAvatar(photo) }?.also { decodedAvatars.put(key, it) }
    }
    val fade = if (reduceGalleryMotion()) snap<Float>() else tween(AVATAR_FADE_MILLIS)
    Crossfade(bitmap, modifier.size(avatarSize), animationSpec = fade, label = "account-avatar") { image ->
        if (image != null) AlbaAvatar(avatarSize, Color.Transparent, Color.White, shadowElevation = shadowElevation, border = border) {
            Image(image, null, Modifier.size(avatarSize).testTag("account-photo"), contentScale = ContentScale.Crop)
        } else MonogramAvatar(name, avatarSize, textStyle = textStyle, shadowElevation = shadowElevation, border = border)
    }
}

private data class AvatarKey(val contentHash: Int, val size: Int)

/** Decoded account photos, at most a few: there is one account, and its photo rarely changes. */
private val decodedAvatars = LruCache<AvatarKey, ImageBitmap>(DECODED_AVATARS)

/** Bounds decoded dimensions as well as download size; a compressed image can be enormous. */
private fun decodeAvatar(photo: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(photo, 0, photo.size, bounds)
    if (bounds.outWidth !in 1..MAX_SOURCE_SIDE || bounds.outHeight !in 1..MAX_SOURCE_SIDE) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > MAX_DECODED_SIDE) inSampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(photo, 0, photo.size, options)?.asImageBitmap()
}

private const val SILHOUETTE_SCALE = .62f
private const val DECODED_AVATARS = 4
private const val AVATAR_FADE_MILLIS = 220
private const val MAX_SOURCE_SIDE = 16384
private const val MAX_DECODED_SIDE = 256

@AlbaPreviews
@Composable
private fun AvatarPreview() = AlbaPreviewSurface {
    MonogramAvatar("Giulia Ferri", 34.dp, shadowElevation = 2.dp)
    MonogramAvatar(null, 34.dp, shadowElevation = 2.dp)
    MonogramAvatar("alba@example.com", 36.dp, textStyle = MaterialTheme.typography.titleMedium)
    AccountAvatar(null, "alex@example.com")
}
