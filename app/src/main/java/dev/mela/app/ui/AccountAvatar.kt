package dev.mela.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    Box(Modifier.size(avatarSize).clip(CircleShape).background(Color.White.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, null, Modifier.size(avatarSize).testTag("account-photo"), contentScale = ContentScale.Crop)
        else Icon(Icons.Outlined.Person, null, Modifier.size(avatarSize * .55f), tint = Color.White)
    }
}
