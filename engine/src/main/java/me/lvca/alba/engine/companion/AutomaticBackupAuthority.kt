package me.lvca.alba.engine.companion

import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.BackupEnrollmentEntity
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.AutomaticBackupPolicy
import me.lvca.alba.engine.source.ExactOriginalSource

/** Decides whether automatic backup may discover, stage or upload for an account right now. */
internal class AutomaticBackupAuthority(
    database: AlbaDatabase,
    private val originals: ExactOriginalSource,
    private val policy: AutomaticBackupPolicy,
) {
    private val backupDao = database.backupDao()

    fun isUnmetered(): Boolean = policy.isUnmetered()

    /** Automatic transfers wait for an unmetered network; user selections never do. */
    fun waitsForNetwork(authorityType: String): Boolean = authorityType == AUTHORITY_AUTOMATIC && !policy.isUnmetered()

    /**
     * Whether [enrollment] still authorizes automatic uploads for [binding]: enabled, consented under the same
     * sign-in, and with the media access and permissions it was granted.
     */
    suspend fun isCurrent(enrollment: BackupEnrollmentEntity?, binding: AccountBinding): Boolean =
        enrollment != null && enrollment.enabled &&
            enrollment.accountId == binding.accountId &&
            enrollment.authorizedAuthEpoch == binding.authEpoch &&
            originals.hasAutomaticBackupAccess() &&
            enrollment.permissionFingerprint == originals.permissionFingerprint()

    /** The enrollment that currently authorizes automatic backup for [binding], if any. */
    suspend fun currentEnrollment(binding: AccountBinding): BackupEnrollmentEntity? =
        backupDao.findBackupEnrollment(binding.accountId)?.takeIf { isCurrent(it, binding) }

    /** Whether the enrollment revision a transfer was created under is still the current one. */
    suspend fun stillAuthorizes(enrollmentId: String?, enrollmentRevision: Long?, binding: AccountBinding): Boolean =
        currentEnrollment(binding)?.let { it.enrollmentId == enrollmentId && it.revision == enrollmentRevision } == true

    /** Whether queued automatic transfers may run for [binding] now. */
    suspend fun canRun(binding: AccountBinding): Boolean = policy.isUnmetered() && currentEnrollment(binding) != null
}
