package dev.mela.engine.model

import org.junit.Assert.*
import org.junit.Test

class StoredMessageTest {
    @Test fun stableCodesAndLegacyJournalsDecodeToTheSameOutcome() {
        assertEquals(StoredMessage.entries.size, StoredMessage.entries.map { it.code }.toSet().size)
        StoredMessage.entries.forEach { message ->
            assertSame(message, StoredMessage.decode(message.code))
            assertSame(message, StoredMessage.decode(message.legacyText))
        }
        assertNull(StoredMessage.decode("unknown future outcome"))
        assertNull(StoredMessage.decode(null))
    }
}
