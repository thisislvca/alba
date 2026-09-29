package dev.mela.engine.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "accounts",
    indices = [Index(value = ["active"]), Index(value = ["authSessionId"])],
)
data class AccountEntity(
    @PrimaryKey val accountId: String,
    val authSessionId: String,
    val destinationLabel: String,
    val authEpoch: Long,
    val operationFence: Long,
    val active: Boolean,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "staged_sources",
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["lineageKey"]),
        Index(value = ["sha256Hex"]),
        Index(value = ["ownedFileToken"], unique = true),
    ],
)
data class StagedSourceEntity(
    @PrimaryKey val stagedSourceId: String,
    val accountId: String,
    val authEpoch: Long,
    val operationFence: Long,
    val authorityType: String,
    val authorityId: String,
    val enrollmentId: String?,
    val enrollmentRevision: Long?,
    val mediaId: String?,
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val lineageKey: String,
    val sourceRevision: String,
    val originalAcquisition: String,
    val originalFormatRequested: Boolean,
    val unredacted: Boolean,
    @androidx.room.ColumnInfo(name = "jpegHeaderValidated") val formatValidated: Boolean,
    val ownedFileToken: String,
    val byteCount: Long,
    val sha256Hex: String,
    val createdAtEpochMillis: Long,
    val releasedAtEpochMillis: Long? = null,
    val lastModifiedAtEpochMillis: Long? = null,
)

@Entity(
    tableName = "transfers",
    indices = [
        Index(value = ["accountId", "state"]),
        Index(value = ["state"]),
        Index(value = ["stagedSourceId"], unique = true),
        Index(value = ["mediaId"]),
        Index(value = ["activeAttemptId"]),
    ],
)
data class TransferEntity(
    @PrimaryKey val transferId: String,
    val stagedSourceId: String,
    val accountId: String,
    val authEpoch: Long,
    val operationFence: Long,
    val authorityType: String,
    val authorityId: String,
    val enrollmentId: String?,
    val enrollmentRevision: Long?,
    val mediaId: String?,
    val displayName: String,
    val state: String,
    val stateVersion: Long,
    val activeAttemptId: String?,
    val leaseToken: String?,
    val leaseExpiresAtEpochMillis: Long?,
    val reconciliationDueAtEpochMillis: Long?,
    val reconciliationDeadlineEpochMillis: Long?,
    val reconciliationChangeToken: String?,
    val verifiedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val message: String?,
)

@Entity(
    tableName = "upload_attempts",
    indices = [Index(value = ["transferId"], unique = true), Index(value = ["accountId"])],
)
data class UploadAttemptEntity(
    @PrimaryKey val attemptId: String,
    val transferId: String,
    val stagedSourceId: String,
    val accountId: String,
    val authEpoch: Long,
    val operationFence: Long,
    val preUploadChangeToken: String,
    val predecessorAttemptId: String?,
    val authorizedAtEpochMillis: Long,
)

@Entity(tableName = "upload_acceptances")
data class UploadAcceptanceEntity(
    @PrimaryKey val attemptId: String,
    val requestUuid: String?,
    val masterRecordName: String?,
    val assetRecordName: String?,
    val duplicateHint: Boolean?,
    val receivedAtEpochMillis: Long,
    val uploadJobId: String? = null,
)

@Entity(tableName = "upload_uncertainties")
data class UploadUncertaintyEntity(
    @PrimaryKey val attemptId: String,
    val reason: String,
    val observedAtEpochMillis: Long,
)

@Entity(tableName = "account_write_slots")
data class AccountWriteSlotEntity(
    @PrimaryKey val accountId: String,
    val attemptId: String,
    val authEpoch: Long,
    val acquiredAtEpochMillis: Long,
)

@Entity(
    tableName = "remote_asset_pairs",
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["accountId", "masterRecordName", "assetRecordName"], unique = true),
    ],
)
data class RemoteAssetPairEntity(
    @PrimaryKey val remotePairId: String,
    val accountId: String,
    val observedAuthEpoch: Long,
    val databaseScope: String,
    val zoneName: String,
    val masterRecordName: String,
    val masterChangeTag: String,
    val masterDeleted: Boolean,
    val assetRecordName: String,
    val assetChangeTag: String,
    val assetDeleted: Boolean,
    val relationIsCurrent: Boolean,
    val originalResourceIdentity: String,
    val hydratedAtEpochMillis: Long,
)

@Entity(
    tableName = "reconciliation_matches",
    primaryKeys = ["attemptId", "remotePairId"],
    indices = [Index(value = ["remotePairId"])],
)
data class ReconciliationMatchEntity(
    val attemptId: String,
    val remotePairId: String,
    val observedAtEpochMillis: Long,
)

@Entity(
    tableName = "asset_links",
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["mediaId"]),
        Index(value = ["remotePairId"]),
        Index(value = ["attemptId"], unique = true),
    ],
)
data class AssetLinkEntity(
    @PrimaryKey val linkId: String,
    val accountId: String,
    val mediaId: String?,
    val stagedSourceId: String,
    val remotePairId: String,
    val attemptId: String,
    val linkedAtEpochMillis: Long,
    val status: String,
)

@Entity(
    tableName = "verification_proofs",
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["linkId"], unique = true),
        Index(value = ["remotePairId"]),
        Index(value = ["mediaId", "accountId", "invalidatedAtEpochMillis", "verifiedAtEpochMillis"]),
    ],
)
data class VerificationProofEntity(
    @PrimaryKey val proofId: String,
    val accountId: String,
    val verificationAuthEpoch: Long,
    val invocationAuthEpoch: Long,
    val linkId: String,
    val mediaId: String?,
    val stagedSourceId: String,
    val sourceRevision: String,
    val remotePairId: String,
    val masterChangeTag: String,
    val assetChangeTag: String,
    val originalResourceIdentity: String,
    val byteCount: Long,
    val sha256Hex: String,
    val verifiedAtEpochMillis: Long,
    val invalidatedAtEpochMillis: Long?,
    val invalidationReason: String?,
)

@Entity(
    tableName = "upload_suppressions",
    indices = [
        Index(value = ["accountId", "lineageKey"]),
        Index(value = ["accountId", "sha256Hex"]),
        Index(value = ["attemptId"]),
    ],
)
data class UploadSuppressionEntity(
    @PrimaryKey val suppressionId: String,
    val accountId: String,
    val attemptId: String,
    val lineageKey: String,
    val sha256Hex: String,
    val reason: String,
    val active: Boolean,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "backup_enrollment_drafts",
    indices = [Index(value = ["accountId"]), Index(value = ["expiresAtEpochMillis"])],
)
data class BackupEnrollmentDraftEntity(
    @PrimaryKey val draftId: String,
    val accountId: String,
    val authEpoch: Long,
    val operationFence: Long,
    val destinationLabel: String,
    val scope: String,
    val disclosureRevision: String,
    val phase: String,
    val shownAtEpochMillis: Long,
    val acceptedAtEpochMillis: Long?,
    val expiresAtEpochMillis: Long,
)

@Entity(
    tableName = "backup_enrollments",
    indices = [Index(value = ["accountId"], unique = true), Index(value = ["enabled"])],
)
data class BackupEnrollmentEntity(
    @PrimaryKey val enrollmentId: String,
    val accountId: String,
    val authorizedAuthEpoch: Long,
    val revision: Long,
    val operationFence: Long,
    val scope: String,
    val disclosureRevision: String,
    val consentedAtEpochMillis: Long,
    val permissionFingerprint: String,
    val enabled: Boolean,
    val updatedAtEpochMillis: Long,
    val discoveryBaselineVersion: String? = null,
    val discoveryBaselineGeneration: Long? = null,
)

@Entity(
    tableName = "local_assets",
    indices = [
        Index(value = ["volumeName", "mediaStoreId", "volumeVersion"], unique = true),
        Index(value = ["lineageKey"]),
        Index(
            value = [
                "volumeName",
                "volumeVersion",
                "permissionFingerprint",
                "generationModified",
                "mediaStoreId",
                "mimeType",
            ],
        ),
    ],
)
data class LocalAssetEntity(
    @PrimaryKey val localAssetId: String,
    val volumeName: String,
    val volumeVersion: String,
    val mediaStoreId: Long,
    val generationModified: Long,
    val contentUri: String,
    val lineageKey: String,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    val capturedAtEpochMillis: Long,
    val permissionFingerprint: String,
    val lastSeenAtEpochMillis: Long,
)

@Entity(tableName = "media_store_checkpoints")
data class MediaStoreCheckpointEntity(
    @PrimaryKey val checkpointId: String,
    val volumeName: String,
    val volumeVersion: String,
    val permissionFingerprint: String,
    val generationModified: Long,
    val mediaStoreId: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "reclaim_proposals",
    indices = [Index(value = ["accountId"]), Index(value = ["expiresAtEpochMillis"])],
)
data class ReclaimProposalEntity(
    @PrimaryKey val proposalId: String,
    val accountId: String,
    val authEpoch: Long,
    val operationFence: Long,
    val state: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val blockedReason: String?,
)

@Entity(
    tableName = "reclaim_proposal_items",
    primaryKeys = ["proposalId", "mediaId"],
    indices = [Index(value = ["proofId"])],
)
data class ReclaimProposalItemEntity(
    val proposalId: String,
    val mediaId: String,
    val contentUri: String,
    val proofId: String,
    val localSha256Hex: String,
    val remoteSha256Hex: String,
    val verifiedAtEpochMillis: Long,
)

@Entity(
    tableName = "trash_attempts",
    indices = [
        Index(value = ["proposalId"], unique = true),
        Index(value = ["accountId"]),
        Index(value = ["consentDisposition"]),
    ],
)
data class TrashAttemptEntity(
    @PrimaryKey val trashAttemptId: String,
    val proposalId: String,
    val accountId: String,
    val authEpoch: Long,
    val operationFence: Long,
    val consentDisposition: String,
    val handedOffAtEpochMillis: Long,
    val callbackAtEpochMillis: Long?,
    val callbackResult: String?,
    val bootSessionId: String? = null,
)

@Entity(
    tableName = "trash_obligations",
    primaryKeys = ["trashAttemptId", "mediaId"],
    indices = [Index(value = ["contentUri"])],
)
data class TrashObligationEntity(
    val trashAttemptId: String,
    val mediaId: String,
    val contentUri: String,
    val proofId: String,
    val uriState: String,
    val trashExpiresAtEpochMillis: Long?,
    val resolvedAtEpochMillis: Long?,
)
