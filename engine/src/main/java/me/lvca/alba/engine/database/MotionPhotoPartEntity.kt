package me.lvca.alba.engine.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * The video part of a motion photo backed up as a Live Photo or as a photo and a video: one row per still's transfer,
 * created when the still is queued under either setting. Its video is read out of the phone's file once the still is
 * backed up; a file with no motion video ends [NOT_MOTION].
 */
@Entity(
    tableName = "motion_photo_parts",
    indices = [Index(value = ["accountId", "mediaId"]), Index(value = ["accountId", "state"])],
)
data class MotionPhotoPartEntity(
    @PrimaryKey val transferId: String,
    val accountId: String,
    val mediaId: String?,
    /** [me.lvca.alba.engine.model.MotionPhotoMode] name: LIVE_PHOTO or PHOTO_AND_VIDEO. */
    val mode: String,
    val state: String,
    /** The UUID that pairs the still and the video, as Apple's ContentIdentifier does. */
    val contentIdentifier: String,
    /** [me.lvca.alba.engine.source.MotionPhotoFormat] id of the video found. */
    val format: String? = null,
    val videoOffset: Long? = null,
    val videoByteCount: Long? = null,
    val videoSha256Hex: String? = null,
    val durationNanos: Long? = null,
    val stillTimeNanos: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Bumped by the user's Retry after a failure, so the new attach keeps its own journal row. */
    val attachRound: Int = 0,
    val tries: Int = 0,
    val nextTryAtEpochMillis: Long? = null,
    /** A [me.lvca.alba.engine.model.StoredMessage] code saying why it waits or failed. */
    val message: String? = null,
    /** With PHOTO_AND_VIDEO, the video's own transfer. */
    val videoTransferId: String? = null,
    val masterRecordName: String? = null,
    val assetRecordName: String? = null,
    /** Apple's checksum of the attached original video, as the re-read after the attach showed it. */
    val receiptFileChecksum: String? = null,
    /** Whether the Live Photo attach wrote the asset's mediaGroupId (CloudKit accepted it). */
    val mediaGroupIdWritten: Boolean? = null,
    val attachedAtEpochMillis: Long? = null,
    /** When the video passed the byte check before Free up space. */
    val byteCheckedAtEpochMillis: Long? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    companion object {
        /** Queued with its still; the video is read out once the still is backed up. */
        const val DETECTING = "DETECTING"

        /** The file holds no motion video Alba can read: nothing more to do. */
        const val NOT_MOTION = "NOT_MOTION"

        /** LIVE_PHOTO: the video is being attached to the still. */
        const val ATTACHING = "ATTACHING"

        /** PHOTO_AND_VIDEO: the video is queued as its own item and waits to be backed up. */
        const val WAITING_VIDEO = "WAITING_VIDEO"

        /** The video is in iCloud: attached and read back, or backed up as its own item. */
        const val ATTACHED = "ATTACHED"

        /** The video could not be backed up; the still is. The user's Retry tries again. */
        const val FAILED = "FAILED"

        /** States in which the video is not in iCloud yet, but will be on its own. */
        val PENDING = setOf(DETECTING, ATTACHING, WAITING_VIDEO)
    }
}

@Dao
interface MotionPhotoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(part: MotionPhotoPartEntity)

    @Update
    suspend fun update(part: MotionPhotoPartEntity): Int

    @Query("SELECT * FROM motion_photo_parts WHERE transferId = :transferId")
    suspend fun find(transferId: String): MotionPhotoPartEntity?

    @Query("SELECT * FROM motion_photo_parts")
    fun observeAll(): Flow<List<MotionPhotoPartEntity>>

    /**
     * The next part due whose still is backed up, oldest first: one to read the video out of, or, when [allowAttach], one
     * to attach.
     */
    @Query(
        """
        SELECT p.* FROM motion_photo_parts p JOIN transfers t ON t.transferId = p.transferId
        WHERE p.accountId = :accountId AND t.state = 'VERIFIED'
          AND (p.state = 'DETECTING' OR (p.state = 'ATTACHING' AND :allowAttach))
          AND (p.nextTryAtEpochMillis IS NULL OR p.nextTryAtEpochMillis <= :now)
        ORDER BY p.createdAtEpochMillis, p.transferId LIMIT 1
        """,
    )
    suspend fun findNextDue(accountId: String, now: Long, allowAttach: Boolean): MotionPhotoPartEntity?

    /** When the next part [findNextDue] could return is due, or null when none waits. */
    @Query(
        """
        SELECT MIN(COALESCE(p.nextTryAtEpochMillis, 0)) FROM motion_photo_parts p JOIN transfers t ON t.transferId = p.transferId
        WHERE p.accountId = :accountId AND t.state = 'VERIFIED'
          AND (p.state = 'DETECTING' OR (p.state = 'ATTACHING' AND :allowAttach))
        """,
    )
    suspend fun earliestDue(accountId: String, allowAttach: Boolean): Long?

    /** PHOTO_AND_VIDEO parts whose video item is now backed up. */
    @Query(
        """
        UPDATE motion_photo_parts SET state = 'ATTACHED', attachedAtEpochMillis = :now, updatedAtEpochMillis = :now
        WHERE accountId = :accountId AND state = 'WAITING_VIDEO'
          AND videoTransferId IN (SELECT transferId FROM transfers WHERE state = 'VERIFIED')
        """,
    )
    suspend fun markVideoItemsBackedUp(accountId: String, now: Long): Int

    /** The newest part for a phone item. */
    @Query(
        """
        SELECT * FROM motion_photo_parts WHERE accountId = :accountId AND mediaId = :mediaId
          AND transferId IN (SELECT transferId FROM transfers)
        ORDER BY createdAtEpochMillis DESC LIMIT 1
        """,
    )
    suspend fun findForMedia(accountId: String, mediaId: String): MotionPhotoPartEntity?

    /** Phone items whose motion video is not backed up yet, or failed: their phone copy must stay. */
    @Query(
        """
        SELECT DISTINCT mediaId FROM motion_photo_parts
        WHERE accountId = :accountId AND mediaId IS NOT NULL AND transferId IN (SELECT transferId FROM transfers) AND state IN ('DETECTING', 'ATTACHING', 'WAITING_VIDEO', 'FAILED')
        """,
    )
    suspend fun findMediaKeptOnPhone(accountId: String): List<String>

    /** Phone items whose Live Photo is still being made: not backed up yet as what the user asked for. */
    @Query(
        """
        SELECT DISTINCT mediaId FROM motion_photo_parts
        WHERE accountId = :accountId AND mediaId IS NOT NULL AND transferId IN (SELECT transferId FROM transfers) AND mode = 'LIVE_PHOTO' AND state IN ('DETECTING', 'ATTACHING')
        """,
    )
    suspend fun findLivePending(accountId: String): List<String>

    @Query("DELETE FROM motion_photo_parts WHERE transferId = :transferId")
    suspend fun delete(transferId: String): Int

    @Query("SELECT * FROM motion_photo_parts WHERE accountId = :accountId AND state = 'FAILED'")
    suspend fun findFailed(accountId: String): List<MotionPhotoPartEntity>
}
