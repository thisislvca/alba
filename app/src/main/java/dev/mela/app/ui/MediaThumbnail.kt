package dev.mela.app.ui

import androidx.compose.ui.res.stringResource
import dev.mela.app.R
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class TransitionThumbnailKey(val mediaId: String, val sourceRevision: String)

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
) {
    val context = LocalContext.current
    var decodeFailure by remember(sharedMediaId ?: reference, sourceRevision) { mutableStateOf<ThumbnailFailure?>(null) }
    var localRetry by remember { mutableIntStateOf(0) }
    var decoding by remember { mutableStateOf(false) }
    var longestSidePx by remember { mutableIntStateOf(0) }
    var zoom by remember(sharedMediaId ?: reference, zoomable) { mutableFloatStateOf(1f) }
    var offset by remember(sharedMediaId ?: reference, zoomable) { mutableStateOf(Offset.Zero) }
    val targetSidePx = if (zoomable && zoom > 1.1f) (longestSidePx * 2).coerceAtMost(4096) else thumbnailSizeBucket(longestSidePx)
    val cacheKey = reference?.takeIf { targetSidePx > 0 }?.let {
        ThumbnailCacheKey(it, targetSidePx, contentScale == ContentScale.Fit, sourceRevision)
    }
    // Start with the exact same pixels as the tile, then replace them when the larger decode is ready.
    // Reset on identity/revision changes so another photo (or an edited revision) can never leak through.
    val bitmap by key(sharedMediaId ?: reference, sourceRevision) { produceState<Bitmap?>(
        initialValue = cachedThumbnail(reference, fallbackReference, sourceRevision)
            ?: sharedMediaId?.let { transitionThumbnails.get(TransitionThumbnailKey(it, sourceRevision)) },
        key1 = cacheKey,
        key2 = retryVersion + localRetry,
    ) {
        val key = cacheKey
        if (key == null) return@produceState
        decodeFailure = null; decoding = true
        val decoded = try { thumbnailMemoryCache.get(key) ?: withContext(Dispatchers.IO) {
            decodeThumbnail(context, key.reference, key.targetSidePx, key.fullImage)
        } } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: SecurityException) { decodeFailure = ThumbnailFailure.ACCESS; null }
        catch (_: java.io.FileNotFoundException) { decodeFailure = ThumbnailFailure.MISSING; null }
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

    val tap by rememberUpdatedState(onTap)
    val zoomChanged by rememberUpdatedState(onZoomChanged)
    LaunchedEffect(zoom > 1f) { zoomChanged(zoom > 1f) }
    var bounds by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    fun clamp(value: Offset): Offset {
        val loaded = bitmap ?: return Offset.Zero
        return constrainPhotoPan(value, bounds, androidx.compose.ui.unit.IntSize(loaded.width, loaded.height), zoom)
    }
    val transformState = rememberTransformableState { scale, pan, _ ->
        zoom = (zoom * scale).coerceIn(1f, 5f)
        offset = if (zoom == 1f) Offset.Zero else clamp(offset + pan)
    }
    val zoomDescription = stringResource(if (zoom > 1f) R.string.photo_zoomed else R.string.photo_fit)
    val toggleLabel = stringResource(R.string.toggle_photo_controls)
    val zoomLabel = stringResource(if (zoom > 1f) R.string.reset_photo_zoom else R.string.zoom_in_photo)
    Box(
        modifier = modifier
            .onSizeChanged { size -> longestSidePx = maxOf(size.width, size.height); bounds = size }
            .then(if (zoomable) Modifier.semantics {
                stateDescription = zoomDescription
                onClick(label = toggleLabel) { tap(); true }
                customActions = listOf(CustomAccessibilityAction(zoomLabel) {
                    zoom = if (zoom > 1f) 1f else 2.5f; offset = Offset.Zero; true
                })
            }
                // A sharper resource is still the same photo; do not cancel an in-flight tap.
                .pointerInput(sharedMediaId ?: reference, sourceRevision) {
                    detectTapGestures(onTap = { tap() }, onDoubleTap = { point ->
                        zoom = if (zoom > 1f) 1f else 2.5f
                        offset = if (zoom == 1f) Offset.Zero else clamp((Offset(size.width / 2f, size.height / 2f) - point) * (zoom - 1))
                    })
                } else Modifier)
            .clipToBounds()
            .then(if (contentScale == ContentScale.Fit) Modifier else Modifier.background(
                Brush.linearGradient(colors = listOf(Color(accentStartArgb), Color(accentEndArgb))),
            )),
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
                    val fit = minOf(constraints.maxWidth.toFloat() / loaded.width, constraints.maxHeight.toFloat() / loaded.height)
                    val width = (loaded.width * fit).toInt().coerceAtLeast(1)
                    val height = (loaded.height * fit).toInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(width, height) { placeable.place(0, 0) }
                } else Modifier)
                    .sharedPhoto(sharedMediaId)
                    .fillMaxSize()
                    .then(if (sharedMediaId != null) Modifier.testTag("photo-${if (contentScale == ContentScale.Fit) "viewer" else "tile"}-$sharedMediaId") else Modifier)
                    .then(if (zoomable) Modifier.transformable(transformState, canPan = { zoom > 1f }).graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y } else Modifier)
                    .clipToBounds(),
            )
        } else {
            val failure = decodeFailure
            val label = stringResource(when (failure) {
                ThumbnailFailure.ACCESS -> R.string.photo_access_lost
                ThumbnailFailure.MISSING -> R.string.photo_file_missing
                ThumbnailFailure.DECODE -> R.string.photo_decode_failed
                null -> if (decoding) R.string.loading else R.string.photo_preview_unavailable
            })
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                if (decoding) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(if (failure == null) MelaIcons.CloudQueue else Icons.Outlined.Info,
                    contentDescription = listOfNotNull(contentDescription, label).joinToString(". "),
                    tint = Color.White.copy(alpha = .85f), modifier = Modifier.size(28.dp))
                if (contentScale == ContentScale.Fit && !decoding) {
                    Text(label, color = Color.White, modifier = Modifier.padding(top = 12.dp))
                    if (reference != null) TextButton(onClick = { localRetry++ }, modifier = Modifier.testTag("retry-decode")) { Text(stringResource(R.string.retry_photo)) }
                }
            }
        }
    }
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
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, reference.toUri())
            retriever.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetSidePx, targetSidePx)
        } finally { retriever.release() }
    } else if (contentReference && !fullImage && Build.VERSION.SDK_INT >= 29) {
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
    return bitmap ?: throw java.io.IOException("No decodable frame")
}

private fun thumbnailCacheKilobytes(): Int = (Runtime.getRuntime().maxMemory() / 1_024L / 8L)
    .coerceIn(MIN_CACHE_KB.toLong(), MAX_CACHE_KB.toLong())
    .toInt()

private const val MIN_CACHE_KB = 4 * 1_024
private const val MAX_CACHE_KB = 16 * 1_024


/** Clamp to the fitted image, not the letterboxed viewport, so panning cannot lose the photo. */
internal fun constrainPhotoPan(offset: Offset, viewport: androidx.compose.ui.unit.IntSize,
    image: androidx.compose.ui.unit.IntSize, zoom: Float): Offset {
    if (image.width <= 0 || image.height <= 0) return Offset.Zero
    val fit = minOf(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
    val horizontal = ((image.width * fit * zoom - viewport.width) / 2).coerceAtLeast(0f)
    val vertical = ((image.height * fit * zoom - viewport.height) / 2).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-horizontal, horizontal), offset.y.coerceIn(-vertical, vertical))
}
