package me.lvca.alba.engine.companion

import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.BackupEnrollmentEntity
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.AutomaticBackupPolicy
import me.lvca.alba.engine.model.BackupSettings
import me.lvca.alba.engine.source.ExactOriginalSource

/** Decides whether automatic backup may discover, stage or upload for an account right now. */
internal class AutomaticBackupAuthority(
    database: AlbaDatabase,
    private val originals: ExactOriginalSource,
    private val policy: AutomaticBackupPolicy,
) {
    private val backupDao = database.backupDao()

    val settings: BackupSettings get() = policy.settings()

    /** Whether automatic backup may upload a photo, or a video when [video], on the current network. */
    fun allows(video: Boolean): Boolean = policy.settings().allows(policy.network(), video)

    /** Whether any automatic work may use the current network. */
    fun mayUseNetwork(): Boolean = policy.settings().allowsAny(policy.network())

    /**
     * Automatic transfers wait for a network the user's settings allow for their media type; user selections never
     * do. [video] is the transfer's staged media type.
     */
    fun waitsForNetwork(authorityType: String, video: Boolean): Boolean = authorityType == AUTHORITY_AUTOMATIC && !allows(video)

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

    /** Whether queued automatic photos, or videos when [video], may run for [binding] now. */
    suspend fun canRun(binding: AccountBinding, video: Boolean): Boolean = allows(video) && currentEnrollment(binding) != null
}
