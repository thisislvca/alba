package dev.mela.app.ui

import org.junit.Assert.*
import org.junit.Test

class PhotoEditStateTest {
    @Test fun aWholeDragIsOneUndoStepAndResetCanBeUndone() {
        val edit = PhotoEditState()
        edit.beginDrag()
        repeat(30) { edit.dragCorner(0, it / 100f, it / 100f) }
        edit.endDrag()
        val crop = edit.current
        edit.change(edit.current.copy(turns = 1))
        edit.undo(); assertEquals(crop, edit.current)
        edit.undo(); assertFalse(edit.dirty); assertFalse(edit.canUndo)
        edit.adjustEdge(0, .1f)
        val adjusted = edit.current
        edit.change(EditStep()); assertFalse(edit.dirty)
        edit.undo(); assertEquals(adjusted, edit.current)
    }
    @Test fun accessibleAdjustmentsKeepAValidCropAndCanceledDragsDoNotAddHistory() {
        val edit = PhotoEditState()
        repeat(100) { edit.adjustEdge(0, .025f); edit.adjustEdge(3, -.025f) }
        assertTrue(edit.current.crop.right - edit.current.crop.left >= .049f)
        assertTrue(edit.current.crop.bottom - edit.current.crop.top >= .049f)
        val before = edit.current
        edit.beginDrag(); edit.dragCorner(1, 1f, .3f); edit.cancelDrag()
        assertEquals(before, edit.current)
    }
}
