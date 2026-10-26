package me.lvca.alba.app.gallery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.lvca.alba.app.R
import me.lvca.alba.app.diagnostics.AlbaDiagnostics
import me.lvca.alba.app.diagnostics.DiagnosticAttribute
import me.lvca.alba.app.diagnostics.DiagnosticOperation
import me.lvca.alba.app.diagnostics.DiagnosticOutcome
import me.lvca.alba.app.diagnostics.measure
import me.lvca.alba.app.messages.UserMessages
import me.lvca.alba.app.messages.appleProtocolError
import me.lvca.alba.app.messages.toUserMessage
import me.lvca.alba.app.transfers.TransferController
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.MaintenanceWakeup
import me.lvca.alba.engine.model.WakeReason
import me.lvca.alba.protocol.account.ICloudAccountManager
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import me.lvca.alba.protocol.auth.AppleProtocolError
import java.time.Instant

/** Whether the library is refreshing, when it last did, and whether the saved account could not be opened. */
data class SyncState(
    val isRefreshing: Boolean = true,
    val lastRefreshedAt: Instant? = null,
    /** Restoring the saved account failed; the UI offers a retry instead of a spinner. */
    val restoreFailed: Boolean = false,
    /** The last refresh failed because "Access iCloud Data on the Web" is off; the account screen explains it. */
    val webAccessOff: Boolean = false,
)

/** Restores the saved account at start, and refreshes the library from iCloud and the phone. */
internal class LibrarySync(
    private val scope: CoroutineScope,
    private val repository: GalleryRepository,
    private val accountManager: ICloudAccountManager,
    private val maintenance: MaintenanceWakeup,
    private val diagnostics: AlbaDiagnostics,
    private val messages: UserMessages,
    private val previews: PreviewController,
    private val hasDeviceAccess: () -> Boolean,
    private val transfers: TransferController,
) {
    private val mutableState = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null

    /** Opens the saved account, resumes pending photo work and loads the library. */
    fun start() {
        scope.launch {
            try { restoreAccount() } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(isRefreshing = false) }
                messages.show(error.toUserMessage(R.string.message_could_not_restore_the_saved_account_refresh_to_retry))
                return@launch
            }
            wakeMaintenance(WakeReason.PROCESS_STARTED, "process_started")
                .onFailure { error -> messages.show(error.toUserMessage(R.string.message_could_not_resume_pending_photo_work)) }
            launchRefresh(replaceActive = true)
            transfers.resumePendingWork()
        }
    }

    /** Restores the saved account, recording a failure for the account screen. */
    suspend fun restoreAccount() {
        try {
            accountManager.restore()
            mutableState.update { it.copy(restoreFailed = false) }
        } catch (error: Exception) {
            if (error !is CancellationException) mutableState.update { it.copy(restoreFailed = true) }
            throw error
        }
    }

    fun refresh() {
        previews.storageBlocked = false
        scope.launch {
            if (accountManager.state.value == ICloudAccountState.Restoring && state.value.isRefreshing) return@launch
            try {
                val account = accountManager.state.value as? ICloudAccountState.SignedIn
                if (accountManager.state.value == ICloudAccountState.Restoring ||
                    (account != null && account.status != SessionStatus.VERIFIED)) restoreAccount()
                if ((accountManager.state.value as? ICloudAccountState.SignedIn)?.status == SessionStatus.PHOTOS_NOT_ENABLED) return@launch
                launchRefresh(replaceActive = false)
                transfers.schedulePendingInBackground()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                messages.show(error.toUserMessage(R.string.message_could_not_reconnect_to_icloud))
            }
        }
    }

    fun launchRefresh(replaceActive: Boolean) {
        if (refreshJob?.isActive == true) {
            if (!replaceActive) return
            refreshJob?.cancel()
        }
        // A refresh withdraws only its own earlier message: clearing every message erased the Undo that a trash shows
        // right before the refresh it starts.
        refreshJob = scope.launch(messages.ownedBy(RefreshMessages)) {
            mutableState.update { it.copy(isRefreshing = true) }
            messages.clear(RefreshMessages)
            val span = diagnostics.start(DiagnosticOperation.GALLERY_REFRESH)
            try {
                val summary = repository.refresh(includeDeviceMedia = hasDeviceAccess())
                span.finish(
                    DiagnosticOutcome.SUCCESS,
                    mapOf(
                        DiagnosticAttribute.CLOUD_COUNT to summary.cloudItemCount,
                        DiagnosticAttribute.DEVICE_COUNT to summary.deviceItemCount,
                        DiagnosticAttribute.SHARED_UNAVAILABLE to summary.sharedAlbumsUnavailable,
                    ),
                )
                mutableState.update {
                    it.copy(isRefreshing = false, lastRefreshedAt = Instant.ofEpochMilli(summary.refreshedAtEpochMillis),
                        webAccessOff = false)
                }
                if (summary.sharedAlbumsUnavailable) messages.show(R.string.shared_refresh_unavailable) else messages.clear(RefreshMessages)
                previews.retry()
            } catch (cancelled: CancellationException) {
                span.finish(DiagnosticOutcome.CANCELLED)
                throw cancelled
            } catch (error: Throwable) {
                span.finish(DiagnosticOutcome.FAILED, error = error)
                val webAccessOff = error.appleProtocolError() == AppleProtocolError.WEB_ACCESS_DISABLED
                mutableState.update { it.copy(isRefreshing = false, webAccessOff = webAccessOff) }
                messages.show(error.toUserMessage(R.string.message_could_not_refresh_the_library))
            }
        }
    }

    /** Picks up photos added, edited or removed on the phone. */
    suspend fun deviceLibraryChanged() {
        if (!hasDeviceAccess()) return
        try {
            diagnostics.measure(DiagnosticOperation.DEVICE_REFRESH) { repository.refreshDevice() }
            previews.retry()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { messages.show(R.string.device_refresh_failed) }
    }

    /** After an account change: forget the old refresh time, then load the new account's library. */
    suspend fun accountChanged(refresh: Boolean) {
        mutableState.update { it.copy(lastRefreshedAt = null) }
        if (!refresh) return
        launchRefresh(replaceActive = true)
        wakeMaintenance(WakeReason.FOREGROUND, "foreground")
        transfers.schedulePendingInBackground()
    }

    /** Cancels a running refresh and returns its job, so the caller can wait for it. */
    fun cancel(): Job? = refreshJob.also { it?.cancel(); refreshJob = null }

    fun markIdle() = mutableState.update { it.copy(isRefreshing = false) }

    private suspend fun wakeMaintenance(reason: WakeReason, label: String): Result<Unit> = runCatching {
        diagnostics.measure(DiagnosticOperation.MAINTENANCE_WAKE, mapOf(DiagnosticAttribute.REASON to label)) {
            maintenance.wake(reason)
        }
        Unit
    }.onFailure { if (it is CancellationException) throw it }
}

/** Owns the messages a library refresh shows. */
private object RefreshMessages
