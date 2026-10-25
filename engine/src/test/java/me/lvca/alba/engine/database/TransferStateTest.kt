package me.lvca.alba.engine.database

import org.junit.Assert.*
import org.junit.Test

class TransferStateTest {
    @Test fun persistedNamesRemainCompatibleAndUnknownStatesCannotRestartUploads() {
        val converter = TransferStateConverters()
        // The strings already stored in users' databases. Renaming a state must not change what it reads or writes.
        val stored = listOf("READY", "SENDING", "OUTCOME_UNKNOWN", "RECONCILING", "READ_FAILURE", "VERIFIED", "AMBIGUOUS",
            "NOT_OBSERVED", "ABANDONED", "NEEDS_SIGN_IN", "UNSUPPORTED", "NEEDS_ATTENTION")
        assertEquals(stored, TransferState.entries.map(converter::encode))
        assertEquals(TransferState.entries, stored.map(converter::decode))
        assertSame(TransferState.NEEDS_ATTENTION, converter.decode("future-state"))
    }
}
