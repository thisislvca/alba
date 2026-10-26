package me.lvca.alba.engine.source

import android.content.IntentSender
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.DeviceFolder
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

    /** Whether videos can be read. Without it, automatic backup covers photos only. */
    fun hasVideoAccess(): Boolean = false

    /**
     * Returns only items [scope] covers, from the camera folder and the chosen [folders], added strictly after
     * enrollment; never older edited items. Formats the uploader refuses are returned too, so they can be reported.
     */
    suspend fun discoverEligibleMedia(
        checkpoint: LocalDiscoveryCheckpoint?,
        limit: Int,
        scope: BackupScope,
        folders: Set<String>,
        addedAfterEpochMillis: Long,
        enrollmentBaseline: LocalDiscoveryCheckpoint? = null,
    ): LocalDiscoveryPage = error("This source cannot enforce automatic backup scope")

    /** The ids of every item [discoverEligibleMedia] covers, regardless of checkpoint, for counting progress. */
    suspend fun eligibleMediaIds(
        scope: BackupScope,
        folders: Set<String>,
        addedAfterEpochMillis: Long,
        enrollmentBaseline: LocalDiscoveryCheckpoint? = null,
    ): Set<String> = emptySet()

    /** Folders holding photos or videos on every volume, the camera first. */
    suspend fun deviceFolders(): List<DeviceFolder> = emptyList()

    suspend fun captureEnrollmentBaseline(): LocalDiscoveryCheckpoint? = null

    /** Stable during a device boot; null means reboot cannot be proved. */
    fun bootSessionId(): String? = null

    fun createTrashRequest(contentUris: List<String>): IntentSender

    suspend fun readTrashState(contentUri: String): LocalTrashState?
}
