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
}
