package me.lvca.alba.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** Within this many steps of 0, a centred slider settles on 0 with a tick, as in Google Photos. */
private const val SNAP_STEPS = 2

/**
 * Google Photos' editing slider: dragging anywhere moves the value relative to where it was, never jumping to the
 * finger. A centred slider fills from its centre tick and gives a haptic tick at 0. Double-tap resets it.
 */
@Composable
internal fun EditorSlider(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    onFinished: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val centred = range.first < 0
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val finish by rememberUpdatedState(onFinished)
    val reset by rememberUpdatedState(onReset)
    val resetLabel = stringResource(R.string.reset_adjustment, label)
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f)
    fun settle(raw: Float): Int {
        val v = raw.roundToInt().coerceIn(range)
        return if (centred && abs(v) <= SNAP_STEPS) 0 else v
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Text(value.toString(), style = MaterialTheme.typography.labelLarge)
        }
        Canvas(Modifier.fillMaxWidth().height(40.dp).testTag("editor-slider").semantics {
            contentDescription = label
            stateDescription = value.toString()
            progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat())
            if (enabled) {
                setProgress { target -> change(settle(target)); finish(); true }
                customActions = listOf(CustomAccessibilityAction(resetLabel) { reset(); true })
            }
        }.pointerInput(enabled, range) {
            if (!enabled) return@pointerInput
            detectTapGestures(onDoubleTap = { reset() })
        }.pointerInput(enabled, range) {
            if (!enabled) return@pointerInput
            var position = 0f
            detectHorizontalDragGestures(
                onDragStart = { position = current.toFloat() },
                onDragEnd = { finish() },
                onDragCancel = { finish() },
            ) { pointer, amount ->
                pointer.consume()
                position = (position + amount / size.width * (range.last - range.first)).coerceIn(range.first.toFloat(), range.last.toFloat())
                val next = settle(position)
                if (next != current) {
                    if (next == 0 && centred) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    change(next)
                }
            }
        }) {
            val y = size.height / 2
            val stroke = 3.dp.toPx()
            fun x(v: Int) = (v - range.first) / (range.last - range.first).toFloat() * size.width
            drawLine(inactive, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
            val origin = if (centred) x(0) else 0f
            drawLine(active, Offset(origin, y), Offset(x(value), y), stroke, StrokeCap.Round)
            if (centred) drawLine(Color.White, Offset(origin, y - 8.dp.toPx()), Offset(origin, y + 8.dp.toPx()), 2.dp.toPx())
            drawCircle(if (enabled) active else inactive, 9.dp.toPx(), Offset(x(value), y))
        }
    }
}
