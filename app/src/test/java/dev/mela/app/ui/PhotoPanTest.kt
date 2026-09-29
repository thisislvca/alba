package dev.mela.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoPanTest {
    @Test fun letterboxedPhotosCannotBePannedOutOfView() {
        val landscape = IntSize(1000, 500)
        val portraitWindow = IntSize(100, 200)
        assertEquals(Offset.Zero, constrainPhotoPan(Offset(500f, 500f), portraitWindow, landscape, 1f))
        assertEquals(Offset(50f, 0f), constrainPhotoPan(Offset(500f, 500f), portraitWindow, landscape, 2f))
        assertEquals(Offset(-200f, -25f), constrainPhotoPan(Offset(-500f, -500f), portraitWindow, landscape, 5f))
    }
}
