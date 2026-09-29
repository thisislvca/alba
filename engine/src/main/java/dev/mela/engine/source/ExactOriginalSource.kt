package dev.mela.engine.source

import android.content.IntentSender
import dev.mela.engine.model.BackupScope
import java.io.OutputStream

data class ExactOriginalEvidence(
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val lineageKey: String,
    val sourceRevision: String,
    val acquisitionMethod: String,
    val originalFormatRequested: Boolean,
    val unredacted: Boolean,
    val formatValidated: Boolean,
    val byteCount: Long,
    val sha256Hex: String,
    val lastModifiedAtEpochMillis: Long? = null,
)

data class LocalDiscoveryCheckpoint(
    val volumeName: String,
    val volumeVersion: String,
    val generationModified: Long,
    val mediaStoreId: Long,
)

data class LocalDiscoveryPage(
    val items: List<DeviceMediaRecord>,
    val checkpoint: LocalDiscoveryCheckpoint,
    val moreComing: Boolean,
)

data class LocalTrashState(
    val isTrashed: Boolean,
    val expiresAtEpochMillis: Long?,
)

interface ExactOriginalSource {
    suspend fun copySelected(
        contentUri: String,
        displayNameHint: String?,
        output: OutputStream,
    ): ExactOriginalEvidence

    suspend fun copyMediaStoreItem(
        contentUri: String,
        expectedRevision: String,
        output: OutputStream,
    ): ExactOriginalEvidence

    suspend fun hashMediaStoreItem(
        contentUri: String,
        expectedRevision: String,
    ): ExactOriginalEvidence

    fun permissionFingerprint(): String

    fun hasAutomaticBackupAccess(): Boolean

    suspend fun discoverJpegs(
        checkpoint: LocalDiscoveryCheckpoint?,
        limit: Int,
    ): LocalDiscoveryPage

    /** Returns only camera JPEGs added strictly after enrollment, never older edited photos. */
    suspend fun discoverEligibleJpegs(
        checkpoint: LocalDiscoveryCheckpoint?,
        limit: Int,
        scope: BackupScope,
        addedAfterEpochMillis: Long,
        enrollmentBaseline: LocalDiscoveryCheckpoint? = null,
    ): LocalDiscoveryPage = error("This source cannot enforce automatic backup scope")

    suspend fun captureEnrollmentBaseline(): LocalDiscoveryCheckpoint? = null

    /** Stable during a device boot; null means reboot cannot be proved. */
    fun bootSessionId(): String? = null

    fun createTrashRequest(contentUris: List<String>): IntentSender

    suspend fun readTrashState(contentUri: String): LocalTrashState?
}
