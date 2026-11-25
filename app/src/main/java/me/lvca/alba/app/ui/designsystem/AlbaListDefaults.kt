package me.lvca.alba.app.ui.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing

/** Shared measures of [AlbaListGroup]'s segments. */
internal object AlbaListDefaults {
    /** The gap between segments, through which the page shows. */
    val SegmentGap = 2.dp

    /** One padding for every row and segment, on both sides. */
    val Padding = AlbaSpacing.lg

    /** The inner corners of a segment; the group's outer corners come from [MaterialTheme.shapes.large]. */
    val segmentShape: Shape @Composable get() = MaterialTheme.shapes.extraSmall
}
