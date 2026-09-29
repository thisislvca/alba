package dev.mela.engine.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MelaDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MelaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

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
    fun schemaThirteenMovesEveryCachePathAndRetainsCatalogAndSyncToken() = runBlocking {
        helper.createDatabase(DATABASE_NAME, 13).use { db ->
            db.execSQL("INSERT INTO catalog_items (mediaId,fileName,capturedAtEpochMillis,width,height,origin,sourceRevision,previewCachePath,viewerCachePath,originalCachePath,motionCachePath,resourceFingerprint,accentStartArgb,accentEndArgb,lastSeenScanId) VALUES ('kept','photo.heic',1,20,30,'ICLOUD','rev','/preview','/viewer','/original','/motion','fingerprint',0,0,'scan')")
            db.execSQL("INSERT INTO catalog_checkpoints VALUES ('icloud:account','token',10)")
        }
        val migrated = MelaDatabase.create(context)
        try {
            val row = requireNotNull(migrated.catalogDao().findById("kept"))
            assertEquals("/preview", row.previewCachePath)
            assertEquals("/viewer", row.viewerCachePath)
            assertEquals("/original", row.originalCachePath)
            assertEquals("/motion", row.motionCachePath)
            assertEquals("token", migrated.catalogDao().checkpoint("icloud:account")?.changeToken)
            assertEquals(null, migrated.catalogDao().observeAll().first().single().previewCachePath)
            migrated.catalogDao().deleteByOrigin("ICLOUD")
            assertTrue(migrated.catalogDao().observeCache().first().isEmpty())
        } finally { migrated.close() }
    }

    @Test
    fun schemaTwelvePreservesPersonalAlbumsAndEncryptedPendingWrites() = runBlocking {
        helper.createDatabase(DATABASE_NAME, 12).use { db ->
            db.execSQL("INSERT INTO collections VALUES ('account','album','Family',NULL,0,1)")
            db.execSQL("INSERT INTO upload_checkpoints VALUES ('pending','RECEIPT_SAVED',X'0102',10)")
        }
        val migrated = MelaDatabase.create(context)
        try {
            val album = requireNotNull(migrated.libraryDao().collection("account", "album"))
            assertEquals("Family", album.name); assertEquals(null, album.sharedGeneration)
            assertEquals("RECEIPT_SAVED", migrated.libraryDao().checkpoint("pending")?.phase)
        } finally { migrated.close() }
    }

    @Test
    fun schemaElevenPreservesUploadJournalAndOfflineMediaWhileCorrectingCloudDurations() = runBlocking {
        helper.createDatabase(DATABASE_NAME, 11).use { db ->
            db.execSQL("INSERT INTO catalog_items (mediaId,fileName,capturedAtEpochMillis,width,height,origin,sourceRevision,originalCachePath,durationMillis,accentStartArgb,accentEndArgb,lastSeenScanId) VALUES ('cloud','clip.mov',1,20,30,'ICLOUD','rev','/private/original',2000000,0,0,'scan')")
            db.execSQL("INSERT INTO catalog_items (mediaId,fileName,capturedAtEpochMillis,width,height,origin,sourceRevision,durationMillis,accentStartArgb,accentEndArgb,lastSeenScanId) VALUES ('phone','clip.mp4',1,20,30,'DEVICE','rev',2000,0,0,'scan')")
            db.execSQL("INSERT INTO catalog_checkpoints VALUES ('icloud:account','old-token',10)")
            db.execSQL("INSERT INTO catalog_checkpoints VALUES ('device:primary','device-token',10)")
            db.execSQL("INSERT INTO upload_checkpoints VALUES ('attempt','UPLOADED',X'0102',10)")
            db.execSQL("INSERT INTO upload_acceptances VALUES ('attempt','request','master','asset',0,10)")
            db.execSQL("""
                INSERT INTO staged_sources (stagedSourceId,accountId,authEpoch,operationFence,authorityType,authorityId,
                    contentUri,displayName,mimeType,lineageKey,sourceRevision,originalAcquisition,originalFormatRequested,
                    unredacted,jpegHeaderValidated,ownedFileToken,byteCount,sha256Hex,createdAtEpochMillis)
                VALUES ('stage','account',1,1,'MANUAL','ticket','content://picker/one','old.jpg','image/jpeg','lineage',
                    'rev','USER_SELECTION',1,1,1,'00000000-0000-0000-0000-000000000001.jpeg',8,'hash',10)
            """.trimIndent())
        }
        val migrated = MelaDatabase.create(context)
        try {
            val cloud = requireNotNull(migrated.catalogDao().findById("cloud"))
            assertEquals("/private/original", cloud.originalCachePath)
            assertEquals(null, cloud.durationMillis)
            assertEquals(2000L, migrated.catalogDao().findById("phone")?.durationMillis)
            assertEquals(null, migrated.catalogDao().checkpoint("icloud:account"))
            assertNotNull(migrated.catalogDao().checkpoint("device:primary"))
            assertEquals("UPLOADED", migrated.libraryDao().checkpoint("attempt")?.phase)
            val acceptance = requireNotNull(migrated.photoWriteDao().findAcceptance("attempt"))
            assertEquals("master", acceptance.masterRecordName)
            assertEquals(null, acceptance.uploadJobId)
            val stage = requireNotNull(migrated.photoWriteDao().findStagedSource("stage"))
            assertTrue(stage.formatValidated)
            assertEquals("00000000-0000-0000-0000-000000000001.jpeg", stage.ownedFileToken)
            assertEquals(null, stage.lastModifiedAtEpochMillis)
        } finally { migrated.close() }
    }

    @Test
    fun schemaTenAddsLocalGalleryFieldsWithoutLosingOfflineCopiesOrUploads() = runBlocking {
        helper.createDatabase(DATABASE_NAME, 10).use { db ->
            db.execSQL("INSERT INTO catalog_items (mediaId,fileName,capturedAtEpochMillis,width,height,origin,sourceRevision,originalCachePath,accentStartArgb,accentEndArgb,lastSeenScanId) VALUES ('kept','original.jpg',1,20,30,'ICLOUD','rev','/private/original',0,0,'scan')")
            db.execSQL("INSERT INTO upload_checkpoints VALUES ('pending','UPLOADED',X'0102',10)")
        }
        val migrated = MelaDatabase.create(context)
        try {
            val row = migrated.catalogDao().findById("kept")!!
            assertEquals("/private/original", row.originalCachePath)
            assertFalse(row.localFavorite); assertFalse(row.isTrashed); assertEquals(null, row.viewerCachePath)
            assertEquals("UPLOADED", migrated.libraryDao().checkpoint("pending")!!.phase)
        } finally { migrated.close() }
    }

    @Test
    fun schemaNineRetainsCacheAndUploadCheckpointAndRehydratesAddedDate() = runBlocking {
        helper.createDatabase(DATABASE_NAME, 9).use { db ->
            db.execSQL("INSERT INTO catalog_items (mediaId, fileName, capturedAtEpochMillis, width, height, origin, sourceRevision, originalCachePath, motionCachePath, accentStartArgb, accentEndArgb, lastSeenScanId) VALUES ('old-live', 'photo.heic', 1, 10, 10, 'ICLOUD', 'rev', '/private/original', '/private/motion', 0, 0, 'scan')")
            db.execSQL("INSERT INTO catalog_checkpoints VALUES ('icloud:account', 'old-token', 10)")
            db.execSQL("INSERT INTO upload_checkpoints VALUES ('attempt', 'UPLOADED', X'0102', 10)")
        }
        val migrated = MelaDatabase.create(context)
        try {
            val row = migrated.catalogDao().findById("old-live")!!
            assertEquals("/private/original", row.originalCachePath)
            assertEquals("/private/motion", row.motionCachePath)
            assertEquals(null, row.addedAtEpochMillis)
            assertEquals("UPLOADED", migrated.libraryDao().checkpoint("attempt")?.phase)
            migrated.openHelper.readableDatabase.query("SELECT * FROM catalog_checkpoints WHERE scope LIKE 'icloud:%'").use { assertEquals(0, it.count) }
        } finally { migrated.close() }
    }

    @Test
    fun schemaEightPreservesOfflineFilesAndUploadJournalButRebuildsResourceMetadata() = runBlocking {
        helper.createDatabase(DATABASE_NAME, 8).use { db ->
            db.execSQL("INSERT INTO catalog_items (mediaId, fileName, capturedAtEpochMillis, width, height, origin, sourceRevision, originalCachePath, accentStartArgb, accentEndArgb, lastSeenScanId) VALUES ('old-live', 'photo.heic', 1, 10, 10, 'ICLOUD', 'rev', '/private/original', 0, 0, 'scan')")
            db.execSQL("INSERT INTO catalog_checkpoints VALUES ('icloud:account', 'old-token', 10)")
            db.execSQL("INSERT INTO upload_checkpoints VALUES ('attempt', 'ALLOCATED', X'0102', 10)")
        }
        val migrated = MelaDatabase.create(context)
        try {
            val item = migrated.catalogDao().findById("old-live")!!
            assertEquals("/private/original", item.originalCachePath)
            assertEquals(null, item.motionCachePath)
            assertEquals("video/quicktime", item.motionMimeType)
            assertEquals(null, migrated.catalogDao().checkpoint("icloud:account"))
            assertEquals("ALLOCATED", migrated.libraryDao().checkpoint("attempt")!!.phase)
        } finally { migrated.close() }
    }

    @Test
    fun schemaSixPreservesCatalogAndAddsRecoverableFeatureTables() = runBlocking {
        helper.createDatabase(DATABASE_NAME, 6).use { db ->
            db.execSQL("INSERT INTO catalog_items VALUES ('existing', 'kept.jpg', 10, 20, 30, 'ICLOUD', 'rev', NULL, '/preview', '/original', 1, 2, 'scan')")
        }
        val migrated = MelaDatabase.create(context)
        try {
            val row = migrated.catalogDao().findById("existing")!!
            assertEquals("/original", row.originalCachePath)
            assertEquals("PHOTO", row.kind)
            assertTrue(migrated.libraryDao().pendingBatches().isEmpty())
            assertEquals(null, migrated.libraryDao().checkpoint("old-attempt"))
        } finally { migrated.close() }
    }

    @Test
    fun schemaTwoLegacyStageSurvivesAdditiveMigration() = runBlocking {
        helper.createDatabase(DATABASE_NAME, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO upload_transfers (
                    transferId, mediaId, fileName, accountLabel, sourceRevision,
                    localUri, stagedPath, byteCount, sha256Hex, state,
                    createdAtEpochMillis, updatedAtEpochMillis, message
                ) VALUES (
                    'legacy-transfer', 'device:1', 'legacy.jpg', 'old-label', 'rev',
                    'content://media/1', '/private/legacy.bin', 3, 'abc', 'READY_TO_UPLOAD',
                    10, 10, 'legacy staging'
                )
                """.trimIndent(),
            )
        }

        val migrated = MelaDatabase.create(context)
        try {
            val legacy = migrated.uploadTransferDao().findLatestForMedia("device:1", "old-label")
            assertNotNull(legacy)
            assertEquals(0, migrated.photoWriteDao().observeTransfers().first().size)
            assertEquals(null, migrated.photoWriteDao().findActiveAccount())
        } finally {
            migrated.close()
        }
    }

    @Test
    fun schemaOneCatalogMigratesThroughLegacyBridgeToCurrent() = runBlocking {
        helper.createDatabase(DATABASE_NAME, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO catalog_items (
                    mediaId, fileName, capturedAtEpochMillis, width, height, origin,
                    sourceRevision, localUri, previewCachePath, originalCachePath,
                    accentStartArgb, accentEndArgb, lastSeenScanId
                ) VALUES (
                    'fixture:1', 'fixture.jpg', 1, 10, 10, 'ICLOUD',
                    'one', NULL, NULL, NULL, 1, 2, 'scan'
                )
                """.trimIndent(),
            )
        }

        val migrated = MelaDatabase.create(context)
        try {
            assertEquals("fixture.jpg", migrated.catalogDao().findById("fixture:1")?.fileName)
            assertEquals(null, migrated.photoWriteDao().findActiveAccount())
        } finally {
            migrated.close()
        }
    }

    @Test
    fun schemaFiveAddsCleanupStateAndUsesScalingIndexes() {
        helper.createDatabase(DATABASE_NAME, 5).close()

        val migrated = MelaDatabase.create(context)
        try {
            val db = migrated.openHelper.writableDatabase
            val stageColumns = db.query("PRAGMA table_info(`staged_sources`)").use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
            assertTrue("releasedAtEpochMillis" in stageColumns)

            val catalogPlan = queryPlan(
                db,
                "SELECT * FROM catalog_items " +
                    "ORDER BY capturedAtEpochMillis DESC, mediaId DESC",
            )
            assertTrue(catalogPlan.any { it.contains("index_catalog_items_capturedAtEpochMillis_mediaId") })
            assertFalse(catalogPlan.any { it.contains("TEMP B-TREE") })

            val localCandidatePlan = queryPlan(
                db,
                """
                SELECT l.* FROM local_assets l
                LEFT JOIN transfers t ON t.mediaId = l.localAssetId AND t.accountId = 'account'
                  AND t.state NOT IN ('NEEDS_ATTENTION', 'UNSUPPORTED', 'ABANDONED')
                WHERE t.transferId IS NULL AND l.mimeType IN ('image/jpeg', 'image/jpg')
                  AND l.volumeName = 'external' AND l.volumeVersion = 'version'
                  AND l.permissionFingerprint = 'full:unredacted'
                ORDER BY l.generationModified, l.mediaStoreId
                LIMIT 1
                """.trimIndent(),
            )
            assertTrue(
                localCandidatePlan.any {
                    it.contains("index_local_assets_volumeName_volumeVersion_permissionFingerprint")
                },
            )
            assertFalse(localCandidatePlan.any { it.contains("TEMP B-TREE") })

            val proofPlan = queryPlan(
                db,
                """
                SELECT * FROM verification_proofs
                WHERE mediaId = 'device:1' AND accountId = 'account'
                  AND invalidatedAtEpochMillis IS NULL
                ORDER BY verifiedAtEpochMillis DESC LIMIT 1
                """.trimIndent(),
            )
            assertTrue(
                proofPlan.any {
                    it.contains("index_verification_proofs_mediaId_accountId_invalidatedAtEpochMillis")
                },
            )
            assertFalse(proofPlan.any { it.contains("TEMP B-TREE") })
        } finally {
            migrated.close()
        }
    }

    private fun queryPlan(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): List<String> =
        db.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(3))
            }
        }

    private companion object {
        const val DATABASE_NAME = "mela-catalog.db"
    }
}
