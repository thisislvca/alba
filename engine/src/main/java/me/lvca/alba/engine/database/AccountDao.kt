package me.lvca.alba.engine.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Accounts and their fences; one account is active at a time. */
@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE active = 1 LIMIT 1")
    suspend fun findActiveAccount(): AccountEntity?

    @Query("SELECT * FROM accounts WHERE accountId = :accountId")
    suspend fun findAccount(accountId: String): AccountEntity?

    @Query("UPDATE accounts SET active = 0, operationFence = operationFence + 1, updatedAtEpochMillis = :now WHERE active = 1")
    suspend fun deactivateActiveAccounts(now: Long): Int

    @Upsert
    suspend fun upsertAccount(account: AccountEntity)
}
