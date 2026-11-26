package me.lvca.alba.app.gallery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
import me.lvca.alba.engine.model.CatalogScanProgress
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.MaintenanceWakeup
import me.lvca.alba.engine.model.WakeReason
import me.lvca.alba.protocol.account.ICloudAccountManager
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import me.lvca.alba.protocol.auth.AppleProtocolError
import me.lvca.alba.protocol.photos.LibraryPreparingException
import java.time.Instant

/** Whether the library is refreshing, when it last did, and whether the saved account could not be opened. */
data class SyncState(
    val isRefreshing: Boolean = true,
    /** The running refresh is one the user started by pulling down: only then does the pull indicator show. */
    val pullRefreshing: Boolean = false,
    /** When the signed-in library last finished a refresh, in this session or an earlier one, here or in the background. */
    val lastRefreshedAt: Instant? = null,
    /** Restoring the saved account failed; the UI offers a retry instead of a spinner. */
    val restoreFailed: Boolean = false,
    /** The last refresh failed because "Access iCloud Data on the Web" is off; the account screen explains it. */
    val webAccessOff: Boolean = false,
    /** The first full read of the iCloud library, while it is unfinished. */
    val catalogScan: CatalogScanProgress? = null,
    /** Apple is still indexing the iCloud library, so Alba cannot read it yet; it keeps checking. */
    val libraryPreparing: LibraryPreparing? = null,
)

/** Apple is still indexing the iCloud library; [progress] is its percentage, when Apple said. */
data class LibraryPreparing(val progress: Int? = null)

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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        scope.launch {
            repository.observeCatalogScan().collect { progress -> mutableState.update { it.copy(catalogScan = progress) } }
        }
        // A refresh that finished in background work, or before Alba last stopped, counts too. It is the restored
        // account's: the saved account is restored after this starts, so it is observed again for each account.
        scope.launch {
            accountManager.session.map { it?.dsid }.distinctUntilChanged().flatMapLatest {
                repository.observeLastRefresh()
            }.filterNotNull().collect { millis ->
                val stored = Instant.ofEpochMilli(millis)
                mutableState.update { state -> if (state.lastRefreshedAt?.isBefore(stored) != false) state.copy(lastRefreshedAt = stored) else state }
            }
        }
        scope.launch {
            try { restoreAccount() } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(isRefreshing = false) }
                messages.show(error.toUserMessage(R.string.message_could_not_restore_the_saved_account_refresh_to_retry))
                return@launch
            }
            // The library refresh does not wait for photo work. Waking it waits for any backup pass in progress, an
            // upload included, and the refresh used to wait behind it with the library showing as refreshing.
            launchRefresh(replaceActive = true)
            wakeMaintenance(WakeReason.PROCESS_STARTED, "process_started")
                .onFailure { error -> messages.show(error.toUserMessage(R.string.message_could_not_resume_pending_photo_work)) }
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

    /**
     * Refreshes the library, or joins the refresh already running. Joining one still reads the phone's photos again at
     * once: the running refresh read them when it started, and a photo deleted since must not wait for it to finish.
     * [fromPull] shows the pull indicator, only while a refresh this pull started runs.
     */
    fun refresh(fromPull: Boolean = false) {
        previews.storageBlocked = false
        val pulled = fromPull && refreshJob?.isActive != true
        if (pulled) mutableState.update { it.copy(pullRefreshing = true) }
        scope.launch {
            var started = false
            try {
                if (accountManager.state.value == ICloudAccountState.Restoring && state.value.isRefreshing) return@launch
                val account = accountManager.state.value as? ICloudAccountState.SignedIn
                if (accountManager.state.value == ICloudAccountState.Restoring ||
                    (account != null && account.status != SessionStatus.VERIFIED)) restoreAccount()
                if ((accountManager.state.value as? ICloudAccountState.SignedIn)?.status == SessionStatus.PHOTOS_NOT_ENABLED) return@launch
                started = launchRefresh(replaceActive = false, fromPull = pulled)
                if (!started) deviceLibraryChanged()
                transfers.schedulePendingInBackground()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                messages.show(error.toUserMessage(R.string.message_could_not_reconnect_to_icloud))
            } finally {
                if (pulled && !started) mutableState.update { it.copy(pullRefreshing = false) }
            }
        }
    }

    /**
     * Starts a refresh, replacing a running one when [replaceActive]; returns whether it started one. Whichever way a
     * refresh ends (finished, failed or cancelled), the library stops showing as refreshing unless another replaced it.
     */
    fun launchRefresh(replaceActive: Boolean, fromPull: Boolean = false): Boolean {
        if (refreshJob?.isActive == true) {
            if (!replaceActive) return false
            refreshJob?.cancel()
        }
        // A refresh withdraws only its own earlier message: clearing every message erased the Undo that a trash shows
        // right before the refresh it starts.
        val job = scope.launch(messages.ownedBy(RefreshMessages), start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            try { runRefresh(fromPull) } finally {
                if (refreshJob === self || refreshJob?.isActive != true) {
                    mutableState.update { it.copy(isRefreshing = false, pullRefreshing = false) }
                }
            }
        }
        refreshJob = job
        job.start()
        return true
    }

    private suspend fun runRefresh(fromPull: Boolean) {
        mutableState.update { it.copy(isRefreshing = true, pullRefreshing = fromPull) }
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
                it.copy(isRefreshing = false, pullRefreshing = false,
                    lastRefreshedAt = maxOf(Instant.ofEpochMilli(summary.refreshedAtEpochMillis), it.lastRefreshedAt ?: Instant.EPOCH),
                    webAccessOff = false, libraryPreparing = null)
            }
            when {
                summary.sharedAlbumsUnavailable -> messages.show(R.string.shared_refresh_unavailable)
                summary.hiddenUnavailable -> messages.show(R.string.hidden_refresh_unavailable)
                else -> messages.clear(RefreshMessages)
            }
            previews.retry()
        } catch (cancelled: CancellationException) {
            span.finish(DiagnosticOutcome.CANCELLED)
            throw cancelled
        } catch (error: Throwable) {
            span.finish(DiagnosticOutcome.FAILED, error = error)
            val webAccessOff = error.appleProtocolError() == AppleProtocolError.WEB_ACCESS_DISABLED
            val preparing = error.libraryPreparing()
            mutableState.update { it.copy(isRefreshing = false, pullRefreshing = false, webAccessOff = webAccessOff, libraryPreparing = preparing) }
            // Indexing is a state the library screen shows for as long as it lasts, not an error to repeat on every check.
            if (preparing == null) messages.show(error.toUserMessage(R.string.message_could_not_refresh_the_library))
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
        mutableState.update { it.copy(lastRefreshedAt = null, libraryPreparing = null) }
        if (!refresh) return
        launchRefresh(replaceActive = true)
        wakeMaintenance(WakeReason.FOREGROUND, "foreground")
        transfers.schedulePendingInBackground()
    }

    /**
     * While Apple is still indexing the library, checks again every [PREPARING_RECHECK_MILLIS] until it is ready, so the
     * library loads by itself. The caller runs this only while Alba is on screen; background work checks less often.
     */
    suspend fun recheckWhilePreparing() {
        while (state.value.libraryPreparing != null) {
            delay(PREPARING_RECHECK_MILLIS)
            if (state.value.libraryPreparing != null) refresh()
        }
    }

    /** Cancels a running refresh and returns its job, so the caller can wait for it. */
    fun cancel(): Job? = refreshJob.also { it?.cancel(); refreshJob = null }

    fun markIdle() = mutableState.update { it.copy(isRefreshing = false, pullRefreshing = false) }

    private suspend fun wakeMaintenance(reason: WakeReason, label: String): Result<Unit> = runCatching {
        diagnostics.measure(DiagnosticOperation.MAINTENANCE_WAKE, mapOf(DiagnosticAttribute.REASON to label)) {
            maintenance.wake(reason)
        }
        Unit
    }.onFailure { if (it is CancellationException) throw it }
}

/** Owns the messages a library refresh shows. */
private object RefreshMessages

/** How often Alba, on screen, checks whether Apple finished indexing the library. */
internal const val PREPARING_RECHECK_MILLIS = 60_000L

/** The indexing this failure reports, looking through wrapping exceptions; null for any other failure. */
private fun Throwable.libraryPreparing(): LibraryPreparing? {
    if (appleProtocolError() != AppleProtocolError.LIBRARY_PREPARING) return null
    val progress = generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).filterIsInstance<LibraryPreparingException>().firstOrNull()?.progress
    return LibraryPreparing(progress)
}

private const val MAX_CAUSE_DEPTH = 8
