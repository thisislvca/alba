package me.lvca.alba.engine.model

import org.junit.Assert.*
import org.junit.Test

class BatchItemTest {
    @Test fun storedStatesReadBackAsTheSameState() {
        BatchItemState.entries.forEach { assertSame(it, BatchItemState.fromStored(it.name)) }
        assertEquals(BatchItemState.NEEDS_ATTENTION, BatchItemState.fromStored("SOMETHING_NEWER"))
        assertEquals(setOf(BatchItemState.WAITING, BatchItemState.RUNNING), BatchItemState.entries.filter { it.isPending }.toSet())
    }
}
