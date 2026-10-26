package me.lvca.alba.engine.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Observed iCloud pairs, reconciliation matches and the exact-byte proofs that link them to local photos. */
@Dao
interface VerificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRemotePair(pair: RemoteAssetPairEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReconciliationMatch(match: ReconciliationMatchEntity): Long

    @Query(
        """
        SELECT p.* FROM remote_asset_pairs p
        JOIN reconciliation_matches m ON m.remotePairId = p.remotePairId
        WHERE m.attemptId = :attemptId
        ORDER BY p.remotePairId
        """,
    )
    suspend fun findReconciliationMatches(attemptId: String): List<RemoteAssetPairEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAssetLink(link: AssetLinkEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVerificationProof(proof: VerificationProofEntity)

    @Query(
        """
        SELECT p.* FROM verification_proofs p
        WHERE p.mediaId = :mediaId AND p.accountId = :accountId
          AND p.invalidatedAtEpochMillis IS NULL
        ORDER BY p.verifiedAtEpochMillis DESC LIMIT 1
        """,
    )
    suspend fun findCurrentProof(mediaId: String, accountId: String): VerificationProofEntity?

    @Query("SELECT * FROM remote_asset_pairs WHERE remotePairId = :pairId")
    suspend fun findRemotePair(pairId: String): RemoteAssetPairEntity?

    /**
     * Phone items still in the gallery, not in Android's trash, with a current proof for the active account: what
     * "Free up space" offers. The newest proof per item counts once.
     */
    @Query(
        """
        SELECT p.mediaId AS mediaId, MAX(p.byteCount) AS byteCount FROM verification_proofs p
        JOIN accounts a ON a.accountId = p.accountId AND a.active = 1
        JOIN catalog_items c ON c.mediaId = p.mediaId AND c.origin = 'DEVICE' AND c.isTrashed = 0
        WHERE p.invalidatedAtEpochMillis IS NULL AND p.mediaId IS NOT NULL
        GROUP BY p.mediaId
        ORDER BY MIN(c.capturedAtEpochMillis), p.mediaId
        """,
    )
    suspend fun findReclaimableOnPhone(): List<ReclaimableProjection>
}

data class ReclaimableProjection(val mediaId: String, val byteCount: Long)
