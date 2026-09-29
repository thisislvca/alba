package dev.mela.engine.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CatalogItemEntity::class,
        MediaCacheEntity::class,
        CatalogCheckpointEntity::class,
        UploadTransferEntity::class,
        AccountEntity::class,
        StagedSourceEntity::class,
        TransferEntity::class,
        UploadAttemptEntity::class,
        UploadAcceptanceEntity::class,
        UploadUncertaintyEntity::class,
        AccountWriteSlotEntity::class,
        RemoteAssetPairEntity::class,
        ReconciliationMatchEntity::class,
        AssetLinkEntity::class,
        VerificationProofEntity::class,
        UploadSuppressionEntity::class,
        BackupEnrollmentDraftEntity::class,
        BackupEnrollmentEntity::class,
        LocalAssetEntity::class,
        MediaStoreCheckpointEntity::class,
        ReclaimProposalEntity::class,
        ReclaimProposalItemEntity::class,
        TrashAttemptEntity::class,
        TrashObligationEntity::class,
        CollectionEntity::class, CollectionMemberEntity::class, BatchItemEntity::class, UploadCheckpointEntity::class,
    ],
    version = 14,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 2, to = 5), AutoMigration(from = 7, to = 8)],
)
abstract class MelaDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun catalogDao(): CatalogDao

    abstract fun uploadTransferDao(): UploadTransferDao

    abstract fun photoWriteDao(): PhotoWriteDao

    companion object {
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS media_cache (mediaId TEXT NOT NULL PRIMARY KEY, previewCachePath TEXT, viewerCachePath TEXT, originalCachePath TEXT, motionCachePath TEXT, resourceFingerprint TEXT NOT NULL, FOREIGN KEY(mediaId) REFERENCES catalog_items(mediaId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO media_cache SELECT mediaId, previewCachePath, viewerCachePath, originalCachePath, motionCachePath, resourceFingerprint FROM catalog_items")
                db.execSQL("UPDATE catalog_items SET previewCachePath = NULL, viewerCachePath = NULL, originalCachePath = NULL, motionCachePath = NULL")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("sharedGeneration TEXT", "sharedRole TEXT", "sharedOwnerName TEXT", "sharedParticipantCount INTEGER", "sharedWebUrl TEXT")
                    .forEach { db.execSQL("ALTER TABLE collections ADD COLUMN $it") }
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE staged_sources ADD COLUMN lastModifiedAtEpochMillis INTEGER")
                db.execSQL("ALTER TABLE upload_acceptances ADD COLUMN uploadJobId TEXT")
                // Refresh cloud metadata with corrected duration units and original MIME types.
                db.execSQL("UPDATE catalog_items SET durationMillis = NULL WHERE origin = 'ICLOUD'")
                db.execSQL("DELETE FROM catalog_checkpoints WHERE scope LIKE 'icloud:%'")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("viewerCachePath TEXT", "byteCount INTEGER", "deviceFolderId TEXT", "deviceFolderName TEXT",
                    "localFavorite INTEGER NOT NULL DEFAULT 0", "isTrashed INTEGER NOT NULL DEFAULT 0", "expiresAtEpochMillis INTEGER")
                    .forEach { db.execSQL("ALTER TABLE catalog_items ADD COLUMN $it") }
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE catalog_items ADD COLUMN addedAtEpochMillis INTEGER")
                // Rehydrate the new field without discarding cached files or pending uploads.
                db.execSQL("DELETE FROM catalog_checkpoints WHERE scope LIKE 'icloud:%'")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE catalog_items ADD COLUMN motionCachePath TEXT")
                db.execSQL("ALTER TABLE catalog_items ADD COLUMN motionMimeType TEXT NOT NULL DEFAULT 'video/quicktime'")
                // Existing records need the newly requested complementary Live Photo resources.
                db.execSQL("DELETE FROM catalog_checkpoints WHERE scope LIKE 'icloud:%'")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE catalog_items ADD COLUMN kind TEXT NOT NULL DEFAULT 'PHOTO'")
                db.execSQL("ALTER TABLE catalog_items ADD COLUMN mimeType TEXT NOT NULL DEFAULT 'image/jpeg'")
                db.execSQL("ALTER TABLE catalog_items ADD COLUMN durationMillis INTEGER")
                db.execSQL("ALTER TABLE catalog_items ADD COLUMN masterRecordName TEXT")
                db.execSQL("ALTER TABLE catalog_items ADD COLUMN assetRecordName TEXT")
                db.execSQL("ALTER TABLE catalog_items ADD COLUMN resourceFingerprint TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE collections (account TEXT NOT NULL, collectionId TEXT NOT NULL, name TEXT NOT NULL, parentId TEXT, isFolder INTEGER NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(account, collectionId))")
                db.execSQL("CREATE TABLE collection_members (account TEXT NOT NULL, collectionId TEXT NOT NULL, mediaId TEXT NOT NULL, PRIMARY KEY(account, collectionId, mediaId))")
                db.execSQL("CREATE TABLE batch_items (batchId TEXT NOT NULL, mediaId TEXT NOT NULL, account TEXT NOT NULL, action TEXT NOT NULL, state TEXT NOT NULL, message TEXT, createdAt INTEGER NOT NULL, PRIMARY KEY(batchId, mediaId))")
                db.execSQL("CREATE TABLE upload_checkpoints (attemptId TEXT NOT NULL PRIMARY KEY, phase TEXT NOT NULL, sealedPayload BLOB NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("DELETE FROM catalog_checkpoints WHERE scope LIKE 'icloud:%'")
            }
        }
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `upload_transfers` (
                        `transferId` TEXT NOT NULL,
                        `mediaId` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `accountLabel` TEXT NOT NULL,
                        `sourceRevision` TEXT NOT NULL,
                        `localUri` TEXT NOT NULL,
                        `stagedPath` TEXT NOT NULL,
                        `byteCount` INTEGER NOT NULL,
                        `sha256Hex` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        `message` TEXT,
                        PRIMARY KEY(`transferId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_transfers_mediaId` ON `upload_transfers` (`mediaId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_transfers_accountLabel` ON `upload_transfers` (`accountLabel`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_transfers_state` ON `upload_transfers` (`state`)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_catalog_items_capturedAtEpochMillis`")
                db.execSQL("DROP INDEX IF EXISTS `index_catalog_items_origin`")
                db.execSQL("DROP INDEX IF EXISTS `index_catalog_items_lastSeenScanId`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_catalog_items_capturedAtEpochMillis_mediaId` " +
                        "ON `catalog_items` (`capturedAtEpochMillis`, `mediaId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_catalog_items_origin_lastSeenScanId` " +
                        "ON `catalog_items` (`origin`, `lastSeenScanId`)",
                )

                db.execSQL("ALTER TABLE `staged_sources` ADD COLUMN `releasedAtEpochMillis` INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfers_state` ON `transfers` (`state`)")

                db.execSQL("DROP INDEX IF EXISTS `index_verification_proofs_mediaId`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_verification_proofs_mediaId_accountId_invalidatedAtEpochMillis_verifiedAtEpochMillis` " +
                        "ON `verification_proofs` " +
                        "(`mediaId`, `accountId`, `invalidatedAtEpochMillis`, `verifiedAtEpochMillis`)",
                )

                db.execSQL("DROP INDEX IF EXISTS `index_local_assets_generationModified`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_local_assets_volumeName_volumeVersion_permissionFingerprint_generationModified_" +
                        "mediaStoreId_mimeType` ON `local_assets` " +
                        "(`volumeName`, `volumeVersion`, `permissionFingerprint`, `generationModified`, " +
                        "`mediaStoreId`, `mimeType`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_trash_attempts_consentDisposition` " +
                        "ON `trash_attempts` (`consentDisposition`)",
                )
            }
        }

        fun create(context: Context): MelaDatabase = Room.databaseBuilder(
            context.applicationContext,
            MelaDatabase::class.java,
            "mela-catalog.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14).build()
    }
}
