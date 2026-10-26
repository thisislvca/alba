package me.lvca.alba.app.ui.editor

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import me.lvca.alba.app.R
import me.lvca.alba.app.editing.Adjustments
import me.lvca.alba.app.editing.AutoEnhance
import me.lvca.alba.app.editing.ColorMatrices
import me.lvca.alba.app.editing.EditRecipe
import me.lvca.alba.app.editing.ImageStats
import me.lvca.alba.app.editing.RecipeRenderer
import me.lvca.alba.app.editing.Vignette
import me.lvca.alba.app.editing.renderPixels
import me.lvca.alba.app.platform.PhotoCrop
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val THUMBNAIL_SIDE = 160
private const val STATS_SAMPLES = 65_536

/** Auto-enhance's suggestion for the photo, from a sample of the preview's pixels. Null while it is worked out. */
@Composable
internal fun rememberEnhancement(preview: Bitmap?): Adjustments? = produceState<Adjustments?>(null, preview) {
    val bitmap = preview ?: return@produceState
    value = withContext(Dispatchers.Default) {
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        AutoEnhance.adjustments(ImageStats.of(pixels, stride = pixels.size / STATS_SAMPLES + 1))
    }
}.value

/** A small square from the middle of the photo for the filter thumbnails. */
@Composable
internal fun rememberFilterThumbnail(preview: Bitmap?): Bitmap? = produceState<Bitmap?>(null, preview) {
    val bitmap = preview ?: return@produceState
    value = withContext(Dispatchers.Default) {
        val side = minOf(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        Bitmap.createScaledBitmap(square, THUMBNAIL_SIDE, THUMBNAIL_SIDE, true)
    }
}.value

/** Live slider previews tone a bitmap no larger than this; the screen shows it at about this size anyway. */
private const val PROXY_SIDE = 1080
private const val VIGNETTE_STOPS = 16

/**
 * The preview with the tone curve and sharpening applied on the CPU to a downscaled copy, off the main thread. Slider
 * moves arriving while one render runs are conflated into the next. The colour matrix and vignette are drawn on the GPU.
 */
@Composable
internal fun rememberTonedPreview(preview: Bitmap?, adjustments: Adjustments): Bitmap? {
    val tone = adjustments.copy(saturation = 0, warmth = 0, tint = 0, vignette = 0)
    var toned by remember(preview) { mutableStateOf<Bitmap?>(null) }
    val latest by rememberUpdatedState(tone)
    LaunchedEffect(preview) {
        val source = preview ?: return@LaunchedEffect
        val proxy = withContext(Dispatchers.Default) {
            val scale = minOf(1f, PROXY_SIDE.toFloat() / maxOf(source.width, source.height))
            if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1), true) else source
        }
        val width = proxy.width
        val height = proxy.height
        val pixels = withContext(Dispatchers.Default) { IntArray(width * height).also { proxy.getPixels(it, 0, width, 0, 0, width, height) } }
        val output = IntArray(width * height)
        snapshotFlow { latest }.conflate().collect { wanted ->
            // A new bitmap each time, never one the screen may still be drawing.
            toned = if (!wanted.changesTone) null else withContext(Dispatchers.Default) {
                RecipeRenderer(EditRecipe(wanted), width, height, color = false).renderPixels(pixels, output, width)
                Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
            }
        }
    }
    return toned ?: preview
}

/**
 * The photo as it will be saved: cropped and rotated, with the colour matrix as a GPU colour filter and the vignette as
 * a gradient over it. In the Crop tab it shows the whole photo under [cropOverlay]. [comparing] shows the original.
 */
@Composable
internal fun EditorImage(
    original: Bitmap,
    toned: Bitmap,
    step: EditStep,
    cropMode: Boolean,
    comparing: Boolean,
    onCompare: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    cropOverlay: @Composable BoxScope.() -> Unit = {},
) {
    val whole = cropMode || comparing
    val crop = if (whole) PhotoCrop() else step.crop
    val turns = if (comparing) 0 else step.turns
    val bitmap = if (comparing) original else toned
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val recipe = step.recipe
    val filter = remember(recipe, comparing) {
        if (comparing) null else ColorMatrices.recipe(recipe).takeUnless(ColorMatrices::isIdentity)?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }
    }
    val compareLabel = stringResource(R.string.hold_to_compare)
    val originalLabel = stringResource(R.string.editor_original)
    val toggleLabel = stringResource(if (comparing) R.string.show_edit else R.string.show_original)
    val compare by rememberUpdatedState(onCompare)
    BoxWithConstraints(modifier.then(if (cropMode) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures(onPress = {
            compare(true)
            try { tryAwaitRelease() } finally { compare(false) }
        })
    }).testTag("editor-image").semantics {
        contentDescription = compareLabel
        if (comparing) stateDescription = originalLabel
        // Touch and hold has no accessibility equivalent, so a screen reader toggles the original instead.
        if (!cropMode) customActions = listOf(CustomAccessibilityAction(toggleLabel) { compare(!comparing); true })
    }, contentAlignment = Alignment.Center) {
        val source = crop.pixels(bitmap.width, bitmap.height)
        val aspect = source.width().toFloat() / source.height()
        val width = if (turns % 2 == 0) minOf(maxWidth, maxHeight * aspect) else minOf(maxHeight, maxWidth * aspect)
        Box(Modifier.requiredSize(width, width / aspect).graphicsLayer { rotationZ = turns * 90f }) {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(image, IntOffset(source.left, source.top), IntSize(source.width(), source.height()),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), colorFilter = filter, filterQuality = FilterQuality.Medium)
                if (!whole && recipe.adjustments.vignette != 0) drawVignette(recipe.adjustments.vignette)
            }
            if (cropMode) cropOverlay()
        }
        AnimatedVisibility(comparing, Modifier.align(Alignment.TopCenter),
            enter = if (reducedMotion) EnterTransition.None else fadeIn(), exit = if (reducedMotion) ExitTransition.None else fadeOut()) {
            Text(originalLabel, Modifier.padding(8.dp).background(Color.Black.copy(alpha = .6f), MaterialTheme.shapes.small)
                .padding(horizontal = 10.dp, vertical = 4.dp).testTag("compare-original"), color = Color.White,
                style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The same vignette [Vignette] applies on save, drawn as a radial gradient stretched to the frame. */
private fun DrawScope.drawVignette(amount: Int) {
    val color = if (amount > 0) Color.Black else Color.White
    // Evenly spaced colours from the centre (distance 0) to the corner (distance 1).
    val colors = List(VIGNETTE_STOPS + 1) { i -> color.copy(alpha = Vignette.alpha(amount, i / VIGNETTE_STOPS.toFloat())) }
    // Distance 1 is the corner. Squashed into a square of the frame's width, that is a circle of radius width / √2.
    scale(scaleX = 1f, scaleY = size.height / size.width, pivot = center) {
        drawRect(Brush.radialGradient(colors, center = center, radius = size.width / sqrt(2f)),
            topLeft = Offset(0f, center.y - size.width / 2), size = Size(size.width, size.width))
    }
}
