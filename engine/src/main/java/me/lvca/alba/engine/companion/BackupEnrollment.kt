package me.lvca.alba.engine.companion

import android.Manifest
import android.os.Build
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.BackupEnrollmentDraftEntity
import me.lvca.alba.engine.database.BackupEnrollmentEntity
import me.lvca.alba.engine.model.AutomaticBackupPolicy
import me.lvca.alba.engine.model.BackupChange
import me.lvca.alba.engine.model.BackupDraft
import me.lvca.alba.engine.model.BackupDraftId
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.BackupSetupEffect
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.WakeupScheduler
import me.lvca.alba.engine.source.ExactOriginalSource
import java.util.UUID

/** Owns disclosure, account-fenced enrollment, and disabling future automatic uploads. */
internal class BackupEnrollment(
    private val database: AlbaDatabase,
    private val originals: ExactOriginalSource,
    private val scheduler: WakeupScheduler,
    private val now: () -> Long,
    private val automaticBackupPolicy: AutomaticBackupPolicy,
    private val accounts: AccountBindingSync,
    private val stages: UploadStages,
) {
    private val accountDao = database.accountDao()
    private val backupDao = database.backupDao()
    private val transferDao = database.transferDao()

    fun observeBackup(): Flow<BackupView> = backupDao.observeBackup().map { row ->
        if (row == null) {
            BackupView(enabled = false)
        } else {
            BackupView(
                enabled = row.enabled,
                destinationLabel = row.destinationLabel,
                scope = BackupScope.valueOf(row.scope),
                message = when {
                    !row.enabled -> StoredMessage.AUTOMATIC_BACKUP_IS_OFF.code
                    !originals.hasAutomaticBackupAccess() -> StoredMessage.BACKUP_PAUSED_ALLOW_FULL_PHOTO_AND_MEDIA_LOCATION_ACCESS.code
                    !automaticBackupPolicy.isUnmetered() -> StoredMessage.BACKUP_PAUSED_WAITING_FOR_AN_UNMETERED_NETWORK.code
                    else -> StoredMessage.NEW_CAMERA_JPEGS_WILL_BACK_UP_AUTOMATICALLY.code
                },
            )
        }
    }

    suspend fun beginBackupEnrollment(scope: BackupScope): BackupDraft {
        val binding = accounts.requireActive()
        val account = requireNotNull(accountDao.findAccount(binding.accountId))
        val createdAt = now()
        val draft = BackupEnrollmentDraftEntity(
            draftId = UUID.randomUUID().toString(),
            accountId = binding.accountId,
            authEpoch = binding.authEpoch,
            operationFence = binding.operationFence,
            destinationLabel = account.destinationLabel,
            scope = scope.name,
            disclosureRevision = BACKUP_DISCLOSURE_REVISION,
            phase = "SHOWN",
            shownAtEpochMillis = createdAt,
            acceptedAtEpochMillis = null,
            expiresAtEpochMillis = createdAt + BACKUP_DRAFT_LIFETIME_MILLIS,
        )
        backupDao.insertBackupDraft(draft)
        return BackupDraft(
            id = BackupDraftId(draft.draftId),
            destinationLabel = draft.destinationLabel,
            disclosure = "Alba will automatically read newly added JPEG photos in DCIM/Camera on this phone and upload exact " +
                "original bytes directly to ${draft.destinationLabel} on an unmetered network. Uploads pause when access, consent, " +
                "or the destination account changes.",
        )
    }

    suspend fun acceptBackupDisclosure(draft: BackupDraftId): BackupSetupEffect {
        val row = backupDao.findBackupDraft(draft.value)
            ?: return BackupSetupEffect.Refused(StoredMessage.THIS_BACKUP_SETUP_HAS_EXPIRED_START_AGAIN.code)
        val binding = accounts.synchronize()
            ?: return BackupSetupEffect.Refused(StoredMessage.SIGN_IN_TO_ICLOUD_BEFORE_ENABLING_AUTOMATIC_BACKUP.code)
        if (row.accountId != binding.accountId || row.authEpoch != binding.authEpoch ||
            row.operationFence != binding.operationFence || row.expiresAtEpochMillis <= now()
        ) {
            return BackupSetupEffect.Refused(StoredMessage.THE_DESTINATION_ACCOUNT_CHANGED_START_BACKUP_SETUP_AGAIN.code)
        }
        if (backupDao.acceptBackupDraft(draft.value, now()) != 1) {
            return BackupSetupEffect.Refused(StoredMessage.THIS_BACKUP_SETUP_IS_NO_LONGER_ACTIVE.code)
        }
        return BackupSetupEffect.RequestMediaPermissions(automaticBackupPermissions())
    }

    suspend fun finishBackupEnrollment(draft: BackupDraftId): BackupView {
        val row = refuseIfNull(backupDao.findBackupDraft(draft.value), StoredMessage.BACKUP_SETUP_EXPIRED)
        refuseUnless(row.phase == "CONSENTED", StoredMessage.ACCEPT_THE_BACKUP_DISCLOSURE_FIRST)
        val binding = accounts.requireActive()
        refuseUnless(
            row.accountId == binding.accountId && row.authEpoch == binding.authEpoch,
            StoredMessage.THE_DESTINATION_ACCOUNT_CHANGED_START_BACKUP_SETUP_AGAIN,
        )
        refuseUnless(
            originals.hasAutomaticBackupAccess(),
            StoredMessage.AUTOMATIC_BACKUP_NEEDS_FULL_PHOTO_ACCESS_AND_PRECISE_MEDIA_LOCATION_ACCESS,
        )

        val discoveryBaseline = originals.captureEnrollmentBaseline()
        val view = accounts.gate(binding.accountId).withLock {
            database.withTransaction {
                val active = requireNotNull(accountDao.findActiveAccount())
                refuseUnless(active.accountId == binding.accountId && active.authEpoch == binding.authEpoch &&
                    active.operationFence == binding.operationFence,
                    StoredMessage.THE_ACCOUNT_CHANGED_WHILE_BACKUP_WAS_BEING_ENABLED,
                )
                val current = backupDao.findBackupEnrollment(active.accountId)
                refuseUnless(backupDao.completeBackupDraft(draft.value) == 1, StoredMessage.THIS_BACKUP_SETUP_WAS_ALREADY_COMPLETED)
                val enrollment = BackupEnrollmentEntity(
                    enrollmentId = current?.enrollmentId ?: UUID.randomUUID().toString(),
                    accountId = active.accountId,
                    authorizedAuthEpoch = active.authEpoch,
                    revision = (current?.revision ?: 0L) + 1L,
                    operationFence = (current?.operationFence ?: 0L) + 1L,
                    scope = row.scope,
                    discoveryBaselineVersion = discoveryBaseline?.volumeVersion,
                    discoveryBaselineGeneration = discoveryBaseline?.generationModified,
                    disclosureRevision = row.disclosureRevision,
                    consentedAtEpochMillis = requireNotNull(row.acceptedAtEpochMillis),
                    permissionFingerprint = originals.permissionFingerprint(),
                    enabled = true,
                    updatedAtEpochMillis = now(),
                )
                backupDao.upsertBackupEnrollment(enrollment)
                BackupView(
                    enabled = true,
                    destinationLabel = active.destinationLabel,
                    scope = BackupScope.valueOf(enrollment.scope),
                    message = StoredMessage.AUTOMATIC_JPEG_BACKUP_IS_ON.code,
                )
            }
        }
        scheduler.setAutomaticBackupEnabled(true)
        scheduler.scheduleSoon()
        return view
    }

    suspend fun changeBackup(change: BackupChange): BackupView = when (change) {
        BackupChange.Disable -> disableAutomaticBackup()
    }

    private suspend fun disableAutomaticBackup(): BackupView {
        val binding = accounts.synchronize()
        if (binding == null) {
            scheduler.setAutomaticBackupEnabled(false)
            return BackupView(false, message = StoredMessage.AUTOMATIC_BACKUP_IS_OFF.code)
        }
        val result = accounts.gate(binding.accountId).withLock {
            database.withTransaction {
                val account = requireNotNull(accountDao.findActiveAccount())
                val enrollment = backupDao.findBackupEnrollment(account.accountId)
                val nextEnrollmentFence = (enrollment?.operationFence ?: 0L) + 1L
                backupDao.disableBackup(account.accountId, nextEnrollmentFence, now())
                transferDao.markReadyAutomaticTransfersNeedAttention(
                    account.accountId,
                    now(),
                    StoredMessage.AUTOMATIC_BACKUP_WAS_DISABLED_BEFORE_THIS_UPLOAD_STARTED.code,
                )
                BackupView(false, account.destinationLabel, message = StoredMessage.AUTOMATIC_BACKUP_IS_OFF.code)
            }
        }
        stages.deleteReleasable()
        scheduler.setAutomaticBackupEnabled(false)
        return result
    }

    private fun automaticBackupPermissions(): List<String> = buildList {
        add(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            },
        )
        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    private companion object {
        const val BACKUP_DISCLOSURE_REVISION = "2026-08-23.1"
        const val BACKUP_DRAFT_LIFETIME_MILLIS = 15 * 60 * 1_000L
    }
}
