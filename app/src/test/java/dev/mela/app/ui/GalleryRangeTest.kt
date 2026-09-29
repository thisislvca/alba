package dev.mela.app.ui

import org.junit.Assert.*
import org.junit.Test

class GalleryRangeTest {
    @Test fun draggingInEitherDirectionSelectsOnlyTheContiguousRange() {
        val ids = (0..20).map { "$it" }
        assertEquals(setOf("3", "4", "5", "6"), selectionRange(ids, "3", "6"))
        assertEquals(setOf("3", "4", "5", "6"), selectionRange(ids, "6", "3"))
        assertEquals(emptySet<String>(), selectionRange(ids, "gone", "3"))
        assertEquals(setOf("3"), selectionRange(ids, "3", "3"))
    }
}
