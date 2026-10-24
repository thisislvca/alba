package me.lvca.alba.app.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.viewer.PhotoZoomState
import me.lvca.alba.app.ui.viewer.PinchToClose
import me.lvca.alba.app.ui.viewer.photoPanExtent
import me.lvca.alba.app.ui.viewer.photoTapGestures
import me.lvca.alba.app.ui.viewer.photoZoomGestures
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion
import me.lvca.alba.app.ui.viewer.sharedPhoto
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Whether previews are paused because phone storage is low. A missing preview then blames storage, not the network. */
internal val LocalPreviewStorageLow = compositionLocalOf { false }

private data class TransitionThumbnailKey(val mediaId: String, val sourceRevision: String)

// Visible images already own these bitmaps. Keep their identity available to the incoming
// shared element even if background decoding evicts the corresponding LRU cache entry.
// Access is confined to composition/effects on the UI thread; disposal releases each owner.
private val visibleThumbnails = mutableMapOf<TransitionThumbnailKey, MutableMap<Any, Bitmap>>()

// A sharp viewer decode can evict grid thumbnails. Keep small return targets separately
// so the incoming shared element exists immediately, even when the disk decode is cold.
private val transitionThumbnails = object : LruCache<TransitionThumbnailKey, Bitmap>(4 * 1_024) {
    override fun sizeOf(key: TransitionThumbnailKey, value: Bitmap): Int = (value.allocationByteCount / 1_024).coerceAtLeast(1)
}

private data class ThumbnailCacheKey(val reference: String, val targetSidePx: Int, val fullImage: Boolean, val sourceRevision: String)

private val thumbnailMemoryCache = object : LruCache<ThumbnailCacheKey, Bitmap>(thumbnailCacheKilobytes()) {
    override fun sizeOf(key: ThumbnailCacheKey, value: Bitmap): Int =
        (value.allocationByteCount / 1_024).coerceAtLeast(1)
}

@Composable
fun MediaThumbnail(
    reference: String?,
    accentStartArgb: Long,
    accentEndArgb: Long,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    zoomable: Boolean = false,
    retryVersion: Int = 0,
    sourceRevision: String = "",
    onTap: () -> Unit = {},
    onZoomChanged: (Boolean) -> Unit = {},
    fallbackReference: String? = null,
    sharedMediaId: String? = null,
    sourceSize: IntSize = IntSize.Zero,
    presentationScale: () -> Float = { 1f },
    pinchToClose: PinchToClose? = null,
) {
    val context = LocalContext.current
    var decodeFailure by remember(sharedMediaId ?: reference, sourceRevision) { mutableStateOf<ThumbnailFailure?>(null) }
    var localRetry by remember { mutableIntStateOf(0) }
    var decoding by remember { mutableStateOf(false) }
    var longestSidePx by remember { mutableIntStateOf(0) }
    val zoomState = remember(sharedMediaId ?: reference, zoomable) { PhotoZoomState() }
    // Derived so a pinch recomposes only when these flip, not on every frame.
    val zoomedIn by remember(zoomState) { derivedStateOf { zoomState.zoom > 1f } }
    val sharpen by remember(zoomState) { derivedStateOf { zoomState.zoom > SHARPEN_ZOOM } }
    val targetSidePx = if (zoomable && sharpen) (longestSidePx * 2).coerceAtMost(4096) else thumbnailSizeBucket(longestSidePx)
    val cacheKey = reference?.takeIf { targetSidePx > 0 }?.let {
        ThumbnailCacheKey(it, targetSidePx, contentScale == ContentScale.Fit, sourceRevision)
    }
    // Start with the exact same pixels as the tile, then replace them when the larger decode is ready.
    // Reset on identity/revision changes so another photo (or an edited revision) can never leak through.
    val bitmap by key(sharedMediaId ?: reference, sourceRevision) { produceState<Bitmap?>(
        initialValue = sharedMediaId?.let { visibleThumbnails[TransitionThumbnailKey(it, sourceRevision)]?.values?.firstOrNull() }
            ?: cachedThumbnail(reference, fallbackReference, sourceRevision)
            ?: sharedMediaId?.let { transitionThumbnails.get(TransitionThumbnailKey(it, sourceRevision)) },
        key1 = cacheKey,
        key2 = retryVersion + localRetry,
    ) {
        val key = cacheKey
        if (key == null) return@produceState
        decodeFailure = null; decoding = true
        val decoded = try { thumbnailMemoryCache.get(key) ?: withContext(Dispatchers.IO) {
            decodeThumbnail(context, key.reference, key.targetSidePx, key.fullImage)
        } } catch (e: CancellationException) { throw e }
        catch (_: SecurityException) { decodeFailure = ThumbnailFailure.ACCESS; null }
        catch (_: FileNotFoundException) { decodeFailure = ThumbnailFailure.MISSING; null }
        catch (_: Exception) { decodeFailure = ThumbnailFailure.DECODE; null }
        finally { decoding = false }
        decoded?.also { loaded ->
            loaded.prepareToDraw()
            thumbnailMemoryCache.put(key, loaded)
        }
        if (decoded != null) {
            if (key.fullImage && sharedMediaId != null) withContext(Dispatchers.IO) {
                val ratio = minOf(1f, 512f / maxOf(decoded.width, decoded.height))
                val small = Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt().coerceAtLeast(1),
                    (decoded.height * ratio).toInt().coerceAtLeast(1), true)
                transitionThumbnails.put(TransitionThumbnailKey(sharedMediaId, sourceRevision), small)
            }
            value = decoded
        }
    } }

    // A tile that had to wait for its decode fades the photo in over the flat placeholder; cached tiles show at once.
    val fadesIn = remember(sharedMediaId ?: reference, sourceRevision) { bitmap == null }
    val placeholder = MaterialTheme.colorScheme.surfaceContainerHigh

    val thumbnailOwner = remember { Any() }
    DisposableEffect(sharedMediaId, sourceRevision, bitmap) {
        val key = sharedMediaId?.let { TransitionThumbnailKey(it, sourceRevision) }
        val loaded = bitmap
        if (key != null && loaded != null) visibleThumbnails.getOrPut(key) { mutableMapOf() }[thumbnailOwner] = loaded
        onDispose {
            if (key != null) visibleThumbnails[key]?.let { owners ->
                owners.remove(thumbnailOwner)
                if (owners.isEmpty()) visibleThumbnails.remove(key)
            }
        }
    }

    val tap by rememberUpdatedState(onTap)
    val zoomChanged by rememberUpdatedState(onZoomChanged)
    LaunchedEffect(zoomedIn) { zoomChanged(zoomedIn) }
    val zoomScope = rememberCoroutineScope()
    val reduceMotion = reduceGalleryMotion()
    val dpi = LocalDensity.current.density * 160f
    SideEffect {
        zoomState.image = bitmap?.let { IntSize(it.width, it.height) } ?: IntSize.Zero
        zoomState.source = sourceSize
        zoomState.dpi = dpi
    }
    val zoomDescription = stringResource(if (zoomedIn) R.string.photo_zoomed else R.string.photo_fit)
    val toggleLabel = stringResource(R.string.toggle_photo_controls)
    val zoomLabel = stringResource(if (zoomedIn) R.string.reset_photo_zoom else R.string.zoom_in_photo)
    Box(
        modifier = modifier
            .onSizeChanged { size -> longestSidePx = maxOf(size.width, size.height); zoomState.viewport = size }
            .then(if (zoomable) Modifier.semantics {
                stateDescription = zoomDescription
                onClick(label = toggleLabel) { tap(); true }
                customActions = listOf(CustomAccessibilityAction(zoomLabel) {
                    val center = Offset(zoomState.viewport.width / 2f, zoomState.viewport.height / 2f)
                    zoomState.launch(zoomScope) { zoomState.toggle(center, reduceMotion) }; true
                })
            }
                .photoZoomGestures(zoomState, reduceMotion, pinchToClose)
                // After the pinch so it sees each touch first and can claim a double-tap drag. A sharper resource is
                // still the same photo; do not cancel an in-flight tap.
                .photoTapGestures(zoomState, reduceMotion, sharedMediaId ?: reference, sourceRevision) { tap() } else Modifier)
            .clipToBounds()
            .then(when {
                contentScale == ContentScale.Fit -> Modifier
                // Loading or loaded tiles sit on a flat neutral placeholder, like Google Photos; the photo's accent
                // colours are kept for a tile that has no preview to show.
                bitmap != null || decoding || (decodeFailure == null && reference != null) -> Modifier.background(placeholder)
                else -> Modifier.background(Brush.linearGradient(colors = listOf(Color(accentStartArgb), Color(accentEndArgb))))
            }),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = contentDescription,
                // Both endpoints crop inside the travelling rectangle. The viewer rectangle itself
                // fits the decoded aspect ratio, so the square tile uncrops smoothly without distortion.
                contentScale = ContentScale.Crop,
                modifier = Modifier.then(if (contentScale == ContentScale.Fit) Modifier.layout { measurable, constraints ->
                    // The viewer's swipe-down scale is a layout size, so the shared element returns from it.
                    val fit = minOf(constraints.maxWidth.toFloat() / loaded.width, constraints.maxHeight.toFloat() / loaded.height) *
                        presentationScale()
                    val width = (loaded.width * fit).toInt().coerceAtLeast(1)
                    val height = (loaded.height * fit).toInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(width, height) { placeable.place(0, 0) }
                } else Modifier)
                    .sharedPhoto(sharedMediaId)
                    .fillMaxSize()
                    .then(if (sharedMediaId != null) Modifier.testTag("photo-${if (contentScale == ContentScale.Fit) "viewer" else "tile"}-$sharedMediaId") else Modifier)
                    .then(if (zoomable) Modifier.graphicsLayer {
                        scaleX = zoomState.zoom; scaleY = zoomState.zoom
                        translationX = zoomState.offset.x; translationY = zoomState.offset.y
                    } else Modifier)
                    .clipToBounds(),
            )
            if (fadesIn && contentScale != ContentScale.Fit) TileFadeIn(placeholder)
        } else {
            val failure = decodeFailure
            val storageLow = LocalPreviewStorageLow.current
            val label = stringResource(when (failure) {
                ThumbnailFailure.ACCESS -> R.string.photo_access_lost
                ThumbnailFailure.MISSING -> R.string.photo_file_missing
                ThumbnailFailure.DECODE -> R.string.photo_decode_failed
                null -> when {
                    decoding -> R.string.loading
                    storageLow -> R.string.photo_preview_storage_low
                    else -> R.string.photo_preview_unavailable
                }
            })
            val onMedia = AlbaTheme.colors.onMedia
            // A tile waiting for its decode stays a plain placeholder: a field of spinners reads as broken while scrolling.
            val waitingTile = contentScale != ContentScale.Fit && failure == null && reference != null
            if (!waitingTile) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                if (decoding) CircularProgressIndicator(Modifier.size(24.dp), color = onMedia, strokeWidth = 2.dp)
                else Icon(when {
                    failure != null -> Icons.Outlined.Info
                    storageLow -> AlbaIcons.PhoneAndroid
                    else -> AlbaIcons.CloudQueue
                },
                    contentDescription = listOfNotNull(contentDescription, label).joinToString(". "),
                    tint = onMedia.copy(alpha = .85f), modifier = Modifier.size(28.dp))
                if (contentScale == ContentScale.Fit && !decoding) {
                    Text(label, color = onMedia, modifier = Modifier.padding(top = 12.dp))
                    if (reference != null) TextButton(onClick = { localRetry++ }, modifier = Modifier.testTag("retry-decode")) { Text(stringResource(R.string.retry_photo)) }
                }
            }
        }
    }
}

/** The placeholder [color] lifting off a newly decoded tile. Drawn, not recomposed, while it fades. */
@Composable
private fun TileFadeIn(color: Color) {
    val reducedMotion = reduceGalleryMotion()
    val cover = remember { Animatable(1f) }
    LaunchedEffect(cover) { cover.animateTo(0f, if (reducedMotion) snap() else tween(GalleryFeel.TILE_FADE_IN_MILLIS)) }
    Spacer(Modifier.fillMaxSize().drawBehind { cover.value.takeIf { it > 0f }?.let { drawRect(color, alpha = it) } })
}

private fun cachedThumbnail(reference: String?, fallback: String?, revision: String): Bitmap? =
    thumbnailMemoryCache.snapshot().entries
        .filter { (key, _) -> key.sourceRevision == revision && (key.reference == reference || key.reference == fallback) }
        .maxByOrNull { (_, bitmap) -> bitmap.width.toLong() * bitmap.height }?.value

private enum class ThumbnailFailure { ACCESS, MISSING, DECODE }

internal fun loadThumbnail(context: Context, reference: String, targetSidePx: Int, fullImage: Boolean): Bitmap? = runCatching {
    decodeThumbnail(context, reference, targetSidePx, fullImage)
}.getOrNull()

private fun decodeThumbnail(context: Context, reference: String, targetSidePx: Int, fullImage: Boolean): Bitmap {
    val contentReference = reference.startsWith("content://")
    val isVideo = contentReference && context.contentResolver.getType(reference.toUri())?.startsWith("video/") == true
    val bitmap = if (isVideo && fullImage) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, reference.toUri())
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetSidePx, targetSidePx)
        } finally { retriever.release() }
    } else if (contentReference && !fullImage) {
        context.contentResolver.loadThumbnail(reference.toUri(), Size(targetSidePx, targetSidePx), null)
    } else {
        // ImageDecoder applies EXIF rotation/mirroring, and sizes before allocating pixels.
        val source = if (contentReference) ImageDecoder.createSource(context.contentResolver, reference.toUri())
            else ImageDecoder.createSource(File(reference))
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            val ratio = minOf(1.0, targetSidePx.toDouble() / longest)
            decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    if (!contentReference) File(reference).setLastModified(System.currentTimeMillis())
    return bitmap ?: throw IOException("No decodable frame")
}

private fun thumbnailCacheKilobytes(): Int = (Runtime.getRuntime().maxMemory() / 1_024L / 8L)
    .coerceIn(MIN_CACHE_KB.toLong(), MAX_CACHE_KB.toLong())
    .toInt()

/** Above this zoom the viewer decodes at twice the screen size. */
private const val SHARPEN_ZOOM = 1.1f

private const val MIN_CACHE_KB = 4 * 1_024
private const val MAX_CACHE_KB = 16 * 1_024

/** Clamp to the fitted image, not the letterboxed viewport, so panning cannot lose the photo. */
internal fun constrainPhotoPan(offset: Offset, viewport: IntSize,
    image: IntSize, zoom: Float): Offset {
    val extent = photoPanExtent(viewport, image, zoom)
    return Offset(offset.x.coerceIn(-extent.x, extent.x), offset.y.coerceIn(-extent.y, extent.y))
}
