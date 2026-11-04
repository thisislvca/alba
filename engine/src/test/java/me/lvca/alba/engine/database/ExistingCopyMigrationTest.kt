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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Schema 19 to 20 on the JVM: a journal written by the previous version opens, keeps its transfers, and gains the
 * existing-copy column and the catalog size index. The schema-19 database is built from Room's exported 19.json.
 */
@RunWith(AndroidJUnit4::class)
class ExistingCopyMigrationTest {
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
    fun schemaNineteenGainsTheExistingCopyColumnKeepsTransfersAndRenamesPairScopes() = runBlocking {
        createSchema19()

        val migrated = AlbaDatabase.create(context)
        try {
            val transfer = requireNotNull(migrated.transferDao().findTransfer("transfer"))
            assertEquals(TransferState.READY, transfer.state)
            assertNull("Existing transfers have not been looked up yet", transfer.existingCopyCheck)

            val db = migrated.openHelper.writableDatabase
            val scopes = db.query("SELECT remotePairId, databaseScope FROM remote_asset_pairs ORDER BY remotePairId").use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
            }
            assertEquals(
                "Verified pairs move to the scope the gallery's display links look for; others are left alone",
                mapOf("old" to RemoteAssetPairEntity.PRIVATE_DATABASE_SCOPE, "shared" to "shared"),
                scopes,
            )

            val indexes = db.query("PRAGMA index_list(`catalog_items`)").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            assertTrue("index_catalog_items_byteCount" in indexes)
        } finally {
            migrated.close()
        }
    }

    private fun createSchema19() {
        val schema = JSONObject(File(SCHEMA_19).readText()).getJSONObject("database")
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
                "INSERT INTO transfers (transferId, stagedSourceId, accountId, authEpoch, operationFence, authorityType, " +
                    "authorityId, displayName, state, stateVersion, createdAtEpochMillis, updatedAtEpochMillis, automaticRetries) " +
                    "VALUES ('transfer', 'source', 'account', 1, 1, 'AUTOMATIC', 'enrollment', 'IMG_0001.HEIC', 'READY', 0, 1, 1, 0)",
            )
            for ((id, scope) in listOf("old" to "com.apple.photos.cloud/private", "shared" to "shared")) {
                db.execSQL(
                    "INSERT INTO remote_asset_pairs (remotePairId, accountId, observedAuthEpoch, databaseScope, zoneName, " +
                        "masterRecordName, masterChangeTag, masterDeleted, assetRecordName, assetChangeTag, assetDeleted, " +
                        "relationIsCurrent, originalResourceIdentity, hydratedAtEpochMillis) " +
                        "VALUES ('$id', 'account', 1, '$scope', 'PrimarySync', 'master-$id', 'tag', 0, 'asset-$id', 'tag', 0, 1, 'res', 1)",
                )
            }
            db.version = 19
        }
    }

    private companion object {
        const val DATABASE_NAME = "mela-catalog.db"

        /** Unit tests run in the module directory. */
        const val SCHEMA_19 = "schemas/me.lvca.alba.engine.database.AlbaDatabase/19.json"
    }
}
