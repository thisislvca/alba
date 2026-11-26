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
 * Schema 23 to 24 on the JVM: a journal written by the previous version opens with its acceptances, proofs and write
 * slot. Every proof it had was a byte comparison, so each becomes a BYTES proof checked when it was made; acceptances have
 * no receipt; the write slot of an upload in flight is kept, and an account can then hold several. The schema-23
 * database is built from Room's exported 23.json.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptMigrationTest {
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
    fun schemaTwentyThreeKeepsProofsAsByteChecksAcceptancesWithoutReceiptsAndItsWriteSlot() = runBlocking {
        createSchema23()

        val migrated = AlbaDatabase.create(context)
        try {
            val proof = requireNotNull(migrated.verificationDao().findProof("proof"))
            assertEquals(VerificationProofEntity.METHOD_BYTES, proof.method)
            assertEquals(42L, proof.byteCheckedAtEpochMillis)
            assertNull(proof.invalidatedAtEpochMillis)

            val acceptance = requireNotNull(migrated.transferDao().findAcceptance("attempt"))
            assertEquals("master", acceptance.masterRecordName)
            assertNull(acceptance.receiptFileChecksum)
            assertNull(acceptance.receiptByteCount)
            assertNull(acceptance.sentSha256Hex)

            val slot = requireNotNull(migrated.transferDao().findWriteSlot("account"))
            assertEquals("An upload in flight at the update keeps its write slot", "attempt", slot.attemptId)
            migrated.transferDao().acquireWriteSlot(AccountWriteSlotEntity("account", "second", 1, 43))
            assertEquals("The account can now hold several", 2, migrated.transferDao().countWriteSlots("account"))
        } finally {
            migrated.close()
        }
    }

    private fun createSchema23() {
        val schema = JSONObject(File(SCHEMA_23).readText()).getJSONObject("database")
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
                "INSERT INTO upload_acceptances (attemptId, requestUuid, masterRecordName, assetRecordName, duplicateHint, " +
                    "receivedAtEpochMillis, uploadJobId) VALUES ('attempt', NULL, 'master', 'asset', 0, 41, 'job')",
            )
            db.execSQL(
                "INSERT INTO verification_proofs (proofId, accountId, verificationAuthEpoch, invocationAuthEpoch, linkId, " +
                    "mediaId, stagedSourceId, sourceRevision, remotePairId, masterChangeTag, assetChangeTag, " +
                    "originalResourceIdentity, byteCount, sha256Hex, verifiedAtEpochMillis) " +
                    "VALUES ('proof', 'account', 1, 1, 'link', 'device:1', 'source', 'rev', 'pair', 'm', 'a', 'res', 7, 'sha', 42)",
            )
            db.execSQL("INSERT INTO account_write_slots (accountId, attemptId, authEpoch, acquiredAtEpochMillis) VALUES ('account', 'attempt', 1, 40)")
            db.version = 23
        }
    }

    private companion object {
        const val DATABASE_NAME = "mela-catalog.db"

        /** Unit tests run in the module directory. */
        const val SCHEMA_23 = "schemas/me.lvca.alba.engine.database.AlbaDatabase/23.json"
    }
}
