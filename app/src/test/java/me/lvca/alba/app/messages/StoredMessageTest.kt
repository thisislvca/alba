package me.lvca.alba.app.messages

import me.lvca.alba.engine.model.StoredMessage
import org.junit.Assert.*
import org.junit.Test
import me.lvca.alba.app.R

class StoredMessageTest {
    @Test fun oldAndNewJournalMessagesUseTheSameLocalizedResource() {
        StoredMessage.entries.forEach { message ->
            assertEquals(localizedStoredMessage(message.legacyText), localizedStoredMessage(message.code))
        }
        assertEquals(UiMessage(R.string.operation_needs_attention), localizedStoredMessage("unknown"))
    }
}
