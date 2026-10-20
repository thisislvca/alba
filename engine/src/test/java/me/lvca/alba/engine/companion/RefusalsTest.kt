package me.lvca.alba.engine.companion

import me.lvca.alba.engine.model.PhotoCompanionException
import me.lvca.alba.engine.model.StoredMessage
import org.junit.Assert.*
import org.junit.Test

class RefusalsTest {
    @Test fun refusalsCarryTheirStoredCodeAndAreFinal() {
        val refusal = runCatching { refuseUnless(false, StoredMessage.ANOTHER_UPLOAD_IS_UNRESOLVED) }.exceptionOrNull()
        assertTrue(refusal is PhotoCompanionException.Refused)
        assertEquals(StoredMessage.ANOTHER_UPLOAD_IS_UNRESOLVED.code, refusal!!.message)
        assertTrue(refusal.isFinalRefusal())

        val missing = runCatching { refuseIfNull(null as String?, StoredMessage.UNKNOWN_DEVICE_PHOTO) }.exceptionOrNull()
        assertEquals(StoredMessage.UNKNOWN_DEVICE_PHOTO, (missing as PhotoCompanionException.Refused).reason)
        assertEquals("kept", refuseIfNull("kept", StoredMessage.UNKNOWN_DEVICE_PHOTO))

        val fence = runCatching { fenceHolds(false) { "lost" } }.exceptionOrNull()
        assertTrue(fence is PhotoCompanionException.FenceLost)
        assertEquals("lost", fence!!.message)
        assertTrue(fence.isFinalRefusal())
    }

    @Test fun infrastructureFaultsAreNotFinalEvenWhenTheyAreIllegalState() {
        // Room and OkHttp report many transient faults as IllegalStateException.
        assertFalse(IllegalStateException("Cannot perform this operation because the connection pool has been closed.").isFinalRefusal())
        assertFalse(IllegalArgumentException("unexpected url").isFinalRefusal())
        assertFalse(java.io.IOException("timeout").isFinalRefusal())
    }
}
