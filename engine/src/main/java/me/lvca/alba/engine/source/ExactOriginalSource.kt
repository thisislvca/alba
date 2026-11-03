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
    /** MediaStore's DATE_TAKEN, when the source has one. */
    val dateTakenEpochMillis: Long? = null,
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

/**
 * Where reading the items that were on the phone before enrollment continues: the last item read, by capture time
 * (or, without one, the time it was added) and MediaStore id, newest first. Only valid for [volumeVersion].
 */
data class ExistingMediaCursor(
    val volumeVersion: String,
    val sortKeyEpochMillis: Long,
    val mediaStoreId: Long,
)

/** One item read from the phone's existing library, and the cursor that continues after it. */
data class ExistingMedia(
    val record: DeviceMediaRecord,
    val cursor: ExistingMediaCursor,
)

data class ExistingMediaPage(
    val items: List<ExistingMedia>,
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

    /**
     * Returns the items [scope] covers in the camera folder and the chosen [folders] that were already on the phone at
     * enrollment, the complement of [discoverEligibleMedia]: newest first, one page after [cursor] at a time, so the
     * library is never held in memory. A cursor from another MediaStore version starts again from the newest item.
     */
    suspend fun discoverExistingMedia(
        cursor: ExistingMediaCursor?,
        limit: Int,
        scope: BackupScope,
        folders: Set<String>,
        addedAfterEpochMillis: Long,
        enrollmentBaseline: LocalDiscoveryCheckpoint? = null,
    ): ExistingMediaPage = ExistingMediaPage(emptyList(), moreComing = false)

    /**
     * The ids of every item [discoverEligibleMedia] covers, regardless of checkpoint, for counting progress. With
     * [includeExisting], also those [discoverExistingMedia] covers.
     */
    suspend fun eligibleMediaIds(
        scope: BackupScope,
        folders: Set<String>,
        addedAfterEpochMillis: Long,
        enrollmentBaseline: LocalDiscoveryCheckpoint? = null,
        includeExisting: Boolean = false,
    ): Set<String> = emptySet()

    /** Folders holding photos or videos on every volume, the camera first. */
    suspend fun deviceFolders(): List<DeviceFolder> = emptyList()

    suspend fun captureEnrollmentBaseline(): LocalDiscoveryCheckpoint? = null

    /** Stable during a device boot; null means reboot cannot be proved. */
    fun bootSessionId(): String? = null

    /**
     * The MediaStore revision (generation and size) of [contentUri] now, or null when this source cannot tell. Checked
     * again right before Android's trash request, so a file edited after its proof check is never trashed.
     */
    suspend fun currentRevision(contentUri: String): String? = null

    fun createTrashRequest(contentUris: List<String>): IntentSender

    suspend fun readTrashState(contentUri: String): LocalTrashState?
}
