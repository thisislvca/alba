package me.lvca.alba.app.account

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lvca.alba.app.R
import me.lvca.alba.app.diagnostics.AlbaDiagnostics
import me.lvca.alba.app.diagnostics.DiagnosticAttribute
import me.lvca.alba.app.diagnostics.DiagnosticOperation
import me.lvca.alba.app.diagnostics.DiagnosticOutcome
import me.lvca.alba.app.gallery.LibrarySync
import me.lvca.alba.app.messages.UiMessage
import me.lvca.alba.app.messages.UserMessages
import me.lvca.alba.app.messages.localizedStoredMessage
import me.lvca.alba.app.messages.toUserMessage
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.engine.model.AccountChangeResult
import me.lvca.alba.engine.model.CloudAccountProfile
import me.lvca.alba.engine.model.CloudStorageUsage
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.PhotoCompanion
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.protocol.account.AppleSignInResult
import me.lvca.alba.protocol.account.ICloudAccountManager
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import java.time.Instant

data class AccountInfo(
    val profile: CloudAccountProfile? = null,
    val storage: CloudStorageUsage? = null,
    val checking: Boolean = false,
    val error: UiMessage? = null,
    val photosAvailable: Boolean? = null,
)

/** The account, its connection, and the library refresh that depends on it. */
data class SessionState(
    val accountState: ICloudAccountState = ICloudAccountState.Restoring,
    /** Signing in, verifying a code or sending a new one. */
    val accountBusy: Boolean = false,
    val accountInfo: AccountInfo = AccountInfo(),
    val isAccountOpen: Boolean = false,
    val network: NetworkStatus? = null,
    val isRefreshing: Boolean = true,
    val lastRefreshedAt: Instant? = null,
    /** Restoring the saved account failed; the account screen offers a retry instead of a spinner. */
    val restoreFailed: Boolean = false,
) {
    val cloudSourceLabel: String
        get() = if (accountState != ICloudAccountState.Demo) "iCloud Personal Library" else "Demo iCloud library"
}

private data class AccountFlags(val busy: Boolean, val info: AccountInfo, val open: Boolean)

/**
 * Sign-in, two-factor verification, sign-out, switching between the demo and iCloud, and connection checks.
 * Work that belongs to the outgoing account is stopped through [cancelCloudWork]; [onAccountChanged] resets the rest.
 */
internal class AccountController(
    private val scope: CoroutineScope,
    private val savedState: SavedStateHandle,
    private val accountManager: ICloudAccountManager,
    private val photos: PhotoCompanion,
    private val repository: GalleryRepository,
    private val diagnostics: AlbaDiagnostics,
    private val messages: UserMessages,
    private val sync: LibrarySync,
    network: StateFlow<NetworkStatus?>,
    private val cancelCloudWork: suspend () -> Unit,
    private val onAccountChanged: suspend (refresh: Boolean) -> Unit,
) {
    private val busy = MutableStateFlow(false)
    private val accountInfo = MutableStateFlow(AccountInfo())
    private val accountOpen = MutableStateFlow(savedState[ACCOUNT_OPEN] ?: false)
    private var accountInfoJob: Job? = null
    private var signInPreviousDsid: String? = null

    val state: StateFlow<SessionState> = combine(
        accountManager.state,
        combine(busy, accountInfo, accountOpen, ::AccountFlags),
        network,
        sync.state,
    ) { account, flags, network, sync ->
        SessionState(
            accountState = account,
            accountBusy = flags.busy,
            accountInfo = flags.info,
            isAccountOpen = flags.open,
            network = network,
            isRefreshing = sync.isRefreshing,
            lastRefreshedAt = sync.lastRefreshedAt,
            restoreFailed = sync.restoreFailed,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), SessionState())

    fun openAccount() {
        savedState[ACCOUNT_OPEN] = true
        accountOpen.value = true
        checkConnection()
    }

    fun closeAccount() {
        savedState[ACCOUNT_OPEN] = false
        accountOpen.value = false
    }

    fun startDemo() = selectLibraryMode(demo = true)

    fun connectAccount() = selectLibraryMode(demo = false)

    private fun selectLibraryMode(demo: Boolean) {
        scope.launch {
            try {
                cancelCloudWork()
                sync.restoreAccount()
                if (accountManager.session.value == null) {
                    accountManager.selectDemo(demo)
                    accountChanged()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                messages.show(error.toUserMessage(R.string.message_could_not_restore_the_saved_account_refresh_to_retry))
            }
            if (!demo) openAccount()
        }
    }

    /** Re-checks the session, storage and profile. The demo refreshes its sample library instead. */
    fun checkConnection() {
        if (accountManager.state.value == ICloudAccountState.SignedOut) return
        if (accountManager.state.value == ICloudAccountState.Demo) sync.launchRefresh(replaceActive = false)
        if (accountInfoJob?.isActive == true || busy.value) return
        accountInfoJob = scope.launch {
            val span = diagnostics.start(DiagnosticOperation.ICLOUD_STORAGE, mapOf(DiagnosticAttribute.ACTION to "connection_check"))
            val identity = accountManager.session.value?.dsid
            val previousStatus = (accountManager.state.value as? ICloudAccountState.SignedIn)?.status
            accountInfo.update { it.copy(checking = true, error = null) }
            try {
                if (accountManager.state.value is ICloudAccountState.SignedIn) accountManager.restore(force = true)
                if (identity != accountManager.session.value?.dsid) {
                    span.finish(DiagnosticOutcome.CANCELLED)
                    return@launch
                }
                val session = accountManager.session.value
                val currentStatus = (accountManager.state.value as? ICloudAccountState.SignedIn)?.status
                if (currentStatus == SessionStatus.PHOTOS_NOT_ENABLED) {
                    accountInfo.value = AccountInfo(photosAvailable = false)
                    span.finish(DiagnosticOutcome.SUCCESS)
                    return@launch
                }
                if (session != null && accountManager.authorizedSession == null) {
                    error(StoredMessage.RECONNECT_TO_ICLOUD_TO_CHECK_STORAGE_SAVED_COPIES_REMAIN_AVAILABLE.code)
                }
                val storage = withContext(Dispatchers.IO) { repository.storageUsage() }
                if (identity != accountManager.session.value?.dsid) return@launch
                accountInfo.update { it.copy(storage = storage, photosAvailable = session?.webservices?.containsKey("ckdatabasews")) }
                val profile = withContext(Dispatchers.IO) {
                    try { repository.accountProfile() }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        null
                    }
                }
                if (identity != accountManager.session.value?.dsid) return@launch
                accountInfo.update { it.copy(profile = profile) }
                if (previousStatus == SessionStatus.PHOTOS_NOT_ENABLED && currentStatus == SessionStatus.VERIFIED) {
                    sync.launchRefresh(replaceActive = true)
                }
                span.finish(DiagnosticOutcome.SUCCESS)
            } catch (error: Exception) {
                if (error is CancellationException) {
                    span.finish(DiagnosticOutcome.CANCELLED)
                    throw error
                }
                span.finish(DiagnosticOutcome.FAILED, error = error)
                accountInfo.update { it.copy(error = error.toUserMessage(R.string.message_could_not_check_icloud)) }
            } finally {
                accountInfo.update { it.copy(checking = false) }
            }
        }
    }

    fun signIn(appleId: String, password: String) {
        if (busy.value) return
        signInPreviousDsid = accountManager.session.value?.dsid
        busy.value = true
        scope.launch {
            try {
                val accountChange = photos.accountWillChange()
                if (accountChange is AccountChangeResult.Blocked) {
                    messages.show(localizedStoredMessage(accountChange.reason))
                    return@launch
                }
                cancelCloudWork()
                messages.clear()
                val result = accountManager.signIn(appleId, password)
                if (result is AppleSignInResult.SignedIn) accountChanged(refresh = accountManager.authorizedSession != null)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                messages.show(error.toUserMessage(R.string.message_could_not_sign_in_to_icloud))
            } finally {
                busy.value = false
            }
        }
    }

    fun submitTwoFactor(code: String) {
        if (busy.value) return
        busy.value = true
        scope.launch {
            try {
                cancelCloudWork()
                messages.clear()
                accountManager.submitTwoFactor(code)
                accountChanged(refresh = accountManager.authorizedSession != null)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                messages.show(error.toUserMessage(R.string.message_could_not_verify_that_code))
            } finally {
                busy.value = false
            }
        }
    }

    fun resendTwoFactor() {
        if (busy.value) return
        busy.value = true
        scope.launch {
            messages.clear()
            try {
                accountManager.resendTwoFactor()
                messages.show(R.string.message_apple_sent_another_verification_code)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                messages.show(error.toUserMessage(R.string.message_could_not_send_another_code))
            } finally {
                busy.value = false
            }
        }
    }

    fun signOut() {
        scope.launch {
            try {
                val accountChange = photos.accountWillChange()
                if (accountChange is AccountChangeResult.Blocked) {
                    messages.show(localizedStoredMessage(accountChange.reason))
                    return@launch
                }
                cancelCloudWork()
                messages.clear()
                accountManager.signOut()
                accountChanged()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                messages.show(error.toUserMessage(R.string.message_could_not_clear_the_icloud_account))
            }
        }
    }

    /** Stops a running connection check and returns its job, so the caller can wait for it. */
    fun cancelConnectionCheck(): Job? = accountInfoJob?.also { it.cancel() }

    private suspend fun accountChanged(refresh: Boolean = true) {
        accountInfoJob?.cancel()
        accountInfo.value = AccountInfo(
            photosAvailable = if ((accountManager.state.value as? ICloudAccountState.SignedIn)?.status ==
                SessionStatus.PHOTOS_NOT_ENABLED) false else null,
        )
        if (accountManager.session.value == null || signInPreviousDsid != accountManager.session.value?.dsid) {
            repository.clearCloudCatalog()
        }
        onAccountChanged(refresh)
    }

    private companion object {
        const val ACCOUNT_OPEN = "accountOpen"
    }
}
