package me.lvca.alba.engine.model

import org.junit.Assert.*
import org.junit.Test

class BatchItemTest {
    @Test fun storedStatesKeepTheirNamesAndUnknownOnesNeedAttention() {
        // The strings already stored in users' databases. Renaming a state must not change what they read as.
        val stored = listOf("WAITING", "RUNNING", "DONE", "FAILED", "NEEDS_ATTENTION", "STOPPED", "CANCELED")
        assertEquals(stored, BatchItemState.entries.map { it.name })
        assertEquals(BatchItemState.entries, stored.map(BatchItemState::fromStored))
        assertEquals(BatchItemState.NEEDS_ATTENTION, BatchItemState.fromStored("SOMETHING_NEWER"))
        assertEquals(setOf(BatchItemState.WAITING, BatchItemState.RUNNING), BatchItemState.entries.filter { it.isPending }.toSet())
    }
}
