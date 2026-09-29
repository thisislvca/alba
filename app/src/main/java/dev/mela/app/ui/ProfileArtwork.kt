package dev.mela.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp

/** Original vector geometry for Mela's account card. */
@Composable
internal fun ProfileArtwork(modifier: Modifier = Modifier) {
    Box(modifier.drawWithCache {
        val w = size.width
        val h = size.height
        // Cool blue light opens into lavender, with a saturated plane beneath the white type.
        // The cloud ends at 156 dp including the card inset; leave clear space beside it.
        val diagonalStart = maxOf(w * .48f, 172.dp.toPx())
        val foreground = Path().apply {
            moveTo(diagonalStart, 0f)
            cubicTo(w * .70f, h * .27f, w * .88f, h * .58f, w * 1.22f, h * .97f)
            lineTo(w * 1.22f, h)
            lineTo(0f, h)
            lineTo(0f, 0f)
            close()
        }
        val farFacet = Path().apply {
            moveTo(w * .63f, 0f)
            cubicTo(w * .77f, h * .16f, w * .93f, h * .24f, w, h * .28f)
            lineTo(w, 0f)
            close()
        }
        val base = Brush.linearGradient(
            0f to Color(0xFF9BCFFF),
            .48f to Color(0xFFA18FFF),
            1f to Color(0xFF2D43D2),
            start = Offset.Zero, end = Offset(w, h * .16f),
        )
        val plane = Brush.linearGradient(
            0f to Color(0xFF62C2FF),
            .30f to Color(0xFF388BE9),
            .62f to Color(0xFF5364DE),
            1f to Color(0xFF8750D6),
            start = Offset.Zero, end = Offset(w * .85f, h),
        )
        val facet = Brush.linearGradient(
            listOf(Color(0xFF5B53E9).copy(alpha = .16f), Color(0xFF8270FF).copy(alpha = .02f)),
            start = Offset(w, 0f), end = Offset(w * .7f, h * .35f),
        )
        onDrawBehind {
            drawRect(base)
            drawPath(foreground, plane)
            drawPath(farFacet, facet)
        }
    })
}

/** The 88 dp portrait and the central cloud dome share the exact same centre (44, 62). */
internal fun Modifier.profileAvatarCloud(): Modifier = drawWithCache {
    val unit = 1.dp.toPx()
    fun oval(left: Float, top: Float, right: Float, bottom: Float) = Path().apply {
        addOval(Rect(left * unit, top * unit, right * unit, bottom * unit))
    }
    val dome = oval(-14f, 4f, 102f, 120f)
    val leftLobe = oval(-50f, 56f, 14f, 120f)
    val rightLobe = oval(72f, 60f, 132f, 120f)
    val base = Path().apply { addRect(Rect(-18f * unit, 88f * unit, 102f * unit, 120f * unit)) }
    val cloud = listOf(leftLobe, rightLobe, base).fold(dome) { outline, part ->
        Path.combine(PathOperation.Union, outline, part)
    }
    // Rasterize only the soft inset edge: BlurMaskFilter is inconsistent on hardware canvases.
    // The small bitmap is cached with the drawing size/density, never regenerated per frame.
    val margin = 56.dp.toPx()
    val bitmap = android.graphics.Bitmap.createBitmap(
        (size.width + margin * 2).toInt().coerceAtLeast(1),
        (size.height + margin * 2).toInt().coerceAtLeast(1),
        android.graphics.Bitmap.Config.ARGB_8888,
    )
    val canvas = android.graphics.Canvas(bitmap)
    canvas.translate(margin, margin)
    canvas.clipPath(cloud.asAndroidPath())
    val edgeShadow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 12.dp.toPx()
        maskFilter = android.graphics.BlurMaskFilter(7.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
        shader = android.graphics.LinearGradient(0f, 0f, 0f, 118.dp.toPx(),
            intArrayOf(0x55202D70, 0x00202D70), floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP)
    }
    canvas.drawPath(cloud.asAndroidPath(), edgeShadow)
    val shadow = bitmap.asImageBitmap()
    onDrawBehind { drawImage(shadow, topLeft = Offset(-margin, -margin)) }
}
