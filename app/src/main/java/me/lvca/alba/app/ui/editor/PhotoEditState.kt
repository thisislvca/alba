package me.lvca.alba.app.ui.editor

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import me.lvca.alba.app.editing.Adjustment
import me.lvca.alba.app.editing.Adjustments
import me.lvca.alba.app.editing.EditRecipe
import me.lvca.alba.app.editing.LookFilter
import me.lvca.alba.app.platform.PhotoCrop

internal data class EditStep(val crop: PhotoCrop = PhotoCrop(0f, 0f, 1f, 1f), val turns: Int = 0, val recipe: EditRecipe = EditRecipe()) {
    val hasCropOrRotation: Boolean get() = crop != PhotoCrop(0f, 0f, 1f, 1f) || turns != 0
}

/**
 * The edit as parameters with its undo and redo history. A drag or a slider gesture is one step. Only parameters are
 * saved, so the history survives rotation and process death without keeping bitmaps.
 */
internal class PhotoEditState(initial: EditStep = EditStep(), previous: List<EditStep> = emptyList(), undone: List<EditStep> = emptyList()) {
    var current by mutableStateOf(initial)
        private set
    private val history = mutableStateListOf<EditStep>().also { it.addAll(previous) }
    private val future = mutableStateListOf<EditStep>().also { it.addAll(undone) }
    private var dragStart: EditStep? = null
    val dirty: Boolean get() = current != EditStep()
    val canUndo: Boolean get() = history.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    private fun remember(step: EditStep) {
        if (history.size == HISTORY_LIMIT) history.removeAt(0)
        history.add(step)
        future.clear()
    }
    fun change(step: EditStep) {
        if (step != current) { remember(current); current = step }
    }
    fun undo() {
        if (history.isEmpty()) return
        future.add(current)
        current = history.removeAt(history.lastIndex)
    }
    fun redo() {
        if (future.isEmpty()) return
        history.add(current)
        current = future.removeAt(future.lastIndex)
    }
    fun beginDrag() { dragStart = current }
    fun endDrag() { dragStart?.takeIf { it != current }?.let(::remember); dragStart = null }
    fun cancelDrag() { dragStart?.let { current = it }; dragStart = null }

    /** Moves a slider. The first move of a gesture starts it, and [endDrag] makes the gesture one undo step. */
    fun slide(adjustment: Adjustment, value: Int) {
        if (dragStart == null) dragStart = current
        current = current.copy(recipe = current.recipe.copy(adjustments = current.recipe.adjustments.with(adjustment, value)))
    }
    fun slideFilterStrength(value: Int) {
        if (dragStart == null) dragStart = current
        current = current.copy(recipe = current.recipe.copy(filterStrength = value.coerceIn(0, MAX_STRENGTH)))
    }
    fun reset(adjustment: Adjustment) {
        endDrag()
        change(current.copy(recipe = current.recipe.copy(adjustments = current.recipe.adjustments.with(adjustment, 0))))
    }
    fun adjust(adjustments: Adjustments) = change(current.copy(recipe = current.recipe.copy(adjustments = adjustments)))
    fun choose(filter: LookFilter) = change(current.copy(recipe = current.recipe.copy(filter = filter, filterStrength = MAX_STRENGTH)))
    fun resetCrop() = change(current.copy(crop = EditStep().crop, turns = 0))

    fun dragCorner(corner: Int, x: Float, y: Float) {
        val c = current.crop
        current = current.copy(crop = PhotoCrop(
            if (corner == 0 || corner == 3) x.coerceIn(0f, c.right - MIN_CROP) else c.left,
            if (corner < 2) y.coerceIn(0f, c.bottom - MIN_CROP) else c.top,
            if (corner == 1 || corner == 2) x.coerceIn(c.left + MIN_CROP, 1f) else c.right,
            if (corner >= 2) y.coerceIn(c.top + MIN_CROP, 1f) else c.bottom,
        ))
    }
    fun adjustEdge(edge: Int, amount: Float) {
        val c = current.crop
        change(current.copy(crop = when (edge) {
            0 -> c.copy(left = (c.left + amount).coerceIn(0f, c.right - MIN_CROP))
            1 -> c.copy(top = (c.top + amount).coerceIn(0f, c.bottom - MIN_CROP))
            2 -> c.copy(right = (c.right + amount).coerceIn(c.left + MIN_CROP, 1f))
            else -> c.copy(bottom = (c.bottom + amount).coerceIn(c.top + MIN_CROP, 1f))
        }))
    }

    companion object {
        private const val HISTORY_LIMIT = 40
        private const val MIN_CROP = .05f
        private const val MAX_STRENGTH = 100

        // Crop edges and turns, then the sliders, then the look and its strength.
        private const val BOTTOM = 3
        private const val TURNS = 4
        private const val FRAME_SIZE = 5
        private val STEP_SIZE = FRAME_SIZE + Adjustment.entries.size + 2

        private fun EditStep.encode(): List<Float> = listOf(crop.left, crop.top, crop.right, crop.bottom, turns.toFloat()) +
            recipe.adjustments.toList().map(Int::toFloat) + listOf(recipe.filter.ordinal.toFloat(), recipe.filterStrength.toFloat())

        private fun decode(values: List<Float>): EditStep {
            val look = FRAME_SIZE + Adjustment.entries.size
            val adjustments = Adjustments.of(values.subList(FRAME_SIZE, look).map(Float::toInt))
            val filter = LookFilter.entries.getOrElse(values[look].toInt()) { LookFilter.NONE }
            val crop = PhotoCrop(values[0], values[1], values[2], values[BOTTOM])
            return EditStep(crop, values[TURNS].toInt(), EditRecipe(adjustments, filter, values[look + 1].toInt()))
        }

        /** Saved as [history size, redo size, current, history…, redo…], each step a fixed run of numbers. */
        val Saver = listSaver<PhotoEditState, Float>(
            save = { state ->
                listOf(state.history.size.toFloat(), state.future.size.toFloat()) +
                    (listOf(state.current) + state.history + state.future).flatMap { it.encode() }
            },
            restore = { values ->
                val historySize = values[0].toInt()
                val steps = values.drop(2).chunked(STEP_SIZE).map(::decode)
                PhotoEditState(steps.first(), steps.subList(1, 1 + historySize), steps.drop(1 + historySize))
            },
        )
    }
}
