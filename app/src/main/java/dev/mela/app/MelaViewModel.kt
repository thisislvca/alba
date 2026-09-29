package dev.mela.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import dev.mela.engine.model.GallerySort
import java.time.LocalDate
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.mela.engine.model.AccountChangeResult
import dev.mela.engine.model.BackupChange
import dev.mela.engine.model.BackupDraft
import dev.mela.engine.model.BackupDraftId
import dev.mela.engine.model.BackupScope
import dev.mela.engine.model.BackupSetupEffect
import dev.mela.engine.model.BackupView
import dev.mela.engine.model.GalleryMedia
import dev.mela.engine.model.GalleryRepository
import dev.mela.engine.model.MediaOrigin
import dev.mela.engine.model.MaintenanceWakeup
import dev.mela.engine.model.PhotoCompanion
import dev.mela.engine.model.SystemConsentCallback
import dev.mela.engine.model.TrashAttemptId
import dev.mela.engine.model.TrashHandoff
import dev.mela.engine.model.TransferId
import dev.mela.engine.model.TransferView
import dev.mela.engine.model.WakeReason
import dev.mela.protocol.account.AppleSignInResult
import dev.mela.protocol.account.ICloudAccountManager
import dev.mela.protocol.account.ICloudAccountState
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import dev.mela.engine.model.GalleryQuery
import dev.mela.engine.model.GalleryCollection
import dev.mela.engine.companion.GalleryBatchActions
import dev.mela.engine.companion.BatchAction
import dev.mela.engine.database.BatchItemEntity
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay

enum class GalleryFilter(val label: String) {
    ALL("All"),
    ICLOUD("iCloud"),
    DEVICE("This phone"),
}

data class AccountInfo(
    val profile: dev.mela.engine.model.CloudAccountProfile? = null,
    val storage: dev.mela.engine.model.CloudStorageUsage? = null,
    val checking: Boolean = false,
    val error: UiMessage? = null,
    val photosAvailable: Boolean? = null,
)

data class GalleryUiState(
    val phoneStorage: dev.mela.engine.model.LocalMediaStorage? = null,
    val phoneStorageChecking: Boolean = false,
    val phoneStorageError: UiMessage? = null,
    val accountInfo: AccountInfo = AccountInfo(),
    val network: NetworkStatus? = null,
    val accountBusy: Boolean = false,
    val previewRetryVersion: Int = 0,
    val limitedPhotoAccess: Boolean = false,
    val query: GalleryQuery = GalleryQuery(),
    val selection: Set<String> = emptySet(),
    val collections: List<GalleryCollection> = emptyList(),
    val batches: List<BatchItemEntity> = emptyList(),
    val items: List<GalleryMedia> = emptyList(),
    val catalogItems: List<GalleryMedia> = items,
    val sections: List<GallerySection> = buildGallerySections(items),
    val selectedMedia: GalleryMedia? = null,
    val selectedFilter: GalleryFilter = GalleryFilter.ALL,
    val isRefreshing: Boolean = true,
    val activeDownloadId: String? = null,
    val deviceMediaAccess: Boolean = false,
    val lastRefreshedAt: Instant? = null,
    val message: UiMessage? = null,
    val cloudCount: Int = 0,
    val deviceCount: Int = 0,
    val accountState: ICloudAccountState = ICloudAccountState.Restoring,
    val isAccountOpen: Boolean = false,
    val cloudSourceLabel: String = "Demo iCloud library",
    val uploadTransfersByMediaId: Map<String, TransferView> = emptyMap(),
    val transferQueue: List<TransferView> = emptyList(),
    val backup: BackupView = BackupView(enabled = false),
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MelaViewModel(
    private val repository: GalleryRepository,
    private val accountManager: ICloudAccountManager,
    private val photos: PhotoCompanion,
    private val maintenance: MaintenanceWakeup,
    private val batchActions: GalleryBatchActions? = null,
    private val scheduleBatches: suspend () -> Unit = {},
    private val savedState: SavedStateHandle,
    private val exporter: GalleryExporter? = null,
    private val network: kotlinx.coroutines.flow.Flow<NetworkStatus?> = flowOf(null),
    private val diagnostics: MelaDiagnostics = MelaDiagnostics.None,
) : ViewModel() {
    private val connection = network.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val query = MutableStateFlow(GalleryQuery(
        origin = savedState.get<String>("queryOrigin")?.let(MediaOrigin::valueOf)
            ?: when (savedState.get<String>("filter")) { "ICLOUD" -> MediaOrigin.ICLOUD; "DEVICE" -> MediaOrigin.DEVICE; else -> null },
        filename = savedState["filename"] ?: "", collectionId = savedState["collection"],
        fromDate = savedState.get<String>("fromDate")?.let(LocalDate::parse),
        throughDate = savedState.get<String>("throughDate")?.let(LocalDate::parse),
        sort = savedState.get<String>("sort")?.let(GallerySort::valueOf) ?: GallerySort.NEWEST,
        offlineOnly = savedState["offlineOnly"] ?: false,
        trashOnly = savedState["trashOnly"] ?: false,
        date = savedState.get<String>("dateBasis")?.let(dev.mela.engine.model.GalleryDate::valueOf) ?: dev.mela.engine.model.GalleryDate.CAPTURED,
    ))
    private val selection = MutableStateFlow(savedState.get<ArrayList<String>>("selection")?.toSet() ?: emptySet())
    private fun updateSelection(ids: Set<String>) {
        selection.value = ids
        // Rotation keeps this ViewModel. Bound process-death state so selecting an entire
        // large library cannot exceed Android's shared saved-state transaction limit.
        savedState["selection"] = ArrayList(if (ids.sumOf { it.length.toLong() * 2L + 32L } <= 64 * 1024L) ids else emptySet())
    }
    fun setQuery(value: GalleryQuery) {
        savedState["queryOrigin"] = value.origin?.name; savedState["filter"] = GalleryFilter.ALL.name
        savedState["filename"] = value.filename; savedState["collection"] = value.collectionId
        savedState["fromDate"] = value.fromDate?.toString(); savedState["throughDate"] = value.throughDate?.toString()
        savedState["dateBasis"] = value.date.name
        savedState["trashOnly"] = value.trashOnly
        savedState["sort"] = value.sort.name; savedState["offlineOnly"] = value.offlineOnly
        query.value = value; updateSelection(emptySet())
    }
    fun selectItems(ids: Set<String>, selected: Boolean) = updateSelection(if (selected) selection.value + ids else selection.value - ids)
    suspend fun loadViewer(id: String) {
        if (uiState.value.catalogItems.any { it.id == id && it.origin == MediaOrigin.ICLOUD }) {
            measured(DiagnosticOperation.VIEWER_DOWNLOAD) { repository.ensureViewer(id) }
        }
    }
    fun toggleSelection(id: String) { updateSelection(selection.value.let { if (id in it) it - id else it + id }) }
    fun selectAllShown() { updateSelection(uiState.value.items.map { it.id }.toSet()) }
    fun clearSelection() { updateSelection(emptySet()) }
    suspend fun openPlayback(id: String, position: Long, length: Long) =
        measured(DiagnosticOperation.PLAYBACK_OPEN) { repository.openPlayback(id, position, length) }
    fun runBatch(action: BatchAction) {
        val ids = uiState.value.items.filter { item ->
            item.id in selection.value && when (action) {
                BatchAction.KEEP_OFFLINE, BatchAction.REMOVE_CACHE, BatchAction.SAVE_TO_PHONE -> item.origin == MediaOrigin.ICLOUD
                BatchAction.UPLOAD -> item.origin == MediaOrigin.DEVICE
                BatchAction.PICKER_UPLOAD -> false
            }
        }.map { it.id }
        if (ids.isEmpty()) return
        updateSelection(emptySet())
        launchBatchWork {
            batchActions?.enqueue(action, ids)
            if (action in setOf(BatchAction.KEEP_OFFLINE, BatchAction.SAVE_TO_PHONE))
                status.value = status.value.copy(message = UiMessage(R.string.download_batch_started))
            if (action == BatchAction.REMOVE_CACHE) drainLocalBatches() else scheduleBatches()
        }
    }
    fun retryBatch(id: String) { launchBatchWork {
        batchActions?.retry(id)
        drainLocalBatches()
        if (batchActions?.hasPending() == true) scheduleBatches()
    } }
    fun cancelDownloadBatch(id: String) = launchBatchWork { batchActions?.cancelDownload(id) }

    private suspend fun drainLocalBatches() {
        while (batchActions?.runPending(localOnly = true) == true) kotlinx.coroutines.yield()
    }

    private var phoneStorageJob: Job? = null
    fun checkPhoneStorage() {
        if (phoneStorageJob?.isActive == true) return
        phoneStorageJob = viewModelScope.launch {
            status.value = status.value.copy(phoneStorageChecking = true, phoneStorageError = null)
            try {
                val usage = repository.localStorageUsage()
                if (previewStorageBlocked && usage.availableBytes > dev.mela.engine.cache.StorageWriteGuard.RESERVE_BYTES) {
                    previewStorageBlocked = false
                    status.value = status.value.copy(previewRetryVersion = status.value.previewRetryVersion + 1)
                }
                status.value = status.value.copy(phoneStorage = usage)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status.value = status.value.copy(phoneStorageError = UiMessage(R.string.phone_storage_failed)) }
            finally { status.value = status.value.copy(phoneStorageChecking = false) }
        }
    }
    fun clearLocalMedia(category: dev.mela.engine.model.LocalMediaCategory) = runMediaAction("local-storage", R.string.phone_storage_failed) {
        phoneStorageJob?.cancel(); phoneStorageJob?.join()
        val previews = previewJobs.values.toList()
        previews.forEach { it.cancel() }; previews.joinAll()
        if (category == dev.mela.engine.model.LocalMediaCategory.ORIGINALS) batchActions?.cancelOfflineDownloads()
        repository.clearLocalMedia(category)
        previewStorageBlocked = false
        status.value = status.value.copy(phoneStorage = repository.localStorageUsage(), phoneStorageError = null,
            message = UiMessage(R.string.phone_storage_cleared))
    }
    fun uploadSelected(uris: List<Uri>) { launchBatchWork {
        batchActions?.enqueue(BatchAction.PICKER_UPLOAD, uris.map(Uri::toString))
        scheduleBatches()
    } }
    private fun launchBatchWork(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                measured(DiagnosticOperation.BATCH_ACTION) {
                    kotlinx.coroutines.withContext(Dispatchers.IO) { action() }
                }
            }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                status.value = status.value.copy(message = error.toUserMessage(R.string.message_could_not_finish_pending_photo_work))
            }
        }
    }
    private suspend fun schedulePendingTransfers() {
        if (backgroundSessionDecision(accountManager.state.value, accountManager.session.value != null) == BackgroundSessionDecision.RUN &&
            (batchActions?.hasPending() == true || photos.hasPendingUserUploads())) scheduleBatches()
    }
    private val accountInfo = MutableStateFlow(AccountInfo())
    private var accountInfoJob: Job? = null
    fun checkConnection() {
        if (accountInfoJob?.isActive == true || status.value.accountBusy) return
        accountInfoJob = viewModelScope.launch {
            val span = diagnostics.start(
                DiagnosticOperation.ICLOUD_STORAGE,
                mapOf(DiagnosticAttribute.ACTION to "connection_check"),
            )
            val identity = accountManager.session.value?.dsid
            val previousStatus = (accountManager.state.value as? ICloudAccountState.SignedIn)?.status
            accountInfo.value = accountInfo.value.copy(checking = true, error = null)
            try {
                if (accountManager.state.value is ICloudAccountState.SignedIn) accountManager.restore(force = true)
                if (identity != accountManager.session.value?.dsid) {
                    span.finish(DiagnosticOutcome.CANCELLED)
                    return@launch
                }
                val session = accountManager.session.value
                val currentStatus = (accountManager.state.value as? ICloudAccountState.SignedIn)?.status
                if (currentStatus == dev.mela.protocol.account.SessionStatus.PHOTOS_NOT_ENABLED) {
                    accountInfo.value = AccountInfo(photosAvailable = false)
                    span.finish(DiagnosticOutcome.SUCCESS)
                    return@launch
                }
                if (session != null && accountManager.authorizedSession == null) error("Reconnect to iCloud to check storage. Saved copies remain available.")
                val storage = kotlinx.coroutines.withContext(Dispatchers.IO) { repository.storageUsage() }
                if (identity != accountManager.session.value?.dsid) return@launch
                accountInfo.value = accountInfo.value.copy(storage = storage,
                    photosAvailable = session?.webservices?.containsKey("ckdatabasews"))
                val profile = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    try { repository.accountProfile() }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        null
                    }
                }
                if (identity != accountManager.session.value?.dsid) return@launch
                accountInfo.value = accountInfo.value.copy(profile = profile)
                if (previousStatus == dev.mela.protocol.account.SessionStatus.PHOTOS_NOT_ENABLED &&
                    currentStatus == dev.mela.protocol.account.SessionStatus.VERIFIED) {
                    launchRefresh(replaceActive = true)
                }
                span.finish(DiagnosticOutcome.SUCCESS)
            } catch (error: Exception) {
                if (error is CancellationException) {
                    span.finish(DiagnosticOutcome.CANCELLED)
                    throw error
                }
                span.finish(DiagnosticOutcome.FAILED, error = error)
                accountInfo.value = accountInfo.value.copy(error = error.toUserMessage(R.string.message_could_not_check_icloud))
            } finally { accountInfo.value = accountInfo.value.copy(checking = false) }
        }
    }
    fun setFavorite(id: String, favorite: Boolean) = runMediaAction(id, R.string.message_could_not_confirm_the_favorite_change_refresh_before_retrying) {
        repository.setFavorite(id, favorite)
    }
    fun setFavorites(ids: List<String>, favorite: Boolean) = runMediaAction("favorites", R.string.favorites_failed) {
        require(ids.distinct().size in 1..50)
        val completed = mutableSetOf<String>()
        try {
            for (id in ids.distinct()) { repository.setFavorite(id, favorite); completed += id }
            updateSelection(selection.value - completed)
            status.value = status.value.copy(message = UiMessage.quantity(R.plurals.favorites_updated, completed.size))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            updateSelection(selection.value - completed)
            status.value = status.value.copy(message = UiMessage(R.string.favorites_partial, listOf(completed.size, ids.distinct().size)))
        }
    }
    fun removeFromAlbum(id: String, ids: List<String>) = runMediaAction("album", R.string.album_removal_failed) {
        repository.removeFromAlbum(id, ids)
        clearSelection(); closeDetail()
        status.value = status.value.copy(message = UiMessage(R.string.album_photos_removed))
    }
    fun setCloudTrashed(ids: List<String>, trashed: Boolean) = runMediaAction("cloud-trash", R.string.cloud_trash_failed) {
        repository.setCloudTrashed(ids, trashed)
        clearSelection(); closeDetail()
        status.value = status.value.copy(message = UiMessage(if (trashed) R.string.cloud_trashed_success else R.string.cloud_restored_success))
    }
    suspend fun sharedActivity(id: String, rank: Int) = kotlinx.coroutines.withContext(Dispatchers.IO) { repository.sharedActivity(id, rank) }
    suspend fun sharedPostDiscussion(id: String) = kotlinx.coroutines.withContext(Dispatchers.IO) { repository.sharedPostDiscussion(id) }
    suspend fun sharedPostPhotos(id: String) = kotlinx.coroutines.withContext(Dispatchers.IO) { repository.sharedPostPhotos(id) }
    suspend fun pendingSharedInvitations() = kotlinx.coroutines.withContext(Dispatchers.IO) { repository.pendingSharedInvitations() }
    suspend fun resolveSharedInvitation(url: String) = kotlinx.coroutines.withContext(Dispatchers.IO) { repository.resolveSharedInvitation(url) }
    suspend fun respondSharedInvitation(id: String, accept: Boolean) {
        measured(DiagnosticOperation.SHARED_ALBUM) { kotlinx.coroutines.withContext(Dispatchers.IO) {
            repository.respondSharedInvitation(id, accept, java.util.UUID.randomUUID().toString())
            repository.refresh(includeDeviceMedia = false)
        } }
    }
    suspend fun sharedManagement(id: String) = kotlinx.coroutines.withContext(Dispatchers.IO) { repository.sharedManagement(id) }
    suspend fun sharedDiscussion(id: String) = kotlinx.coroutines.withContext(Dispatchers.IO) { repository.sharedDiscussion(id) }
    suspend fun changeSharedAlbum(id: String, command: dev.mela.engine.model.SharedCommand) {
        val key = "shared-action:" + id + ":" + command.hashCode()
        val operation = savedState.get<String>(key) ?: java.util.UUID.randomUUID().toString().also { savedState[key] = it }
        measured(DiagnosticOperation.SHARED_ALBUM) { kotlinx.coroutines.withContext(Dispatchers.IO) {
            repository.changeSharedAlbum(id, command, operation)
            repository.refresh(includeDeviceMedia = false)
        } }
        savedState.remove<String>(key)
        if (command.action in setOf(dev.mela.engine.model.SharedAction.REMOVE_MEDIA, dev.mela.engine.model.SharedAction.DELETE_ALBUM, dev.mela.engine.model.SharedAction.LEAVE)) closeDetail()
        if (command.action in setOf(dev.mela.engine.model.SharedAction.DELETE_ALBUM, dev.mela.engine.model.SharedAction.LEAVE)) setQuery(GalleryQuery())
    }
    fun contributeToSharedAlbum(id: String, ids: List<String>) = runMediaAction("shared-album", R.string.shared_contribute_failed) {
        val key = "shared-contribute:" + id + ":" + ids.sorted().hashCode()
        val operation = savedState.get<String>(key) ?: java.util.UUID.randomUUID().toString().also { savedState[key] = it }
        repository.contributeToSharedAlbum(id, ids, operation)
        savedState.remove<String>(key)
        repository.refresh(includeDeviceMedia = false)
        clearSelection()
        status.value = status.value.copy(message = UiMessage(R.string.shared_contributed))
    }
    fun createSharedAlbum(name: String, generation: dev.mela.engine.model.SharedAlbumGeneration) = runMediaAction("shared-album", R.string.shared_create_failed) {
        val key = "shared-create:" + generation.name + ":" + (accountManager.state.value as? ICloudAccountState.SignedIn)?.appleId.orEmpty() + ":" + name.trim()
        val operationId = savedState.get<String>(key) ?: java.util.UUID.randomUUID().toString().also { savedState[key] = it }
        val album = repository.createSharedAlbum(name, operationId, generation)
        savedState.remove<String>(key)
        setQuery(query.value.copy(collectionId = album.id))
        status.value = status.value.copy(message = UiMessage(R.string.album_created, listOf(album.name)))
    }
    fun createAlbum(name: String) = runMediaAction("album", R.string.message_could_not_confirm_the_new_album_refresh_before_retrying) {
        val album = repository.createAlbum(name)
        setQuery(query.value.copy(collectionId = album.id))
        status.value = status.value.copy(message = UiMessage(R.string.album_created, listOf(album.name)))
    }
    fun createAlbumWithPhotos(name: String, mediaIds: List<String>) = runMediaAction("album", R.string.message_could_not_confirm_the_new_album_refresh_before_retrying) {
        val ids = mediaIds.distinct()
        require(ids.size in 1..50)
        val album = repository.createAlbum(name)
        try {
            repository.addToAlbum(album.id, ids)
            clearSelection()
            status.value = status.value.copy(message = UiMessage.Quantity(R.plurals.album_created_with_photos, ids.size, listOf(album.name, ids.size)))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            // The album creation succeeded. Never blindly repeat either remote write.
            status.value = status.value.copy(message = UiMessage(R.string.album_created_membership_uncertain, listOf(album.name)))
        }
    }
    fun deleteAlbum(id: String) = runMediaAction("album", R.string.message_could_not_confirm_album_deletion_refresh_before_retrying) {
        repository.deleteAlbum(id)
        if (query.value.collectionId == id) setQuery(query.value.copy(collectionId = null))
        status.value = status.value.copy(message = UiMessage(R.string.message_album_deleted_its_photos_remain_in_your_library))
    }
    fun renameAlbum(id: String, name: String) = runMediaAction("album", R.string.message_could_not_confirm_the_album_name_refresh_before_retrying) {
        repository.renameAlbum(id, name)
    }
    fun addToAlbum(id: String, mediaIds: List<String>) = runMediaAction("album", R.string.message_could_not_confirm_album_membership_refresh_before_retrying) {
        repository.addToAlbum(id, mediaIds)
        clearSelection()
        status.value = status.value.copy(message = UiMessage.quantity(R.plurals.added_to_album_count, mediaIds.size))
    }
    private val readyShare = MutableStateFlow<android.content.Intent?>(null)
    val shareIntent: StateFlow<android.content.Intent?> = readyShare
    fun cancelShare() { if (status.value.activeDownloadId == "share") mediaActionJob?.cancel() }
    fun consumeShare() { readyShare.value = null }
    fun share(items: List<GalleryMedia>) = runMediaAction("share", R.string.message_could_not_prepare_sharing) {
        readyShare.value = null
        val intent = requireNotNull(exporter).prepareShare(items)
        readyShare.value = intent
    }
    fun shareFailed() { status.value = status.value.copy(message = UiMessage(R.string.message_no_app_could_open_the_share_sheet)) }
    fun saveToGallery(media: GalleryMedia) = runMediaAction(media.id, R.string.message_could_not_save_to_the_gallery) {
        requireNotNull(exporter).saveToGallery(media)
        status.value = status.value.copy(message = UiMessage(if (media.kind == dev.mela.engine.model.MediaKind.LIVE_PHOTO)
            R.string.message_saved_the_photo_and_motion_clip_as_two_gallery_items else R.string.message_saved_to_your_phone_gallery))
    }
    fun exportDocument(id: String, uri: Uri, livePair: Boolean) = runMediaAction(id, R.string.message_could_not_export_this_photo) {
        val media = repository.findMedia(id)
            ?: error("The photo is no longer available.")
        requireNotNull(exporter).exportDocument(media, uri, livePair)
        status.value = status.value.copy(message = UiMessage(if (livePair) R.string.message_exported_the_original_photo_and_motion_clip else R.string.message_exported_the_original))
    }
    private val selectedFilter = MutableStateFlow(GalleryFilter.ALL)
    private val selectedMediaId = savedState.getStateFlow<String?>("media", null)
    private val deviceMediaAccess = MutableStateFlow(false)
    private val status = MutableStateFlow(ViewModelStatus(isAccountOpen = savedState["accountOpen"] ?: false))
    private val previewJobs = linkedMapOf<String, Job>()
    private val runningPreviews = mutableSetOf<String>()
    private var previewStorageBlocked = false
    private val previewPermits = Semaphore(MAX_CONCURRENT_PREVIEWS)
    private var refreshJob: Job? = null
    private var mediaActionJob: Job? = null

    private val queryProjection = dev.mela.engine.model.GalleryQueryProjection()
    private val sectionProjection = GallerySectionProjection()
    private val galleryContent = combine(
        combine(repository.observeGallery(), query) { items, query -> items to query },
        selectedFilter,
        selectedMediaId,
        deviceMediaAccess,
        accountManager.state,
    ) { (allItems, currentQuery), filter, selectedId, hasDeviceAccess, accountState ->
        projectGalleryContent(
            allItems = allItems,
            filter = filter,
            selectedId = selectedId,
            hasDeviceAccess = hasDeviceAccess,
            accountState = accountState,
            includeSections = false,
        ).let { content ->
            val matching = queryProjection.apply(content.items, currentQuery)
            val linked = if (currentQuery.origin == null && currentQuery.collectionId == null && hasDeviceAccess)
                matching.mapNotNull { it.linkedDeviceId }.toSet() else emptySet()
            val visible = matching.filterNot { it.id in linked }
            content.copy(items = visible, sections = sectionProjection.apply(visible, currentQuery.date))
        }
    }.flowOn(Dispatchers.Default)

    private val transferContent = photos.observeTransfers()
        .map(::projectTransfers)
        .flowOn(Dispatchers.Default)

    private val baseState = combine(
        galleryContent,
        transferContent,
        status,
        photos.observeBackup(),
    ) { content, transfers, currentStatus, backup ->
        GalleryUiState(
            phoneStorage = currentStatus.phoneStorage,
            phoneStorageChecking = currentStatus.phoneStorageChecking,
            phoneStorageError = currentStatus.phoneStorageError,
            accountBusy = currentStatus.accountBusy,
            previewRetryVersion = currentStatus.previewRetryVersion,
            limitedPhotoAccess = currentStatus.limitedPhotoAccess,
            items = content.items,
            catalogItems = content.catalogItems,
            sections = content.sections,
            selectedMedia = content.selectedMedia,
            selectedFilter = content.selectedFilter,
            isRefreshing = currentStatus.isRefreshing,
            activeDownloadId = currentStatus.activeDownloadId,
            deviceMediaAccess = content.deviceMediaAccess,
            lastRefreshedAt = currentStatus.lastRefreshedAt,
            message = currentStatus.message,
            cloudCount = content.cloudCount,
            deviceCount = content.deviceCount,
            accountState = content.accountState,
            isAccountOpen = currentStatus.isAccountOpen,
            cloudSourceLabel = content.cloudSourceLabel,
            uploadTransfersByMediaId = transfers.byMediaId,
            transferQueue = transfers.queue,
            backup = backup,
        )
    }
    private val browseState = combine(query, selection, repository.observeCollections(), batchActions?.observe() ?: flowOf(emptyList())) {
        q, selected, collections, batches -> GalleryUiState(query = q, selection = selected, collections = collections, batches = batches)
    }
    val uiState: StateFlow<GalleryUiState> = combine(baseState, browseState, accountInfo, connection) { base, browse, info, connection ->
        base.copy(accountInfo = info, network = connection, query = browse.query, selection = browse.selection, collections = browse.collections + base.catalogItems.filter { !it.isTrashed && it.deviceFolderId != null }
                .distinctBy { it.deviceFolderId }.map { GalleryCollection(it.deviceFolderId!!, it.deviceFolderName ?: "") },
            batches = browse.batches.filter { it.account == batchActions?.activeAccount })
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = GalleryUiState(),
    )

    init {
        viewModelScope.launch {
            var wasOffline = false
            connection.map { it?.online }.distinctUntilChanged().collectLatest { online ->
                if (online == false) wasOffline = true
                if (online == true && wasOffline) {
                    // Coalesce network handovers, then retire failed/in-flight reads before
                    // the existing visible-tile effects retry their bounded preview window.
                    delay(300)
                    val oldJobs = previewJobs.values.toList()
                    oldJobs.forEach { it.cancel() }
                    oldJobs.joinAll()
                    status.value = status.value.copy(previewRetryVersion = status.value.previewRetryVersion + 1)
                    wasOffline = false
                }
            }
        }
        viewModelScope.launch { accountManager.session.map { it?.let { session -> session.dsid } }.distinctUntilChanged()
            .collect { account ->
                if (account != null) {
                    val previous = savedState.get<String>("accountIdentity")
                    if (previous != null && previous != account) {
                        clearSelection(); closeDetail(); setQuery(GalleryQuery()); selectFilter(GalleryFilter.ALL)
                    }
                    savedState["accountIdentity"] = account
                }
            } }
        viewModelScope.launch {
            try { accountManager.restore() } catch (error: Exception) {
                if (error is CancellationException) throw error
                status.value = status.value.copy(isRefreshing = false, message = error.toUserMessage(R.string.message_could_not_restore_the_saved_account_refresh_to_retry))
                return@launch
            }
            runCatching {
                measured(
                    DiagnosticOperation.MAINTENANCE_WAKE,
                    mapOf(DiagnosticAttribute.REASON to "process_started"),
                ) { maintenance.wake(WakeReason.PROCESS_STARTED) }
            }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    status.value = status.value.copy(
                        message = error.toUserMessage(R.string.message_could_not_resume_pending_photo_work),
                    )
                }
            launchRefresh(replaceActive = true)
            launchBatchWork {
                drainLocalBatches()
                schedulePendingTransfers()
            }
        }
    }

    fun setDeviceMediaAccess(granted: Boolean, limited: Boolean = false) {
        status.value = status.value.copy(limitedPhotoAccess = limited)
        if (deviceMediaAccess.value == granted) return
        deviceMediaAccess.value = granted
        if (!granted && query.value.origin == MediaOrigin.DEVICE) {
            selectFilter(GalleryFilter.ALL)
        }
        refresh()
    }

    fun selectFilter(filter: GalleryFilter) {
        if (filter == GalleryFilter.DEVICE && !deviceMediaAccess.value) return
        setQuery(query.value.copy(origin = when (filter) {
            GalleryFilter.ALL -> null
            GalleryFilter.ICLOUD -> MediaOrigin.ICLOUD
            GalleryFilter.DEVICE -> MediaOrigin.DEVICE
        }))
    }

    fun selectMedia(mediaId: String) {
        savedState["media"] = mediaId
        requestPreview(mediaId)
    }

    fun closeDetail() {
        savedState["media"] = null
    }

    suspend fun deviceLibraryChanged() {
        if (!deviceMediaAccess.value) return
        try {
            measured(DiagnosticOperation.DEVICE_REFRESH) { repository.refreshDevice() }
            status.value = status.value.copy(previewRetryVersion = status.value.previewRetryVersion + 1)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { status.value = status.value.copy(message = UiMessage(R.string.device_refresh_failed)) }
    }

    fun refresh() {
        previewStorageBlocked = false
        viewModelScope.launch {
            if (accountManager.state.value == ICloudAccountState.Restoring && status.value.isRefreshing) return@launch
            try {
                val account = accountManager.state.value as? ICloudAccountState.SignedIn
                if (accountManager.state.value == ICloudAccountState.Restoring ||
                    (account != null && account.status != dev.mela.protocol.account.SessionStatus.VERIFIED)) accountManager.restore()
                if ((accountManager.state.value as? ICloudAccountState.SignedIn)?.status ==
                    dev.mela.protocol.account.SessionStatus.PHOTOS_NOT_ENABLED) return@launch
                launchRefresh(replaceActive = false)
                launchBatchWork { schedulePendingTransfers() }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                status.value = status.value.copy(message = error.toUserMessage(R.string.message_could_not_reconnect_to_icloud))
            }
        }
    }

    fun openAccount() {
        savedState["accountOpen"] = true
        status.value = status.value.copy(isAccountOpen = true)
        checkConnection()
    }

    fun closeAccount() {
        savedState["accountOpen"] = false
        status.value = status.value.copy(isAccountOpen = false)
    }

    private var signInPreviousDsid: String? = null

    fun signIn(appleId: String, password: String) {
        if (status.value.accountBusy) return
        signInPreviousDsid = accountManager.session.value?.dsid
        status.value = status.value.copy(accountBusy = true)
        viewModelScope.launch {
            val accountChange = photos.accountWillChange()
            if (accountChange is AccountChangeResult.Blocked) {
                status.value = status.value.copy(message = localizedStoredMessage(accountChange.reason), accountBusy = false)
                return@launch
            }
            cancelCloudWork()
            status.value = status.value.copy(message = null)
            try {
                val result = accountManager.signIn(appleId, password)
                if (result is AppleSignInResult.SignedIn) {
                    accountChanged(refresh = accountManager.authorizedSession != null)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                status.value = status.value.copy(
                    message = error.toUserMessage(R.string.message_could_not_sign_in_to_icloud),
                )
            } finally {
                status.value = status.value.copy(accountBusy = false)
            }
        }
    }

    fun submitTwoFactor(code: String) {
        if (status.value.accountBusy) return
        status.value = status.value.copy(accountBusy = true)
        viewModelScope.launch {
            cancelCloudWork()
            status.value = status.value.copy(message = null)
            try {
                accountManager.submitTwoFactor(code)
                accountChanged(refresh = accountManager.authorizedSession != null)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                status.value = status.value.copy(
                    message = error.toUserMessage(R.string.message_could_not_verify_that_code),
                )
            } finally {
                status.value = status.value.copy(accountBusy = false)
            }
        }
    }

    fun resendTwoFactor() {
        if (status.value.accountBusy) return
        status.value = status.value.copy(accountBusy = true)
        viewModelScope.launch {
            status.value = status.value.copy(message = null)
            try {
                accountManager.resendTwoFactor()
                status.value = status.value.copy(message = UiMessage(R.string.message_apple_sent_another_verification_code))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                status.value = status.value.copy(
                    message = error.toUserMessage(R.string.message_could_not_send_another_code),
                )
            } finally {
                status.value = status.value.copy(accountBusy = false)
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            val accountChange = photos.accountWillChange()
            if (accountChange is AccountChangeResult.Blocked) {
                status.value = status.value.copy(message = localizedStoredMessage(accountChange.reason))
                return@launch
            }
            cancelCloudWork()
            status.value = status.value.copy(message = null)
            try {
                accountManager.signOut()
                accountChanged()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                status.value = status.value.copy(
                    message = error.toUserMessage(R.string.message_could_not_clear_the_icloud_account),
                )
            }
        }
    }

    private suspend fun accountChanged(refresh: Boolean = true) {
        exporter?.clearShares()
        accountInfoJob?.cancel()
        accountInfo.value = AccountInfo(
            photosAvailable = if ((accountManager.state.value as? ICloudAccountState.SignedIn)?.status ==
                dev.mela.protocol.account.SessionStatus.PHOTOS_NOT_ENABLED) false else null,
        )
        if (accountManager.session.value == null || signInPreviousDsid != accountManager.session.value?.dsid) repository.clearCloudCatalog()
        savedState["media"] = null
        status.value = status.value.copy(lastRefreshedAt = null)
        if (!refresh) return
        launchRefresh(replaceActive = true)
        runCatching {
            measured(
                DiagnosticOperation.MAINTENANCE_WAKE,
                mapOf(DiagnosticAttribute.REASON to "foreground"),
            ) { maintenance.wake(WakeReason.FOREGROUND) }
        }
        launchBatchWork { schedulePendingTransfers() }
    }

    private suspend fun cancelCloudWork() {
        readyShare.value = null
        batchActions?.cancelActive()
        val jobs = buildList {
            refreshJob?.let(::add)
            addAll(previewJobs.values)
            mediaActionJob?.let(::add)
            accountInfoJob?.let(::add)
        }.distinct()
        jobs.forEach(Job::cancel)
        refreshJob = null
        previewJobs.clear()
        mediaActionJob = null
        jobs.joinAll()
        status.value = status.value.copy(activeDownloadId = null, isRefreshing = false)
    }

    private fun launchRefresh(replaceActive: Boolean) {
        if (refreshJob?.isActive == true) {
            if (!replaceActive) return
            refreshJob?.cancel()
        }
        refreshJob = viewModelScope.launch {
            status.value = status.value.copy(isRefreshing = true, message = null)
            val span = diagnostics.start(DiagnosticOperation.GALLERY_REFRESH)
            try {
                val summary = repository.refresh(includeDeviceMedia = deviceMediaAccess.value)
                span.finish(
                    DiagnosticOutcome.SUCCESS,
                    mapOf(
                        DiagnosticAttribute.CLOUD_COUNT to summary.cloudItemCount,
                        DiagnosticAttribute.DEVICE_COUNT to summary.deviceItemCount,
                        DiagnosticAttribute.SHARED_UNAVAILABLE to summary.sharedAlbumsUnavailable,
                    ),
                )
                status.value = status.value.copy(
                    isRefreshing = false,
                    lastRefreshedAt = Instant.ofEpochMilli(summary.refreshedAtEpochMillis),
                    message = if (summary.sharedAlbumsUnavailable) UiMessage(R.string.shared_refresh_unavailable) else null,
                    previewRetryVersion = status.value.previewRetryVersion + 1,
                )
            } catch (cancelled: CancellationException) {
                span.finish(DiagnosticOutcome.CANCELLED)
                throw cancelled
            } catch (error: Throwable) {
                span.finish(DiagnosticOutcome.FAILED, error = error)
                status.value = status.value.copy(
                    isRefreshing = false,
                    message = error.toUserMessage(R.string.message_could_not_refresh_the_library),
                )
            }
        }
    }

    fun requestPreview(mediaId: String) {
        if (previewStorageBlocked) return
        if (uiState.value.catalogItems.none { it.id == mediaId && it.origin == MediaOrigin.ICLOUD && it.previewReference == null }) return
        queuePreview(mediaId)
    }

    fun prefetchPreviews(mediaIds: List<String>) {
        val candidates = mediaIds.distinct().take(MAX_PENDING_PREVIEWS)
        val finishing = previewJobs.keys.count { id ->
            id !in candidates && (id in runningPreviews || id == selectedMediaId.value)
        }
        // Reserve room for retained in-flight work, trimming look-ahead from the end
        // instead of dropping the first (currently visible) photos in a full window.
        val wanted = candidates.take((MAX_PENDING_PREVIEWS - finishing).coerceAtLeast(0)).toSet()
        // Replace obsolete queued work after a fling. Let in-flight bytes finish so
        // briefly browsing past a photo still leaves a useful thumbnail on disk.
        previewJobs.entries.toList().forEach { (id, job) ->
            if (id !in wanted && id != selectedMediaId.value && id !in runningPreviews) {
                previewJobs.remove(id)
                job.cancel()
            }
        }
        wanted.forEach(::requestPreview)
    }

    private fun queuePreview(mediaId: String) {
        if (previewJobs[mediaId]?.isActive == true) return
        if (previewJobs.size >= MAX_PENDING_PREVIEWS) {
            val oldestBackground = previewJobs.entries.lastOrNull { it.key != selectedMediaId.value && it.key !in runningPreviews }
                ?: return
            previewJobs.remove(oldestBackground.key)
            oldestBackground.value.cancel()
        }

        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val span = diagnostics.start(DiagnosticOperation.PREVIEW_DOWNLOAD)
            try {
                previewPermits.withPermit {
                    if (previewStorageBlocked) return@withPermit
                    runningPreviews += mediaId
                    try { repository.ensurePreview(mediaId) }
                    finally { runningPreviews -= mediaId }
                }
                span.finish(DiagnosticOutcome.SUCCESS)
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    span.finish(DiagnosticOutcome.CANCELLED)
                    throw error
                }
                span.finish(DiagnosticOutcome.FAILED, error = error)
                if (error is dev.mela.engine.cache.InsufficientStorageException) previewStorageBlocked = true
                if (selectedMediaId.value == mediaId || previewStorageBlocked) {
                    status.value = status.value.copy(
                        message = error.toUserMessage(R.string.message_could_not_load_this_preview),
                    )
                }
            } finally {
                if (previewJobs[mediaId] === job) previewJobs.remove(mediaId)
            }
        }
        previewJobs[mediaId] = job
        job.start()
    }

    fun keepOriginalOffline(mediaId: String) {
        launchBatchWork {
            batchActions?.enqueue(BatchAction.KEEP_OFFLINE, listOf(mediaId))
            scheduleBatches()
        }
    }
    fun cancelOfflineDownload() {
        val id = selectedMediaId.value ?: return
        launchBatchWork {
            uiState.value.batches.filter { it.mediaId == id && it.action == BatchAction.KEEP_OFFLINE.name && it.state in setOf("WAITING", "RUNNING") }
                .map { it.batchId }.distinct().forEach { batchActions?.cancelDownload(it) }
        }
    }

    fun removeCachedOriginal(mediaId: String) {
        runMediaAction(mediaId, R.string.message_could_not_remove_the_offline_copy) {
            repository.removeCachedOriginal(mediaId)
        }
    }

    fun requestUpload(mediaId: String) {
        launchBatchWork {
            batchActions?.enqueue(BatchAction.UPLOAD, listOf(mediaId))
            scheduleBatches()
        }
    }

    fun uploadSelected(uri: Uri) = uploadSelected(listOf(uri))

    fun beginAutomaticBackup(onDisclosureReady: (BackupDraft) -> Unit) {
        viewModelScope.launch {
            runCatching { photos.beginBackupEnrollment(BackupScope.CAMERA_JPEGS) }
                .onSuccess(onDisclosureReady)
                .onFailure { status.value = status.value.copy(message = it.toUserMessage(R.string.message_could_not_start_backup_setup)) }
        }
    }

    suspend fun acceptAutomaticBackup(draftId: BackupDraftId): BackupSetupEffect {
        val effect = photos.acceptBackupDisclosure(draftId)
        if (effect is BackupSetupEffect.Refused) {
            status.value = status.value.copy(message = localizedStoredMessage(effect.reason))
        }
        return effect
    }

    fun finishAutomaticBackup(draftId: BackupDraftId) {
        viewModelScope.launch {
            runCatching { photos.finishBackupEnrollment(draftId) }
                .onSuccess { status.value = status.value.copy(message = localizedStoredMessage(it.message)) }
                .onFailure { status.value = status.value.copy(message = it.toUserMessage(R.string.message_could_not_enable_backup)) }
        }
    }

    fun disableAutomaticBackup() {
        viewModelScope.launch {
            runCatching { photos.changeBackup(BackupChange.Disable) }
                .onSuccess { status.value = status.value.copy(message = localizedStoredMessage(it.message)) }
                .onFailure { status.value = status.value.copy(message = it.toUserMessage(R.string.message_could_not_disable_backup)) }
        }
    }

    fun requestVerifiedTrash(
        mediaId: String,
        onHandoffReady: (TrashHandoff) -> Unit,
    ) {
        runMediaAction(mediaId, R.string.message_could_not_prepare_this_photo_for_android_trash) {
            val proposal = photos.prepareTrash(setOf(mediaId))
            proposal.blockedReason?.let { reason ->
                status.value = status.value.copy(message = localizedStoredMessage(reason))
                return@runMediaAction
            }
            onHandoffReady(photos.beginTrash(proposal.id))
        }
    }

    fun recordTrashResult(
        attempt: TrashAttemptId,
        result: SystemConsentCallback,
    ) {
        viewModelScope.launch {
            runCatching { photos.recordTrashResult(attempt, result) }
                .onSuccess {
                    status.value = status.value.copy(
                        message = UiMessage(if (result == SystemConsentCallback.APPROVED) {
                            R.string.message_android_approved_the_trash_request_mela_is_checking_the_phone_item_its_icloud_copy_remains_veri
                        } else {
                            R.string.message_nothing_was_removed_from_this_phone
                        }),
                    )
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    status.value = status.value.copy(
                        message = error.toUserMessage(R.string.message_could_not_confirm_android_s_trash_result),
                    )
                }
        }
    }

    fun continuePastUnresolved(transferId: TransferId) {
        viewModelScope.launch {
            try {
                photos.continuePastUnresolved(transferId)
                status.value = status.value.copy(
                    message = UiMessage(R.string.message_that_uncertain_item_stays_blocked_mela_can_continue_with_other_photos),
                )
                scheduleBatches()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                status.value = status.value.copy(
                    message = error.toUserMessage(R.string.message_could_not_continue_the_upload_queue),
                )
            }
        }
    }

    fun localOperationFailed() { status.value = status.value.copy(message = UiMessage(R.string.local_action_failed)) }

    fun dismissMessage() {
        status.value = status.value.copy(message = null)
    }

    private fun runMediaAction(
        mediaId: String,
        fallbackMessage: Int,
        action: suspend () -> Unit,
    ) {
        if (status.value.activeDownloadId != null) return
        mediaActionJob = viewModelScope.launch {
            val span = diagnostics.start(
                DiagnosticOperation.MEDIA_ACTION,
                mapOf(DiagnosticAttribute.ACTION to diagnosticMediaAction(mediaId)),
            )
            status.value = status.value.copy(activeDownloadId = mediaId, message = null)
            try {
                action()
                span.finish(DiagnosticOutcome.SUCCESS)
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    span.finish(DiagnosticOutcome.CANCELLED)
                    throw error
                }
                span.finish(DiagnosticOutcome.FAILED, error = error)
                status.value = status.value.copy(message = error.toUserMessage(fallbackMessage))
            } finally {
                status.value = status.value.copy(activeDownloadId = null)
                mediaActionJob = null
            }
        }
    }

    private fun Throwable.toUserMessage(fallback: Int): UiMessage = when (this) {
        is dev.mela.protocol.auth.AppleProtocolException -> UiMessage(when (error) {
            dev.mela.protocol.auth.AppleProtocolError.INVALID_CREDENTIALS -> R.string.invalid_credentials
            dev.mela.protocol.auth.AppleProtocolError.INVALID_TWO_FACTOR_CODE -> R.string.invalid_two_factor_code
            dev.mela.protocol.auth.AppleProtocolError.SESSION_EXPIRED -> R.string.session_expired
            dev.mela.protocol.auth.AppleProtocolError.TERMS_UPDATE_REQUIRED -> R.string.terms_update_required
            dev.mela.protocol.auth.AppleProtocolError.PHOTOS_UNAVAILABLE -> R.string.apple_did_not_provide_a_photos_service_for_this_account
            dev.mela.protocol.auth.AppleProtocolError.UNSUPPORTED_TWO_FACTOR -> R.string.unsupported_two_factor
            dev.mela.protocol.auth.AppleProtocolError.NETWORK -> R.string.network_error
            dev.mela.protocol.auth.AppleProtocolError.MALFORMED_RESPONSE -> R.string.malformed_response
        })
        else -> localizedStoredMessage(message, fallback)
    }

    private suspend fun <T> measured(
        operation: DiagnosticOperation,
        attributes: Map<DiagnosticAttribute, Any> = emptyMap(),
        block: suspend () -> T,
    ): T {
        val span = diagnostics.start(operation, attributes)
        return try {
            block().also { span.finish(DiagnosticOutcome.SUCCESS) }
        } catch (error: Throwable) {
            span.finish(
                if (error is CancellationException) DiagnosticOutcome.CANCELLED else DiagnosticOutcome.FAILED,
                error = error.takeUnless { it is CancellationException },
            )
            throw error
        }
    }

    private fun diagnosticMediaAction(mediaId: String): String = when {
        mediaId == "picker" -> "picker_upload"
        mediaId.startsWith("offline:") -> "offline"
        mediaId == "share" -> "share"
        mediaId == "local-storage" -> "storage_cleanup"
        mediaId == "favorites" -> "favorite"
        mediaId == "album" -> "album"
        mediaId == "cloud-trash" -> "cloud_trash"
        mediaId == "shared-album" -> "shared_album"
        else -> "save"
    }

    private data class ViewModelStatus(
        val phoneStorage: dev.mela.engine.model.LocalMediaStorage? = null,
        val phoneStorageChecking: Boolean = false,
        val phoneStorageError: UiMessage? = null,
        val accountBusy: Boolean = false,
        val previewRetryVersion: Int = 0,
        val limitedPhotoAccess: Boolean = false,
        val isRefreshing: Boolean = true,
        val activeDownloadId: String? = null,
        val lastRefreshedAt: Instant? = null,
        val message: UiMessage? = null,
        val isAccountOpen: Boolean = false,
    )

    class Factory(
        context: Context,
    ) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            check(modelClass.isAssignableFrom(MelaViewModel::class.java))
            val graph = (appContext as MelaApplication).graph
            return MelaViewModel(
                savedState = extras.createSavedStateHandle(),
                repository = graph.galleryRepository,
                exporter = GalleryExporter(appContext, graph.galleryRepository),
                network = networkStatus(appContext),
                diagnostics = graph.diagnostics,
                accountManager = graph.accountManager,
                photos = graph.photos,
                maintenance = graph.maintenance,
                batchActions = graph.batches,
                scheduleBatches = { UserTransferScheduler(appContext).enqueue() },
            ) as T
        }
    }

    private companion object {
        const val MAX_CONCURRENT_PREVIEWS = 4
        const val MAX_PENDING_PREVIEWS = 48
    }
}
