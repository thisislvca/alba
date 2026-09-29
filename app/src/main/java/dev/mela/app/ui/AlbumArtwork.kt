package dev.mela.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.mela.app.R
import dev.mela.engine.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class CollectionPreview(val count: Int = 0, val media: List<GalleryMedia> = emptyList())

/** One bounded preview index; never decode originals to build collection mosaics. */
@Composable
internal fun rememberCollectionPreviews(media: List<GalleryMedia>): Map<String, CollectionPreview> {
    val summaries by produceState<Map<String, CollectionPreview>>(emptyMap(), media) {
        value = withContext(Dispatchers.Default) {
            val result = mutableMapOf<String, CollectionPreview>()
            for (item in media.filterNot { it.isTrashed }.sortedByDescending { it.capturedAtEpochMillis }) {
                val ids = item.collectionIds + listOfNotNull(
                    GalleryQuery.FAVORITES.takeIf { item.isFavorite && !item.isShared },
                    "offline".takeIf { item.availability == MediaAvailability.ORIGINAL_CACHED && !item.isShared },
                    if (item.isShared) "hub:shared" else if (item.origin == MediaOrigin.DEVICE) "hub:device" else "hub:albums",
                )
                for (id in ids) {
                    val previous = result[id] ?: CollectionPreview()
                    result[id] = CollectionPreview(previous.count + 1,
                        if (previous.media.size < 6) previous.media + item else previous.media)
                }
            }
            result
        }
    }
    return summaries
}

internal fun GalleryMedia.coverReference(): String? = previewReference ?: viewerReference ?:
    (linkedDeviceReference ?: originalReference)?.takeIf { kind != MediaKind.VIDEO || it.startsWith("content://") }

@Composable
internal fun CoverImage(media: GalleryMedia?, retry: Int, preview: (String) -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(media?.id, media?.previewReference, retry) {
        media?.takeIf { it.origin == MediaOrigin.ICLOUD && it.previewReference == null }?.let { preview(it.id) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        if (media == null) ICloudIcon(R.drawable.icloud_albums)
        else MediaThumbnail(media.coverReference(), media.accentStartArgb, media.accentEndArgb, null,
            Modifier.fillMaxSize(), sourceRevision = media.sourceRevision, retryVersion = retry)
    }
}

@Composable
internal fun AlbumCover(media: List<GalleryMedia>, retry: Int, preview: (String) -> Unit,
    modifier: Modifier = Modifier, rotating: Boolean = false) {
    LaunchedEffect(rotating, media.map { it.id }, retry) {
        if (rotating) media.filter { it.origin == MediaOrigin.ICLOUD && it.previewReference == null }.forEach { preview(it.id) }
    }
    val candidates = if (rotating) media.filter { it.coverReference() != null }.ifEmpty { media.take(1) } else media.take(1)
    var index by remember(candidates.map { it.id }) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(rotating, candidates.map { it.id }, lifecycle) {
        if (rotating && candidates.size > 1) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(6_500)
                // Respect Android's reduced-animation preference, including changes while open.
                if (android.provider.Settings.Global.getFloat(context.contentResolver,
                        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f) index = (index + 1) % candidates.size
            }
        }
    }
    Crossfade(candidates.getOrNull(index), modifier, animationSpec = tween(900), label = "album-cover") {
        CoverImage(it, retry, preview, Modifier.fillMaxSize())
    }
}

@Composable
internal fun OwnerAvatar(name: String?, modifier: Modifier = Modifier) {
    val description = name?.let { stringResource(R.string.shared_owner, it) }
    Surface(modifier.size(34.dp).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        shape = CircleShape, color = Color(0xFFEEECE8), contentColor = Color(0xFF35383C), shadowElevation = 2.dp) {
        Box(contentAlignment = Alignment.Center) {
            val initial = name?.trim()?.firstOrNull()?.uppercaseChar()?.toString()
            if (initial == null) Icon(Icons.Outlined.AccountCircle, null, Modifier.size(26.dp))
            else Text(initial, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
internal fun AlbumCard(album: GalleryCollection, summary: CollectionPreview?, retry: Int,
    preview: (String) -> Unit, modifier: Modifier = Modifier, featured: Boolean = false, open: () -> Unit) {
    Surface(onClick = open, modifier = modifier.testTag("${if (album.shared != null) "shared-" else ""}album-${album.id}"),
        shape = androidx.compose.ui.graphics.RectangleShape, color = MaterialTheme.colorScheme.background) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(if (featured) .82f else 1f).clip(RoundedCornerShape(16.dp))) {
                AlbumCover(summary?.media.orEmpty(), retry, preview, Modifier.fillMaxSize())
                if (album.shared != null) OwnerAvatar(album.shared?.ownerName, Modifier.align(Alignment.TopStart).padding(10.dp))
                if (featured) {
                    Box(Modifier.fillMaxWidth().height(100.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .8f)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                        Text(album.localizedName(), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(itemCount(summary?.count ?: 0), color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (!featured) {
                Text(album.localizedName(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                Text(if (album.isFolder) androidx.compose.ui.res.pluralStringResource(R.plurals.folder_album_count, summary?.count ?: 0, summary?.count ?: 0) else itemCount(summary?.count ?: 0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
            }
        }
    }
}

@Composable
internal fun SharedAlbumsStrip(albums: List<GalleryCollection>, summaries: Map<String, CollectionPreview>, retry: Int,
    preview: (String) -> Unit, open: (String) -> Unit, seeAll: () -> Unit) {
    if (albums.isEmpty()) return
    Column(Modifier.padding(bottom = 18.dp).testTag("shared-albums-strip")) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.shared_albums), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = seeAll) { Text(stringResource(R.string.see_all)) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(albums, key = { it.id }) { album ->
                AlbumCard(album, summaries[album.id], retry, preview, Modifier.width(164.dp), featured = true) { open(album.id) }
            }
        }
    }
}

@Composable
internal fun AlbumHero(album: GalleryCollection, summary: CollectionPreview?, retry: Int, preview: (String) -> Unit,
    activity: (() -> Unit)?, view: (() -> Unit)?, info: (() -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("album-hero")) {
        Box(Modifier.fillMaxWidth().height((maxWidth * .95f).coerceIn(260.dp, 420.dp))) {
            AlbumCover(summary?.media.orEmpty(), retry, preview, Modifier.fillMaxSize(), rotating = true)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .12f), Color.Black.copy(alpha = .85f)))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp)) {
                Text(album.localizedName(), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color.White,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(itemCount(summary?.count ?: 0), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                        if (info != null && album.shared != null) TextButton(onClick = info,
                            contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("shared-version-badge"),
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = .85f))) {
                            Text(stringResource(if (album.shared?.generation == SharedAlbumGeneration.MODERN) R.string.shared_new else R.string.shared_legacy), style = MaterialTheme.typography.labelMedium)
                        }
                        album.shared?.ownerName?.let { Text(stringResource(R.string.shared_owner, it), color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall) }
                    }
                    if (activity != null) FilledIconButton(onClick = activity, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = .45f), contentColor = Color.White)) {
                        Icon(painterResource(R.drawable.ic_album_activity), stringResource(R.string.shared_activity))
                    }
                    if (view != null) {
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick = view, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = .45f), contentColor = Color.White)) {
                            Icon(Icons.Filled.PlayArrow, stringResource(R.string.view_album))
                        }
                    }
                }
            }
        }
    }
}
