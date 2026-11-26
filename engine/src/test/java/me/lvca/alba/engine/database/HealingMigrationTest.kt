package me.lvca.alba.engine.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Schema 24 to 25 on the JVM: uploads already uncertain start their healing window at their last update and give back
 * the write slot they held; one iCloud could not process is recorded as rejected; an upload still being checked keeps its
 * slot. The schema-24 database is built from Room's exported 24.json.
 */
@RunWith(AndroidJUnit4::class)
class HealingMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun schemaTwentyFourStartsHealingUncertainUploadsAndGivesBackTheirWriteSlots() = runBlocking {
        createSchema24()

        val migrated = AlbaDatabase.create(context)
        try {
            val dao = migrated.transferDao()
            val ambiguous = requireNotNull(dao.findTransfer("ambiguous"))
            assertEquals(77L, ambiguous.unresolvedSinceEpochMillis)
            assertNull(ambiguous.failureReason)
            assertEquals("ICLOUD_REJECTED", dao.findTransfer("rejected")?.failureReason)
            assertNull(dao.findTransfer("checking")?.unresolvedSinceEpochMillis)
            assertEquals(listOf("attempt-c"), dao.findWriteSlots("account").map { it.attemptId })
            assertEquals(listOf("ambiguous", "rejected"), dao.findUnresolved("account").map { it.transferId }.sorted())
        } finally {
            migrated.close()
        }
    }

    private fun createSchema24() {
        val schema = JSONObject(File(SCHEMA_24).readText()).getJSONObject("database")
        val file = context.getDatabasePath(DATABASE_NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.insertTransfer("ambiguous", "AMBIGUOUS", "attempt-a", "more_than_one_exact_icloud_copy_matched_alba_will_not_upload_again")
            db.insertTransfer("rejected", "NOT_OBSERVED", "attempt-r", "upload_processing_failed")
            db.insertTransfer("checking", "RECONCILING", "attempt-c", null)
            db.execSQL("INSERT INTO account_write_slots (accountId, attemptId, authEpoch, acquiredAtEpochMillis) VALUES ('account', 'attempt-a', 1, 40)")
            db.execSQL("INSERT INTO account_write_slots (accountId, attemptId, authEpoch, acquiredAtEpochMillis) VALUES ('account', 'attempt-c', 1, 40)")
            db.version = 24
        }
    }

    private fun SQLiteDatabase.insertTransfer(id: String, state: String, attemptId: String, message: String?) {
        execSQL(
            "INSERT INTO transfers (transferId, stagedSourceId, accountId, authEpoch, operationFence, authorityType, authorityId, " +
                "displayName, state, stateVersion, activeAttemptId, createdAtEpochMillis, updatedAtEpochMillis, message) " +
                "VALUES (?, ?, 'account', 1, 1, 'AUTOMATIC', 'enrollment', 'IMG.jpg', ?, 1, ?, 70, 77, ?)",
            arrayOf<Any?>(id, "source-$id", state, attemptId, message),
        )
    }

    private companion object {
        const val DATABASE_NAME = "mela-catalog.db"

        /** Unit tests run in the module directory. */
        const val SCHEMA_24 = "schemas/me.lvca.alba.engine.database.AlbaDatabase/24.json"
    }
}
