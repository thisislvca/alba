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
 * Schema 25 to 26 on the JVM: the motion photo parts table is added and nothing else changes, so photos backed up
 * before stay stills. The schema-25 database is built from Room's exported 25.json; the device test validates the
 * result against 26.json.
 */
@RunWith(AndroidJUnit4::class)
class MotionPhotoMigrationTest {
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
    fun schemaTwentyFiveGainsMotionPhotoPartsAndKeepsItsTransfers() = runBlocking {
        createSchema25()

        val migrated = AlbaDatabase.create(context)
        try {
            assertEquals("VERIFIED", migrated.transferDao().findTransfer("verified")?.state?.name)
            val parts = migrated.motionPhotoDao()
            assertNull(parts.find("verified"))
            parts.insert(
                MotionPhotoPartEntity(
                    transferId = "verified", accountId = "account", mediaId = "device:1", mode = "LIVE_PHOTO",
                    state = MotionPhotoPartEntity.DETECTING, contentIdentifier = "ID", createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
                ),
            )
            assertEquals(listOf("device:1"), parts.findLivePending("account"))
        } finally {
            migrated.close()
        }
    }

    private fun createSchema25() {
        val schema = JSONObject(File(SCHEMA_25).readText()).getJSONObject("database")
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
            db.execSQL(
                "INSERT INTO transfers (transferId, stagedSourceId, accountId, authEpoch, operationFence, authorityType, authorityId, " +
                    "displayName, state, stateVersion, activeAttemptId, createdAtEpochMillis, updatedAtEpochMillis, message) " +
                    "VALUES ('verified', 'source', 'account', 1, 1, 'AUTOMATIC', 'enrollment', 'IMG.jpg', 'VERIFIED', 1, 'attempt', 70, 77, NULL)",
            )
            db.version = 25
        }
    }

    private companion object {
        const val DATABASE_NAME = "mela-catalog.db"

        /** Unit tests run in the module directory. */
        const val SCHEMA_25 = "schemas/me.lvca.alba.engine.database.AlbaDatabase/25.json"
    }
}
