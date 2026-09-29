package dev.mela.app.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import dev.mela.app.PhotoCrop

internal data class EditStep(val crop: PhotoCrop = PhotoCrop(0f, 0f, 1f, 1f), val turns: Int = 0)

internal class PhotoEditState(initial: EditStep = EditStep(), previous: List<EditStep> = emptyList()) {
    var current by mutableStateOf(initial)
        private set
    private val history = mutableStateListOf<EditStep>().also { it.addAll(previous) }
    private var dragStart: EditStep? = null
    val dirty: Boolean get() = current != EditStep()
    val canUndo: Boolean get() = history.isNotEmpty()

    private fun remember(step: EditStep) {
        if (history.size == 40) history.removeAt(0)
        history.add(step)
    }
    fun change(step: EditStep) {
        if (step != current) { remember(current); current = step }
    }
    fun undo() { if (history.isNotEmpty()) current = history.removeAt(history.lastIndex) }
    fun beginDrag() { dragStart = current }
    fun endDrag() { dragStart?.takeIf { it != current }?.let(::remember); dragStart = null }
    fun cancelDrag() { dragStart?.let { current = it }; dragStart = null }
    fun dragCorner(corner: Int, x: Float, y: Float) {
        val c = current.crop
        current = current.copy(crop = PhotoCrop(
            if (corner == 0 || corner == 3) x.coerceIn(0f, c.right - .05f) else c.left,
            if (corner < 2) y.coerceIn(0f, c.bottom - .05f) else c.top,
            if (corner == 1 || corner == 2) x.coerceIn(c.left + .05f, 1f) else c.right,
            if (corner >= 2) y.coerceIn(c.top + .05f, 1f) else c.bottom,
        ))
    }
    fun adjustEdge(edge: Int, amount: Float) {
        val c = current.crop
        change(current.copy(crop = when (edge) {
            0 -> c.copy(left = (c.left + amount).coerceIn(0f, c.right - .05f))
            1 -> c.copy(top = (c.top + amount).coerceIn(0f, c.bottom - .05f))
            2 -> c.copy(right = (c.right + amount).coerceIn(c.left + .05f, 1f))
            else -> c.copy(bottom = (c.bottom + amount).coerceIn(c.top + .05f, 1f))
        }))
    }

    companion object {
        val Saver = listSaver<PhotoEditState, Float>(
            save = { state -> (listOf(state.current) + state.history).flatMap { listOf(it.crop.left, it.crop.top, it.crop.right, it.crop.bottom, it.turns.toFloat()) } },
            restore = { values -> values.chunked(5).map { EditStep(PhotoCrop(it[0], it[1], it[2], it[3]), it[4].toInt()) }
                .let { PhotoEditState(it.first(), it.drop(1)) } },
        )
    }
}
