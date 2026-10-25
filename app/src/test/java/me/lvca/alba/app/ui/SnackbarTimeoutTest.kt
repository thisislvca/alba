package me.lvca.alba.app.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnackbarTimeoutTest {
    private val asIs: (Long, Boolean) -> Long = { base, _ -> base }
    private val undo = UndoSnackbarVisuals("Moved to phone trash", "Undo")

    private fun status(duration: SnackbarDuration, action: String? = null) = object : SnackbarVisuals {
        override val message = "Saved"
        override val actionLabel = action
        override val withDismissAction = false
        override val duration = duration
    }

    @Test fun undoStaysForGooglePhotosLongDuration() {
        assertEquals(5_000L, snackbarTimeoutMillis(undo, screenReaderOn = false, recommended = asIs))
    }

    @Test fun undoStaysAtLeastTenSecondsWithAScreenReader() {
        assertEquals(10_000L, snackbarTimeoutMillis(undo, screenReaderOn = true, recommended = asIs))
    }

    @Test fun androidsAccessibilityTimeoutWinsWhenLonger() {
        var askedWithControls: Boolean? = null
        val userSetting: (Long, Boolean) -> Long = { _, controls -> askedWithControls = controls; 60_000L }
        assertEquals(60_000L, snackbarTimeoutMillis(undo, screenReaderOn = true, recommended = userSetting))
        assertEquals(true, askedWithControls)
    }

    @Test fun plainMessagesKeepMaterialDurations() {
        assertEquals(4_000L, snackbarTimeoutMillis(status(SnackbarDuration.Short), screenReaderOn = true, recommended = asIs))
        assertEquals(10_000L, snackbarTimeoutMillis(status(SnackbarDuration.Long), screenReaderOn = false, recommended = asIs))
        assertNull(snackbarTimeoutMillis(status(SnackbarDuration.Indefinite), screenReaderOn = false, recommended = asIs))
    }

    @Test fun anyActionGetsTheScreenReaderMinimum() {
        assertEquals(10_000L, snackbarTimeoutMillis(status(SnackbarDuration.Short, "Retry"), screenReaderOn = true, recommended = asIs))
    }
}
