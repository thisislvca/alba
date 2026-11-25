package me.lvca.alba.app.ui.designsystem.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Alba's corner scale. Cards and grouped lists use [Shapes.large]; covers [Shapes.medium]; controls and
 * clipped rows [Shapes.small]; chips [Shapes.extraSmall]; hero surfaces and sheets [Shapes.extraLarge].
 * Fully rounded pills, badges and avatars use CircleShape.
 */
internal val AlbaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Sheets and panels that rise from the bottom edge: [Shapes.extraLarge] on top, square at the bottom. */
internal val Shapes.sheet: CornerBasedShape
    get() = extraLarge.copy(bottomStart = CornerSize(0), bottomEnd = CornerSize(0))

/**
 * Album and collection covers ([Shapes.medium]). The image, the container it sits in and the card's press ripple all
 * clip to this one shape, so no frame or mismatched corner can show between them. Draw covers with AlbaCover.
 */
internal val Shapes.cover: Shape get() = medium

/** Photo grid tiles: nearly square so the grid reads as one surface. */
internal val Shapes.tile: Shape get() = TileShape

/** Cells inside a collection mosaic. */
internal val Shapes.mosaicCell: Shape get() = MosaicCellShape

private val TileShape = RoundedCornerShape(2.dp)
private val MosaicCellShape = RoundedCornerShape(3.dp)
