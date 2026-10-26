package me.lvca.alba.app

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import me.lvca.alba.app.account.AccountController
import me.lvca.alba.app.account.PhoneStorageController
import me.lvca.alba.app.actions.MediaAction
import me.lvca.alba.app.actions.MediaActionRunner
import me.lvca.alba.app.actions.MediaWork
import me.lvca.alba.app.demo.DemoTransferMode
import me.lvca.alba.app.gallery.GalleryBrowser
import me.lvca.alba.app.gallery.LibraryEditsController
import me.lvca.alba.app.gallery.LibrarySync
import me.lvca.alba.app.gallery.PreviewController
import me.lvca.alba.app.messages.UiMessage
import me.lvca.alba.app.messages.UndoAction
import me.lvca.alba.app.messages.UserMessages
import me.lvca.alba.app.platform.PhotoEditStore
import me.lvca.alba.app.sharing.SharedAlbumsController
import me.lvca.alba.app.transfers.ExportController
import me.lvca.alba.app.transfers.LiveTransferMode
import me.lvca.alba.app.transfers.TransferController
import me.lvca.alba.engine.model.MediaOrigin

/**
 * Owns the scope and saved state for Alba's state holders, wires them together, and exposes each one with its own
 * state flow. Behaviour lives in the holders:
 * - [gallery]: the query, its results, the selection and the open photo
 * - [previews]: thumbnail downloads and their retry signal
 * - [sync]: restoring the saved account and refreshing the library
 * - [account]: sign-in, two-factor, sign-out and connection checks
 * - [transfers]: batches, uploads, automatic backup and the verified trash handoff
 * - [exports]: sharing, saving to the phone gallery and exporting originals
 * - [edits]: favorites, albums and iCloud's Recently Deleted
 * - [sharedAlbums]: shared album reads, invitations and changes
 * - [phoneStorage]: Alba's own storage on the phone
 */
class AlbaViewModel(
    dependencies: AlbaDependencies,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val messages = UserMessages()
    private val effectQueue = EffectQueue()
    private val network = dependencies.network.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val runner = MediaActionRunner(viewModelScope, dependencies.diagnostics, messages)
    private val diagnostics = dependencies.diagnostics

    internal val previews: PreviewController = PreviewController(
        scope = viewModelScope,
        repository = dependencies.repository,
        diagnostics = dependencies.diagnostics,
        messages = messages,
        network = network,
        selectedMediaId = { gallery.selectedMediaId.value },
        needsPreview = { id ->
            gallery.current.catalogItems.any { it.id == id && it.origin == MediaOrigin.ICLOUD && it.previewReference == null }
        },
    )

    internal val gallery: GalleryBrowser = GalleryBrowser(
        scope = viewModelScope,
        savedState = savedState,
        repository = dependencies.repository,
        accountManager = dependencies.accountManager,
        diagnostics = dependencies.diagnostics,
        requestPreview = previews::request,
    )

    internal val transfers: TransferController = TransferController(
        scope = viewModelScope,
        savedState = savedState,
        accountManager = dependencies.accountManager,
        photos = dependencies.photos,
        repository = dependencies.repository,
        batches = dependencies.batches,
        scheduler = dependencies.transferScheduler,
        diagnostics = dependencies.diagnostics,
        messages = messages,
        runner = runner,
        effects = effectQueue,
        gallery = gallery,
        live = LiveTransferMode(dependencies.photos, dependencies.batches, dependencies.transferScheduler, messages),
        demo = DemoTransferMode(viewModelScope, dependencies.accountManager, dependencies.repository,
            dependencies.demoImporter, messages) { id -> gallery.current.catalogItems.firstOrNull { it.id == id } },
    )

    internal val sync: LibrarySync = LibrarySync(
        scope = viewModelScope,
        repository = dependencies.repository,
        accountManager = dependencies.accountManager,
        maintenance = dependencies.maintenance,
        diagnostics = dependencies.diagnostics,
        messages = messages,
        previews = previews,
        hasDeviceAccess = { gallery.hasDeviceAccess },
        transfers = transfers,
    )

    internal val exports: ExportController = ExportController(dependencies.repository, dependencies.exporter, runner, messages, effectQueue)

    internal val edits: LibraryEditsController = LibraryEditsController(dependencies.repository, runner, messages, gallery)

    internal val sharedAlbums: SharedAlbumsController = SharedAlbumsController(savedState, dependencies.repository, dependencies.accountManager,
        dependencies.diagnostics, runner, messages, gallery)

    internal val phoneStorage: PhoneStorageController = PhoneStorageController(viewModelScope, dependencies.repository, transfers, previews, runner, messages)

    internal val account: AccountController = AccountController(
        scope = viewModelScope,
        savedState = savedState,
        accountManager = dependencies.accountManager,
        photos = dependencies.photos,
        repository = dependencies.repository,
        diagnostics = dependencies.diagnostics,
        messages = messages,
        sync = sync,
        network = network,
        cancelCloudWork = ::cancelCloudWork,
        onAccountChanged = { refresh ->
            exports.clearShares()
            gallery.closeMedia()
            sync.accountChanged(refresh)
        },
    )

    /** The transient message to show, until [dismissMessage] or a newer message replaces it. */
    val message: StateFlow<UiMessage?> = messages.message

    /**
     * One-shot platform requests (share sheet, trash consent, permissions) for the UI to perform once each. The oldest
     * request is delivered again to every new collector until the UI reports it through [effectHandled].
     */
    val effects: Flow<PendingEffect> = effectQueue.next

    val mediaWork: StateFlow<MediaWork> = runner.running
        .map { running ->
            MediaWork(busy = running.isNotEmpty(), sharing = running.any { it.action == MediaAction.SHARE },
                preparingOriginal = running.any { it.action == MediaAction.OPEN_ELSEWHERE })
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MediaWork())

    val diagnosticsConsent: StateFlow<DiagnosticsConsent> = diagnostics.enabled
        .map { DiagnosticsConsent(diagnostics.available, it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DiagnosticsConsent(diagnostics.available, diagnostics.enabled.value))

    /** Writes edited photos as new phone copies. */
    val photoEdits: PhotoEditStore = dependencies.photoEdits

    init {
        sync.start()
    }

    fun dismissMessage() = messages.clear()

    /** The UI performed [effect], so it is not delivered again. */
    fun effectHandled(effect: PendingEffect) {
        effectQueue.handled(effect)
        if (effect.effect is AlbaEffect.RequestBackupPermissions) transfers.backupPermissionsLaunched()
    }

    fun localOperationFailed() = messages.show(R.string.local_action_failed)

    fun setDiagnosticsEnabled(enabled: Boolean) = diagnostics.setEnabled(enabled)

    /** Records phone photo access, refreshing the library when access itself changed. */
    fun setDeviceMediaAccess(granted: Boolean, limited: Boolean) {
        if (!gallery.setDeviceMediaAccess(granted, limited)) return
        sync.refresh()
        // A refresh already running started without phone access and refresh() leaves it be, so scan the phone once it
        // ends; otherwise photos taken before the grant stay missing until the next change.
        if (granted) viewModelScope.launch { sync.deviceLibraryChanged() }
    }

    private val welcome = MutableStateFlow(false)

    /** A saved account was signed out: the root shows the introduction once, then reports it through [welcomeShown]. */
    val returnToWelcome: StateFlow<Boolean> = welcome.asStateFlow()

    fun signOut() = account.signOut(onSavedAccountRemoved = { welcome.value = true })

    fun welcomeShown() {
        welcome.value = false
    }

    /** Phone items Alba last asked Android to move to its trash, kept for Undo; null for a restore. In memory only. */
    private var pendingPhoneTrash: List<String>? = null

    /** iCloud items to move to Recently Deleted once Android answers the phone part of a mixed trash. In memory only. */
    private var pendingCloudTrash: List<String> = emptyList()

    /** The user confirmed trashing phone and iCloud items together; the iCloud part follows Android's answer. */
    fun mixedTrashRequested(cloudIds: List<String>) {
        pendingCloudTrash = cloudIds
    }

    /** Asking Android to trash phone items failed before it could answer. */
    fun phoneTrashFailed() {
        pendingPhoneTrash = null
        pendingCloudTrash = emptyList()
        localOperationFailed()
    }

    /** Alba is about to ask Android to move [contentUris] to its trash, or to [restore] them from it. */
    fun phoneTrashRequested(contentUris: List<String>, restore: Boolean) {
        pendingPhoneTrash = contentUris.takeUnless { restore }
    }

    /**
     * Android answered the last trash or restore request. When it moved or restored the items, leave the selection
     * and viewer and show the result; a move to the trash can be undone, like Google Photos' "Moved to trash".
     */
    fun phoneTrashAnswered(approved: Boolean) {
        val trashed = pendingPhoneTrash
        val cloud = pendingCloudTrash
        pendingPhoneTrash = null
        pendingCloudTrash = emptyList()
        if (cloud.isNotEmpty()) {
            // The user confirmed the whole move in Alba; Android's prompt covers only the phone items, so the iCloud
            // items go even when it is declined. One message with one Undo covers both.
            if (approved) sync.refresh()
            edits.setCloudTrashed(cloud, trashed = true, alsoOnPhone = trashed.takeIf { approved }.orEmpty())
            return
        }
        if (!approved) return
        gallery.clearSelection()
        gallery.closeMedia()
        sync.refresh()
        if (!trashed.isNullOrEmpty()) {
            messages.show(UiMessage.Undoable(UiMessage.quantity(R.plurals.phone_trash_moved, trashed.size), UndoAction.PhoneTrash(trashed)))
        }
    }

    /** Stops everything that belongs to the outgoing account and waits for it to end. */
    private suspend fun cancelCloudWork() {
        transfers.cancelActiveBatches()
        val jobs = buildList<Job> {
            sync.cancel()?.let(::add)
            addAll(previews.cancel())
            addAll(runner.cancelAll())
            account.cancelConnectionCheck()?.let(::add)
        }.distinct()
        jobs.joinAll()
        sync.markIdle()
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            check(modelClass.isAssignableFrom(AlbaViewModel::class.java))
            val graph = (appContext as AlbaApplication).graph
            return AlbaViewModel(AlbaDependencies.from(graph), extras.createSavedStateHandle()) as T
        }
    }
}
