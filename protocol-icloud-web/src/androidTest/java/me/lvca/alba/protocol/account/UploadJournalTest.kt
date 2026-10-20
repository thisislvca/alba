package me.lvca.alba.protocol.account

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.protocol.photos.EncryptedUploadJournal
import me.lvca.alba.protocol.photos.UploadCheckpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UploadJournalTest {
    @Test fun opaqueCheckpointIsEncryptedAndBoundToAccountAndPhase() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AlbaDatabase::class.java).build()
        try {
            val journal = EncryptedUploadJournal(me.lvca.alba.engine.database.RoomUploadCheckpointStore(db))
            val binding = AccountBinding("account", 2, 3)
            val value = UploadCheckpoint("RECEIPT_SAVED", "secret-signed-url-and-receipt")
            journal.save(binding, "attempt", value)
            val row = db.libraryDao().checkpoint("attempt")!!
            assertFalse(String(row.sealedPayload).contains("secret"))
            assertEquals(value, journal.load(binding, "attempt"))
            assertTrue(runCatching { journal.load(binding.copy(authEpoch = 4), "attempt") }.isFailure)
            db.libraryDao().putCheckpoint(row.copy(phase = "RESERVED"))
            assertTrue(runCatching { journal.load(binding, "attempt") }.isFailure)
        } finally { db.close() }
    }
}
