package dev.mela.engine.database

import org.junit.Assert.*
import org.junit.Test

class TransferStateTest {
    @Test fun persistedNamesRemainCompatibleAndUnknownStatesCannotRestartUploads() {
        val converter = TransferStateConverters()
        TransferState.entries.forEach { state ->
            assertEquals(state.name, converter.encode(state))
            assertSame(state, converter.decode(state.name))
        }
        assertSame(TransferState.NEEDS_ATTENTION, converter.decode("future-state"))
    }
}
