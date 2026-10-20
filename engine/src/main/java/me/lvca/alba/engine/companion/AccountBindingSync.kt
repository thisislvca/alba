package me.lvca.alba.engine.companion

import androidx.room.withTransaction
import me.lvca.alba.engine.database.AccountEntity
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.AccountChangeResult
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.PhotoCompanionException
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.WakeupScheduler
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mirrors the protocol's signed-in destination into the single active account row. A new session bumps the
 * account's epoch and fence, which retires every write authorized under the previous binding.
 */
internal class AccountBindingSync(
    private val database: AlbaDatabase,
    private val protocol: ICloudPhotoProtocol,
    private val scheduler: WakeupScheduler,
    private val stages: UploadStages,
    private val now: () -> Long,
) {
    private val accountDao = database.accountDao()
    private val backupDao = database.backupDao()
    private val transferDao = database.transferDao()
    private val trashDao = database.trashDao()
    private val gates = ConcurrentHashMap<String, Mutex>()

    /** Serializes account-fenced writes. An upload holds it while awaiting Apple. */
    fun gate(accountId: String): Mutex = gates.getOrPut(accountId, ::Mutex)

    /** Whether [binding] still names the active account under the same epoch and fence. */
    suspend fun isCurrent(binding: AccountBinding): Boolean = accountDao.findActiveAccount()?.toBinding() == binding

    suspend fun synchronize(): AccountBinding? {
        val destination = protocol.currentDestination() ?: return null
        var replacedActiveAccount = false
        val binding = database.withTransaction {
            val active = accountDao.findActiveAccount()
            if (active != null && active.accountId == destination.accountId &&
                active.authSessionId == destination.authSessionId
            ) {
                if (active.destinationLabel != destination.label) {
                    accountDao.upsertAccount(active.copy(destinationLabel = destination.label, updatedAtEpochMillis = now()))
                }
                return@withTransaction active.toBinding()
            }

            if (active != null) {
                refuseUnless(
                    trashDao.countPotentiallyExecutableTrashAttempts() == 0,
                    StoredMessage.A_SYSTEM_TRASH_REQUEST_MAY_STILL_EXECUTE_FOR_THE_PREVIOUS_ICLOUD_ACCOUNT,
                )
                retire(active, StoredMessage.THE_AUTHENTICATED_ICLOUD_SESSION_CHANGED_BEFORE_UPLOAD_SELECT_THIS_PHOTO_AGAIN)
                replacedActiveAccount = true
            }
            val previous = accountDao.findAccount(destination.accountId)
            val epoch = when {
                previous == null -> 1L
                previous.authSessionId == destination.authSessionId -> previous.authEpoch
                else -> previous.authEpoch + 1L
            }
            val account = AccountEntity(
                accountId = destination.accountId,
                authSessionId = destination.authSessionId,
                destinationLabel = destination.label,
                authEpoch = epoch,
                operationFence = (previous?.operationFence ?: 0L) + 1L,
                active = true,
                updatedAtEpochMillis = now(),
            )
            accountDao.upsertAccount(account)
            account.toBinding()
        }
        if (replacedActiveAccount) runSuspendCatching { scheduler.setAutomaticBackupEnabled(false) }
        return binding
    }

    suspend fun requireActive(): AccountBinding = synchronize()
        ?: throw PhotoCompanionException.Refused(StoredMessage.SIGN_IN_TO_ICLOUD_BEFORE_UPLOADING)

    suspend fun accountWillChange(): AccountChangeResult {
        val active = accountDao.findActiveAccount()
        if (active == null) {
            if (trashDao.countPotentiallyExecutableTrashAttempts() > 0) {
                return AccountChangeResult.Blocked(
                    StoredMessage.A_SYSTEM_TRASH_REQUEST_MAY_STILL_EXECUTE_FINISH_THE_ANDROID_DIALOG_OR_RESTART_YOUR_DEVICE_AND_R.code,
                )
            }
            runSuspendCatching { scheduler.setAutomaticBackupEnabled(false) }
            return AccountChangeResult.Allowed
        }
        val result = gate(active.accountId).withLock {
            if (trashDao.countPotentiallyExecutableTrashAttempts() > 0) {
                return@withLock AccountChangeResult.Blocked(
                    StoredMessage.A_SYSTEM_TRASH_REQUEST_MAY_STILL_EXECUTE_FINISH_THE_ANDROID_DIALOG_OR_RESTART_YOUR_DEVICE_AND_R.code,
                )
            }
            database.withTransaction {
                retire(active, StoredMessage.THE_ICLOUD_ACCOUNT_CHANGED_BEFORE_UPLOAD_SELECT_THIS_PHOTO_AGAIN_FOR_THE_NEW_ACCOUNT)
            }
            AccountChangeResult.Allowed
        }
        if (result is AccountChangeResult.Allowed) {
            stages.deleteReleasable()
            runSuspendCatching { scheduler.setAutomaticBackupEnabled(false) }
        }
        return result
    }

    /** Stops [active]'s queued uploads and backup, then deactivates it. Runs inside the caller's transaction. */
    private suspend fun retire(active: AccountEntity, reason: StoredMessage) {
        transferDao.markReadyTransfersNeedAttention(active.accountId, now(), reason.code)
        val enrollment = backupDao.findBackupEnrollment(active.accountId)
        backupDao.disableBackup(
            active.accountId,
            (enrollment?.operationFence ?: 0L) + 1L,
            now(),
        )
        accountDao.deactivateActiveAccounts(now())
    }
}
