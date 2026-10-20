package me.lvca.alba.app

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.GalleryDate
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.GalleryQuery
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.GallerySort
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.source.MediaRead
import me.lvca.alba.protocol.account.ICloudAccountManager
import java.time.LocalDate

/** What the gallery shows: the query, its results, the selection and the photo open in the viewer. */
data class GalleryState(
    val query: GalleryQuery = GalleryQuery(),
    val selection: Set<String> = emptySet(),
    val items: List<GalleryMedia> = emptyList(),
    val catalogItems: List<GalleryMedia> = items,
    val sections: List<GallerySection> = buildGallerySections(items),
    val collections: List<GalleryCollection> = emptyList(),
    val selectedMedia: GalleryMedia? = null,
    val cloudCount: Int = 0,
    val deviceCount: Int = 0,
    val deviceMediaAccess: Boolean = false,
    val limitedPhotoAccess: Boolean = false,
)

private data class DeviceAccess(val granted: Boolean = false, val limited: Boolean = false)

private data class Browsing(
    val query: GalleryQuery,
    val selection: Set<String>,
    val selectedMediaId: String?,
    val access: DeviceAccess,
)

/**
 * Browses the library: the query, the selection, the open photo and phone photo access. Projects the catalog for the
 * query off the main thread, so selection, viewer and message changes never redo that work.
 */
internal class GalleryBrowser(
    scope: CoroutineScope,
    private val savedState: SavedStateHandle,
    private val repository: GalleryRepository,
    accountManager: ICloudAccountManager,
    private val diagnostics: AlbaDiagnostics,
    private val requestPreview: (String) -> Unit,
) {
    private val query = MutableStateFlow(restoredQuery(savedState))
    private val selection = MutableStateFlow(savedState.get<ArrayList<String>>(SELECTION)?.toSet() ?: emptySet())
    private val deviceAccess = MutableStateFlow(DeviceAccess())
    val selectedMediaId: StateFlow<String?> = savedState.getStateFlow(MEDIA, null)

    /** The selection as the user last changed it, ahead of [state]. */
    val selectedIds: Set<String> get() = selection.value
    val hasDeviceAccess: Boolean get() = deviceAccess.value.granted

    private val projection = GalleryProjection()
    private val projected = combine(
        repository.observeGallery(),
        query,
        deviceAccess.map { it.granted }.distinctUntilChanged(),
        accountManager.state,
    ) { items, query, granted, account -> projection.apply(items, query, granted, account) }
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val collections = combine(
        repository.observeCollections(),
        projected.map { it.accountState to it.catalog.deviceFolders }.distinctUntilChanged(),
    ) { collections, (account, folders) -> visibleCollections(collections, account, folders) }
        .flowOn(Dispatchers.Default)

    private val browsing = combine(query, selection, selectedMediaId, deviceAccess, ::Browsing)

    val state: StateFlow<GalleryState> = combine(projected, collections, browsing) { projected, collections, browsing ->
        GalleryState(
            query = browsing.query,
            selection = browsing.selection,
            items = projected.items,
            catalogItems = projected.catalog.items,
            sections = projected.sections,
            collections = collections,
            selectedMedia = browsing.selectedMediaId?.let(projected.catalog.byId::get),
            cloudCount = projected.catalog.cloudCount,
            deviceCount = projected.catalog.deviceCount,
            deviceMediaAccess = browsing.access.granted,
            limitedPhotoAccess = browsing.access.limited,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), GalleryState())

    /** The latest published state. Only current while the UI observes [state]. */
    val current: GalleryState get() = state.value

    init {
        scope.launch {
            accountManager.session.map { it?.dsid }.distinctUntilChanged().collect { account ->
                if (account != null) {
                    val previous = savedState.get<String>(ACCOUNT)
                    // Another account's query, selection and open photo must not carry over.
                    if (previous != null && previous != account) {
                        clearSelection(); closeMedia(); setQuery(GalleryQuery())
                    }
                    savedState[ACCOUNT] = account
                }
            }
        }
    }

    fun setQuery(value: GalleryQuery) {
        savedState[QUERY_ORIGIN] = value.origin?.name
        savedState[LEGACY_FILTER] = LEGACY_FILTER_ALL
        savedState[FILENAME] = value.filename
        savedState[COLLECTION] = value.collectionId
        savedState[FROM_DATE] = value.fromDate?.toString()
        savedState[THROUGH_DATE] = value.throughDate?.toString()
        savedState[DATE_BASIS] = value.date.name
        savedState[TRASH_ONLY] = value.trashOnly
        savedState[SORT] = value.sort.name
        savedState[OFFLINE_ONLY] = value.offlineOnly
        query.value = value
        updateSelection(emptySet())
    }

    fun currentQuery(): GalleryQuery = query.value

    fun selectItems(ids: Set<String>, selected: Boolean) =
        updateSelection(if (selected) selection.value + ids else selection.value - ids)

    fun toggleSelection(id: String) = updateSelection(selection.value.let { if (id in it) it - id else it + id })
    fun selectAllShown() = updateSelection(current.items.map { it.id }.toSet())
    fun clearSelection() = updateSelection(emptySet())

    fun openMedia(mediaId: String) {
        savedState[MEDIA] = mediaId
        requestPreview(mediaId)
    }

    fun closeMedia() {
        savedState[MEDIA] = null
    }

    /** Records phone photo access. Returns whether access itself changed, which calls for a refresh. */
    fun setDeviceMediaAccess(granted: Boolean, limited: Boolean): Boolean {
        val changed = deviceAccess.value.granted != granted
        deviceAccess.value = DeviceAccess(granted, limited)
        if (changed && !granted && query.value.origin == MediaOrigin.DEVICE) setQuery(query.value.copy(origin = null))
        return changed
    }

    suspend fun loadViewer(id: String) {
        if (current.catalogItems.any { it.id == id && it.origin == MediaOrigin.ICLOUD }) {
            diagnostics.measure(DiagnosticOperation.VIEWER_DOWNLOAD) { repository.ensureViewer(id) }
        }
    }

    suspend fun openPlayback(id: String, position: Long, length: Long): MediaRead =
        diagnostics.measure(DiagnosticOperation.PLAYBACK_OPEN) { repository.openPlayback(id, position, length) }

    private fun updateSelection(ids: Set<String>) {
        selection.value = ids
        // Rotation keeps the ViewModel. Bound process-death state so selecting an entire
        // large library cannot exceed Android's shared saved-state transaction limit.
        savedState[SELECTION] = ArrayList(if (ids.sumOf { it.length.toLong() * 2L + 32L } <= 64 * 1024L) ids else emptySet())
    }

    private companion object {
        const val MEDIA = "media"
        const val SELECTION = "selection"
        const val ACCOUNT = "accountIdentity"
        const val QUERY_ORIGIN = "queryOrigin"
        const val FILENAME = "filename"
        const val COLLECTION = "collection"
        const val FROM_DATE = "fromDate"
        const val THROUGH_DATE = "throughDate"
        const val DATE_BASIS = "dateBasis"
        const val TRASH_ONLY = "trashOnly"
        const val SORT = "sort"
        const val OFFLINE_ONLY = "offlineOnly"

        /** Saved by releases that stored an origin filter instead of [QUERY_ORIGIN]. */
        const val LEGACY_FILTER = "filter"
        const val LEGACY_FILTER_ALL = "ALL"

        fun restoredQuery(savedState: SavedStateHandle) = GalleryQuery(
            origin = savedState.get<String>(QUERY_ORIGIN)?.let(MediaOrigin::valueOf)
                ?: when (savedState.get<String>(LEGACY_FILTER)) {
                    "ICLOUD" -> MediaOrigin.ICLOUD
                    "DEVICE" -> MediaOrigin.DEVICE
                    else -> null
                },
            filename = savedState[FILENAME] ?: "",
            collectionId = savedState[COLLECTION],
            fromDate = savedState.get<String>(FROM_DATE)?.let(LocalDate::parse),
            throughDate = savedState.get<String>(THROUGH_DATE)?.let(LocalDate::parse),
            sort = savedState.get<String>(SORT)?.let(GallerySort::valueOf) ?: GallerySort.NEWEST,
            offlineOnly = savedState[OFFLINE_ONLY] ?: false,
            trashOnly = savedState[TRASH_ONLY] ?: false,
            date = savedState.get<String>(DATE_BASIS)?.let(GalleryDate::valueOf) ?: GalleryDate.CAPTURED,
        )
    }
}
