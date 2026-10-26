package me.lvca.alba.app.ui.editor

import androidx.compose.runtime.saveable.SaverScope
import me.lvca.alba.app.editing.Adjustment
import me.lvca.alba.app.editing.Adjustments
import me.lvca.alba.app.editing.EditRecipe
import me.lvca.alba.app.editing.LookFilter
import me.lvca.alba.app.platform.PhotoCrop
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

    @Test fun aSliderGestureIsOneStepAndRedoReplaysUndoneSteps() {
        val edit = PhotoEditState()
        repeat(20) { edit.slide(Adjustment.BRIGHTNESS, it * 3) }
        edit.endDrag()
        assertEquals(57, edit.current.recipe.adjustments.brightness)
        edit.choose(LookFilter.MONO)
        edit.undo(); edit.undo()
        assertFalse(edit.dirty); assertTrue(edit.canRedo)
        edit.redo(); assertEquals(57, edit.current.recipe.adjustments.brightness)
        edit.redo(); assertEquals(LookFilter.MONO, edit.current.recipe.filter)
        assertFalse(edit.canRedo)
        edit.undo()
        // A new change drops the steps that were undone.
        edit.reset(Adjustment.BRIGHTNESS)
        assertFalse(edit.canRedo)
        assertEquals(0, edit.current.recipe.adjustments.brightness)
        edit.undo(); assertEquals(57, edit.current.recipe.adjustments.brightness)
    }

    @Test fun historyAndRedoSurviveSavingAsParameters() {
        val edit = PhotoEditState()
        edit.change(EditStep(PhotoCrop(.1f, .2f, .9f, .8f), 1))
        edit.adjust(Adjustments(contrast = 30, vignette = -40, sharpness = 20))
        edit.choose(LookFilter.SEPIA)
        edit.slideFilterStrength(45); edit.endDrag()
        edit.undo()
        val saved = with(PhotoEditState.Saver) { SaverScope { true }.save(edit) }!!
        val restored = PhotoEditState.Saver.restore(saved)!!
        assertEquals(edit.current, restored.current)
        assertEquals(EditRecipe(Adjustments(contrast = 30, vignette = -40, sharpness = 20), LookFilter.SEPIA, 100), restored.current.recipe)
        restored.redo(); assertEquals(45, restored.current.recipe.filterStrength)
        restored.undo(); restored.undo(); restored.undo()
        assertEquals(EditStep(PhotoCrop(.1f, .2f, .9f, .8f), 1), restored.current)
    }
}
