@file:OptIn(ExperimentalLayoutApi::class)

package me.lvca.alba.app.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.lvca.alba.app.R
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.gallery.GallerySection
import me.lvca.alba.app.gallery.GalleryState
import me.lvca.alba.app.gallery.LibraryPreparing
import me.lvca.alba.app.platform.demoEntry
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.BrowseActions
import me.lvca.alba.app.ui.GalleryScreenActions
import me.lvca.alba.app.ui.account.backupStatus
import me.lvca.alba.app.ui.adaptive.albaLayout
import me.lvca.alba.app.ui.collections.AlbumHero
import me.lvca.alba.app.ui.collections.AlbumNameDialog
import me.lvca.alba.app.ui.collections.CollectionDetailPlaceholder
import me.lvca.alba.app.ui.collections.CollectionRow
import me.lvca.alba.app.ui.collections.CollectionsListDetail
import me.lvca.alba.app.ui.collections.CollectionsPage
import me.lvca.alba.app.ui.collections.CollectionsScreen
import me.lvca.alba.app.ui.collections.SharedAlbumsStrip
import me.lvca.alba.app.ui.collections.icon
import me.lvca.alba.app.ui.collections.isPersonal
import me.lvca.alba.app.ui.collections.rememberCollectionPreviews
import me.lvca.alba.app.ui.collections.sharedStripAlbums
import me.lvca.alba.app.ui.currentLocale
import me.lvca.alba.app.ui.dateLabel
import me.lvca.alba.app.ui.designsystem.AlbaBottomSheet
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.AlbaFloatingDefaults
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.AlbaMenu
import me.lvca.alba.app.ui.designsystem.AlbaMenuDivider
import me.lvca.alba.app.ui.designsystem.AlbaMenuItem
import me.lvca.alba.app.ui.designsystem.AlbaSheetBody
import me.lvca.alba.app.ui.designsystem.DemoBadge
import me.lvca.alba.app.ui.designsystem.theme.AlbaIconSize
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.app.ui.designsystem.theme.galleryOverlay
import me.lvca.alba.app.ui.designsystem.theme.tile
import me.lvca.alba.app.ui.itemCount
import me.lvca.alba.app.ui.localizedName
import me.lvca.alba.app.ui.localizedTitle
import me.lvca.alba.app.ui.monthLabel
import me.lvca.alba.app.ui.navigation.FadeThrough
import me.lvca.alba.app.ui.shared.CreateSharedAlbumDialog
import me.lvca.alba.app.ui.shared.SharedActivityEffects
import me.lvca.alba.app.ui.shared.SharedAlbumActivity
import me.lvca.alba.app.ui.shared.SharedAlbumControls
import me.lvca.alba.app.ui.shared.SharedAlbumDetails
import me.lvca.alba.app.ui.shared.SharedAlbumEmptyState
import me.lvca.alba.app.ui.shared.SharedAlbumNotice
import me.lvca.alba.app.ui.shared.SharedContributionPicker
import me.lvca.alba.app.ui.shared.SharedInvitations
import me.lvca.alba.app.ui.shared.sharedContributionCandidates
import me.lvca.alba.app.ui.transfers.BackupHero
import me.lvca.alba.app.ui.transfers.BackupHeroAction
import me.lvca.alba.app.ui.transfers.BatchProgress
import me.lvca.alba.app.ui.transfers.TransferQueueCard
import me.lvca.alba.app.ui.transfers.failureGroups
import me.lvca.alba.app.ui.viewer.LocalPhotoTransition
import me.lvca.alba.app.ui.viewer.galleryColorEase
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion
import me.lvca.alba.engine.model.*
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import java.time.LocalDate

/**
 * Where a photo grid was, by media id rather than by index. A restored LazyGridState only knows an index, and it is
 * clamped the first time the grid is measured against fewer items than that (another tab's results, or a catalog that
 * has not loaded yet), which silently resets the scroll to the top. A restored anchor is re-applied once its photo
 * is present; any scroll by the user cancels it.
 */
internal class GridAnchor(var mediaId: String? = null, var offset: Int = 0, restoring: Boolean = false,
    /** Grid keys on screen when a photo was opened; the viewer returns into place without scrolling to them. */
    var visibleWhenOpened: List<String> = emptyList()) {
    var restoring by mutableStateOf(restoring && mediaId != null)

    companion object {
        val Saver = listSaver<GridAnchor, Any?>(
            save = { listOf(it.mediaId, it.offset, ArrayList(it.visibleWhenOpened)) },
            restore = {
                @Suppress("UNCHECKED_CAST")
                GridAnchor(it[0] as String?, it[1] as Int, restoring = true, visibleWhenOpened = it[2] as List<String>)
            })
    }
}

/** Records the first visible photo into [anchor] and, for a restored anchor, scrolls back to it once its photo is present. */
@Composable
internal fun KeepGridAnchor(grid: LazyGridState, anchor: GridAnchor, positions: Map<String, Int>, gridIndexOf: (String) -> Int) {
    val indexOf by rememberUpdatedState(gridIndexOf)
    LaunchedEffect(grid, anchor) {
        snapshotFlow { grid.isScrollInProgress }.collect { if (it) anchor.restoring = false }
    }
    LaunchedEffect(grid, anchor, positions) {
        val id = anchor.mediaId
        if (anchor.restoring && id != null && id in positions) {
            grid.requestScrollToItem(indexOf(id), anchor.offset)
            anchor.restoring = false
        }
    }
    LaunchedEffect(grid, anchor, positions) {
        snapshotFlow {
            if (anchor.restoring) null
            else grid.layoutInfo.visibleItemsInfo.firstOrNull { (it.key as? String)?.let(positions::containsKey) == true }
                // Negative when items above the first photo (headers, prompts) are on screen, so they stay in view.
                ?.let { (it.key as String) to -it.offset.y }
        }.collect { found -> if (found != null) { anchor.mediaId = found.first; anchor.offset = found.second } }
    }
}

private val QuerySaver = listSaver<GalleryQuery, String>(
    save = { listOf(it.origin?.name.orEmpty(), it.collectionId.orEmpty(), it.filename,
        it.fromDate?.toString().orEmpty(), it.throughDate?.toString().orEmpty(), it.sort.name, it.offlineOnly.toString(), it.date.name, it.trashOnly.toString()) },
    restore = { GalleryQuery(it[0].takeIf(String::isNotEmpty)?.let(MediaOrigin::valueOf), it[1].takeIf(String::isNotEmpty),
        it[2], it[3].takeIf(String::isNotEmpty)?.let(LocalDate::parse), it[4].takeIf(String::isNotEmpty)?.let(LocalDate::parse),
        GallerySort.valueOf(it[5]), it[6].toBoolean(), it.getOrNull(7)?.let(GalleryDate::valueOf) ?: GalleryDate.CAPTURED, it.getOrNull(8)?.toBoolean() ?: false) })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryHome(
    gallery: GalleryState,
    session: SessionState,
    transfers: TransferState,
    mediaBusy: Boolean,
    previewRetryVersion: Int,
    actions: GalleryScreenActions,
    onBottomSpaceChanged: (Dp) -> Unit,
) {
    val canEdit = session.canEditLibrary(mediaBusy)
    val identity = if (session.accountState == ICloudAccountState.Restoring) null else
        (session.accountState as? ICloudAccountState.SignedIn)?.appleId ?: session.cloudSourceLabel
    var tab by rememberSaveable { mutableStateOf(GalleryTab.LIBRARY) }
    var libraryQuery by rememberSaveable(stateSaver = QuerySaver) { mutableStateOf(GalleryQuery()) }
    var collectionQuery by rememberSaveable(stateSaver = QuerySaver) { mutableStateOf(GalleryQuery()) }
    var searchQuery by rememberSaveable(stateSaver = QuerySaver) { mutableStateOf(GalleryQuery()) }
    var collectionsPage by rememberSaveable { mutableStateOf<CollectionsPage?>(null) }
    var albumMenu by remember { mutableStateOf(false) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var level by rememberSaveable { mutableStateOf(GridLevel.DAY) }
    var albumLevel by rememberSaveable { mutableStateOf(GridLevel.COMPACT) }
    var sheet by rememberSaveable(stateSaver = GallerySheet.Saver) { mutableStateOf(null) }
    val scope = rememberCoroutineScope()
    var permissionDismissed by rememberSaveable { mutableStateOf(false) }
    var limitedNoteDismissed by rememberSaveable { mutableStateOf(false) }
    var owner by rememberSaveable { mutableStateOf(identity) }
    LaunchedEffect(identity) {
        if (identity != null) {
            if (owner != null && owner != identity) {
                tab = GalleryTab.LIBRARY
                libraryQuery = GalleryQuery(); collectionQuery = GalleryQuery(); searchQuery = GalleryQuery()
                folder = null; collectionsPage = null; sheet = null
                actions.changeQuery(GalleryQuery())
            }
            owner = identity
        }
    }
    val libraryGrid = rememberLazyGridState()
    val collectionGrid = rememberLazyGridState()
    val searchGrid = rememberLazyGridState()
    val libraryAnchor = rememberSaveable(saver = GridAnchor.Saver) { GridAnchor() }
    val collectionAnchor = rememberSaveable(saver = GridAnchor.Saver) { GridAnchor() }
    val searchAnchor = rememberSaveable(saver = GridAnchor.Saver) { GridAnchor() }
    val collectionScroll = rememberLazyGridState()
    // Hidden photos never appear in album covers or counts.
    val unhidden = remember(gallery.catalogItems) { gallery.catalogItems.filterNot { it.isHidden } }
    val summaries = key(identity) { rememberCollectionPreviews(unhidden, gallery.collections) }
    val sharedAlbums = remember(gallery.collections) { gallery.collections.filter { it.shared != null } }
    val sharedStripShows = remember(sharedAlbums, summaries) { sharedStripAlbums(sharedAlbums, summaries).isNotEmpty() }
    fun switchTab(next: GalleryTab) {
        if (next == tab) return
        when (tab) {
            GalleryTab.LIBRARY -> libraryQuery = gallery.query
            GalleryTab.COLLECTIONS -> collectionQuery = gallery.query
            GalleryTab.SEARCH -> searchQuery = gallery.query
        }
        tab = next
        actions.clearSelection()
        actions.changeQuery(when (next) {
            GalleryTab.LIBRARY -> libraryQuery
            GalleryTab.COLLECTIONS -> collectionQuery
            GalleryTab.SEARCH -> searchQuery
        })
    }
    val tabFade = remember(scope) { FadeThrough<GalleryTab>(scope) }
    val reduceTabMotion = reduceGalleryMotion()

    // The navigation answers a tap at once; the screens switch once the current one has faded out. Only the navigation
    // reads the pending tab, so the tap's first frame recomposes nothing else.
    fun selectedTab() = tabFade.pending ?: tab
    // A tap whose fade a rotation or other recreation cuts short still lands on its tab. Nothing in composition reads
    // this, so writing it keeps the tap's first frame cheap.
    var interruptedTab by rememberSaveable { mutableStateOf<GalleryTab?>(null) }
    LaunchedEffect(Unit) { interruptedTab?.let { interruptedTab = null; switchTab(it) } }
    fun navigate(next: GalleryTab) {
        if (next == selectedTab()) return
        interruptedTab = next.takeIf { it != tab }
        tabFade.start(tab, next, reduceTabMotion) { interruptedTab = null; switchTab(it) }
    }
    val query = gallery.query
    val layout = albaLayout()
    // An expanded window keeps Collections' list on the left and shows the opened collection in a pane on the right,
    // under a bar of its own; the header then belongs to the list.
    val splitCollections = layout.listDetail && tab == GalleryTab.COLLECTIONS
    val inCollection = tab == GalleryTab.COLLECTIONS && (query.collectionId != null || query.offlineOnly || query.trashOnly)
    val albumInHeader = inCollection && !splitCollections
    val collection = gallery.collections.firstOrNull { it.id == query.collectionId }
    val collectionTitle = when {
        query.trashOnly -> stringResource(if (query.origin == MediaOrigin.ICLOUD) R.string.icloud_recently_deleted else R.string.phone_trash)
        query.offlineOnly -> stringResource(R.string.offline)
        query.collectionId == GalleryQuery.FAVORITES -> stringResource(R.string.favorites)
        query.collectionId == GalleryQuery.HIDDEN -> stringResource(R.string.hidden)
        else -> collection?.localizedName() ?: SmartCollection.entries.firstOrNull { it.id == query.collectionId }?.localizedTitle() ?: stringResource(R.string.collection)
    }
    fun closeCollection() = actions.changeQuery(GalleryQuery())
    fun backInCollections() {
        if (albumInHeader) closeCollection()
        else if (folder != null) folder = gallery.collections.firstOrNull { it.id == folder }?.parentId
        else if (collectionsPage != null) collectionsPage = null
        else switchTab(GalleryTab.LIBRARY)
    }
    val backEnabled = LocalPhotoTransition.current?.active != false && gallery.selection.isEmpty()
    val parentOf: (String) -> String? = { id -> gallery.collections.firstOrNull { it.id == id }?.parentId }
    val screen = if (tab == GalleryTab.COLLECTIONS) GalleryScreen(tab, collectionsPage, folder,
        GalleryScreen.albumOf(query).takeUnless { splitCollections }) else GalleryScreen(tab)
    val libraryFrame = rememberGraphicsLayer()
    val detailFrame = rememberGraphicsLayer()
    val title = if (albumInHeader) collectionTitle else if (tab == GalleryTab.COLLECTIONS && folder != null)
        gallery.collections.firstOrNull { it.id == folder }?.name ?: stringResource(R.string.albums) else stringResource(if (tab == GalleryTab.COLLECTIONS) collectionsPage?.title ?: tab.title else tab.title)
    val contextAccountLabel = stringResource(R.string.open_icloud_account)
    val signedIn = session.accountState as? ICloudAccountState.SignedIn
    val libraryLoad = session.libraryLoad(gallery)
    // The first load's line under the large title; Apple's indexing has its own card instead. A refresh shows nothing.
    val headerBar = libraryLoad == LibraryLoad.INITIAL_LOAD && session.libraryPreparing == null
    // The first read of a large library takes a while: the line says how far it is rather than only that it is updating.
    val loadLabel = if (headerBar) session.catalogScan?.let { catalogScanLabel(it) } ?: stringResource(R.string.updating_library) else null
    val accountStatus = accountStatus(session, transfers, libraryLoad)
    val canUpload = signedIn?.status == SessionStatus.VERIFIED || session.accountState == ICloudAccountState.Demo
    // Clearing filters keeps the place being browsed: an open collection, offline copies or a trash.
    val unfiltered = if (inCollection) GalleryQuery(origin = query.origin.takeIf { query.trashOnly }, collectionId = query.collectionId,
        offlineOnly = query.offlineOnly, trashOnly = query.trashOnly, sort = query.sort, date = query.date)
        else GalleryQuery(sort = query.sort, date = query.date)
    // Without an account or photos there is nothing to filter: offer connecting iCloud and the demo instead.
    val connectPrompt = session.accountState == ICloudAccountState.SignedOut && tab == GalleryTab.LIBRARY &&
        query == unfiltered && gallery.items.isEmpty() && !session.isRefreshing
    val hero = inCollection && collection != null && !collection.isFolder && !collection.id.startsWith("smart:") && !collection.id.startsWith("device-folder:")
    val heroInHeader = hero && !splitCollections
    val visibleLevel = if (hero) albumLevel else level
    val changeLevel: (GridLevel) -> Unit = { if (hero) albumLevel = it else level = it }
    // Reserve the leading grid item before the catalog arrives, so lazy-key anchoring cannot hide it.
    val showSharedStrip = tab == GalleryTab.LIBRARY && query.collectionId == null &&
        query.fromDate == null && query.throughDate == null && !query.offlineOnly && !query.trashOnly
    val searchAlbums = remember(gallery.collections, query.filename, tab) {
        if (tab == GalleryTab.SEARCH && query.filename.isNotBlank()) matchingAlbums(gallery.collections, query.filename) else emptyList()
    }
    // iCloud's Recently Deleted opens with Apple's note on how long items stay.
    val cloudTrash = inCollection && query.trashOnly && query.origin == MediaOrigin.ICLOUD
    val leadsWithContent = hero || showSharedStrip || searchAlbums.isNotEmpty()
    val leadingItems = if (leadsWithContent || cloudTrash) 1 else 0
    // A shared album's hero and Recently Deleted show their photos without date headers.
    val flatGrid = hero || query.trashOnly
    // Android 14's partial access: a small note in the prompt's place says how many photos Alba sees, with Change.
    val limitedNote = tab == GalleryTab.LIBRARY && gallery.deviceMediaAccess && gallery.limitedPhotoAccess && !limitedNoteDismissed
    val permissionPrompt = (tab == GalleryTab.LIBRARY && !gallery.deviceMediaAccess && !permissionDismissed) || limitedNote
    fun openShared(id: String, page: CollectionsPage = CollectionsPage.SHARED) {
        when (tab) {
            GalleryTab.LIBRARY -> libraryQuery = gallery.query
            GalleryTab.SEARCH -> searchQuery = gallery.query
            GalleryTab.COLLECTIONS -> Unit
        }
        tab = GalleryTab.COLLECTIONS
        collectionsPage = page
        actions.clearSelection()
        collectionGrid.requestScrollToItem(0)
        actions.changeQuery(GalleryQuery(collectionId = id))
    }
    SharedActivityEffects(remember(sharedAlbums) { sharedAlbums.map { it.id }.toSet() }, actions, openAlbum = ::openShared) { invitations ->
        libraryQuery = gallery.query; tab = GalleryTab.COLLECTIONS; collectionsPage = CollectionsPage.SHARED
        actions.clearSelection(); actions.changeQuery(GalleryQuery())
        if (invitations) sheet = GallerySheet.SharedInvitations
    }
    val uiDensity = LocalDensity.current
    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        val wide = layout.navigationRail
        // A short window (a phone in landscape) keeps one small bar: the title inline, with the filter actions beside
        // the screen's own, and no rows under it.
        val smallBar = !layout.largeTitles
        // A wide window shows the selection's actions in the header, unless it is short: there they float at the bottom
        // as on a phone, so starting a selection adds no row and never moves the grid.
        val headerSelection = wide && !smallBar
        val phonePhotos = !wide && tab == GalleryTab.LIBRARY
        // Selecting keeps the header where it is: resetting it would shift the grid under a drag-selecting finger.
        val collapseHeader = phonePhotos
        val selecting = gallery.selection.isNotEmpty()
        // Kept while selecting: the selection bar covers it, and removing it would shift the grid.
        val filterRow = !heroInHeader && (wide || tab != GalleryTab.LIBRARY) && (tab != GalleryTab.COLLECTIONS || albumInHeader)
        val filtersInBar = smallBar && filterRow
        val headerState = rememberTopAppBarState()
        // A short grid can fit after collapse; it must still allow a reverse drag to restore the header.
        val collapseAllowed by rememberUpdatedState(collapseHeader)
        val reducedMotion = reduceGalleryMotion()
        val reducedMotionNow by rememberUpdatedState(reducedMotion)
        val headerScroll = remember(headerState, libraryGrid, uiDensity) {
            GalleryHeaderScroll(headerState, HeaderScrollGate(GalleryFeel.HEADER_REVEAL_DP_PER_SECOND * uiDensity.density,
                GalleryFeel.HEADER_HIDE_DP_PER_SECOND * uiDensity.density),
                enabled = { collapseAllowed && (headerState.heightOffset < 0f || libraryGrid.canScrollForward || libraryGrid.canScrollBackward) },
                reducedMotion = { reducedMotionNow })
        }
        val headerHidden by remember { derivedStateOf { headerState.collapsedFraction >= .99f } }
        // Returning the header is independent of reaching the beginning of the grid.
        val galleryScrolled by remember(libraryGrid, headerState) {
            derivedStateOf { libraryGrid.canScrollBackward || headerState.heightOffset < -.5f }
        }
        val headerColor = animateColorAsState(
            if (phonePhotos) {
                if (galleryScrolled) MaterialTheme.colorScheme.galleryOverlay else MaterialTheme.colorScheme.surface
            } else MaterialTheme.colorScheme.background,
            animationSpec = if (reducedMotion) snap() else tween(250, easing = galleryColorEase), label = "gallery-header-surface")
        val sections = gallery.sections
        val monthOffsets = remember(sections, permissionPrompt, leadingItems, visibleLevel, query.date) {
            galleryMonthOffsets(sections, permissionPrompt, visibleLevel.dayHeaders, query.date, leadingItems)
        }
        val headerClearance = with(uiDensity) { GalleryFeel.STICKY_DATE_HEADER_CLEARANCE.toPx() }
        val visibleMonth by remember(libraryGrid, sections, monthOffsets, headerClearance) {
            derivedStateOf {
                val visible = libraryGrid.layoutInfo.visibleItemsInfo
                val first = visible.firstOrNull { it.offset.y + it.size.height > 0 }
                val headers = visible.filter { (it.key as? String)?.startsWith("month-") == true }
                    .map { it.offset.y to it.offset.y + it.size.height }
                // A month farther down the viewport must not label the opening featured section.
                if (!galleryScrolled || first == null || first.index < (monthOffsets.firstOrNull() ?: Int.MAX_VALUE)) null
                // Like Google Photos, the pill steps aside while a real month header is near the top.
                else if (monthHeaderNearTop(headers, headerClearance)) null
                else sections.getOrNull(monthOffsets.indexOfLast { it <= first.index })?.month
            }
        }
        // Like Google Photos, the floating date shows while the grid moves and hides a second after it stops.
        val scrubber = remember { ScrubberHost() }
        val dateScrolled by rememberRecentlyScrolled(libraryGrid) { scrubber.dragging }
        LaunchedEffect(collapseHeader) {
            if (!collapseHeader) { headerState.heightOffset = 0f; headerState.contentOffset = 0f }
        }
        // Elsewhere the large title collapses into its bar as the content scrolls, and opens again on each new screen.
        // Photos on a phone keeps Google Photos' header instead, which scrolls away whole.
        // A short window's small bar does not expand, so it has nothing to collapse.
        val collapseTitle = !phonePhotos && !heroInHeader && !smallBar
        val titleState = rememberTopAppBarState()
        val titleScroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(titleState)
        LaunchedEffect(screen) { titleState.heightOffset = 0f; titleState.contentOffset = 0f }
        var floatingBarHeight by remember { mutableStateOf(80.dp) }
        var headerHeight by remember { mutableStateOf(0.dp) }
        // The floating bar sits up to 16dp above the navigation bar; keep the grid's last row a clear 24dp above it.
        // A short wide window's selection floats too, so the grid makes room for it while selecting.
        val bottomSpace = if (wide && (headerSelection || !selecting)) 24.dp
            else floatingBarHeight + 40.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LaunchedEffect(bottomSpace) { onBottomSpaceChanged(bottomSpace) }
        // An album with a hero carries its title over the cover, so its bar holds only these.
        val heroActions: @Composable RowScope.() -> Unit = {
            if (collection?.shared != null) IconButton(onClick = { sheet = GallerySheet.SharedManage }, enabled = canEdit) {
                Icon(AlbaIcons.AccountCircle, stringResource(R.string.shared_manage))
            }
            IconButton(onClick = { sheet = GallerySheet.Filters }, modifier = Modifier.testTag("filter-button")) { Icon(painterResource(R.drawable.ic_filter), stringResource(R.string.filter_and_sort)) }
            Box {
                IconButton(onClick = { albumMenu = true }, modifier = Modifier.testTag("album-options")) {
                    Icon(AlbaIcons.MoreVert, stringResource(R.string.album_options))
                }
                AlbaMenu(expanded = albumMenu, onDismissRequest = { albumMenu = false }) {
                    AlbaMenuItem(stringResource(R.string.browse_by_date), onClick = { albumMenu = false; sheet = GallerySheet.JumpToDate },
                        enabled = sections.isNotEmpty(), leadingIcon = { Icon(AlbaIcons.DateRange, null) })
                    AlbaMenuDivider()
                    if (collection?.shared != null) {
                        AlbaMenuItem(stringResource(R.string.shared_details),
                            onClick = { albumMenu = false; sheet = GallerySheet.SharedInfo },
                            leadingIcon = { Icon(AlbaIcons.Info, null) })
                        if (collection.shared?.canContribute == true) AlbaMenuItem(stringResource(R.string.shared_add_photos),
                            onClick = { albumMenu = false; sheet = GallerySheet.SharedPicker }, enabled = canEdit,
                            leadingIcon = { Icon(AlbaIcons.Add, null) })
                    } else {
                        AlbaMenuItem(stringResource(R.string.rename_album),
                            onClick = { albumMenu = false; sheet = collection?.id?.let(GallerySheet::RenameAlbum) }, enabled = canEdit,
                            leadingIcon = { Icon(AlbaIcons.Edit, null) }, modifier = Modifier.testTag("album-rename"))
                        AlbaMenuItem(stringResource(R.string.delete_album),
                            onClick = { albumMenu = false; sheet = collection?.id?.let(GallerySheet::DeleteAlbum) }, enabled = canEdit,
                            leadingIcon = { Icon(AlbaIcons.DeleteOutline, null) })
                    }
                }
            }
            TextButton(onClick = { if (gallery.selection.isEmpty()) gallery.items.firstOrNull()?.let { actions.toggleSelection(it.id) } else actions.clearSelection() }, enabled = gallery.items.isNotEmpty()) {
                Text(stringResource(if (gallery.selection.isEmpty()) R.string.select_items else R.string.cancel))
            }
        }
        // What the grid shows, with its album, date and filter actions.
        val filterName = query.collectionId?.let { id -> when (id) {
            GalleryQuery.FAVORITES -> stringResource(R.string.favorites)
            GalleryQuery.HIDDEN -> stringResource(R.string.hidden)
            else -> SmartCollection.entries.firstOrNull { it.id == id }?.localizedTitle()
        } }
        val allPhotos = stringResource(R.string.all_photos)
        val originName = query.origin?.let { if (it == MediaOrigin.DEVICE) stringResource(R.string.this_phone) else stringResource(R.string.icloud) }
        val filterSummary = listOfNotNull(originName ?: allPhotos, filterName,
            if (query.fromDate != null || query.throughDate != null) stringResource(R.string.date_range) else null,
            if (query.date == GalleryDate.ADDED) stringResource(R.string.date_added) else null).joinToString(" · ")
        // In a short window's small bar they sit beside the screen's own actions, and a filter shows under the title.
        val filterActions: @Composable RowScope.() -> Unit = {
            if (inCollection && collection != null && collection.isPersonal) {
                IconButton(enabled = canEdit, onClick = { sheet = GallerySheet.RenameAlbum(collection.id) }) {
                    Icon(AlbaIcons.Edit, stringResource(R.string.rename_album))
                }
                IconButton(enabled = canEdit, onClick = { sheet = GallerySheet.DeleteAlbum(collection.id) }) { Icon(AlbaIcons.DeleteOutline, stringResource(R.string.delete_album)) }
            }
            // Recently Deleted is in deletion order, with no dates to browse by. Where there are none,
            // the button is left out rather than dimmed, so the actions here keep one colour.
            val datesBrowsable = sections.isNotEmpty() && !query.trashOnly
            if (datesBrowsable) IconButton(onClick = { sheet = GallerySheet.JumpToDate }, modifier = Modifier.testTag("browse-dates-button")) {
                Icon(AlbaIcons.DateRange, stringResource(R.string.browse_by_date))
            }
            IconButton(onClick = { sheet = GallerySheet.Filters }, modifier = Modifier.testTag("filter-button")) { Icon(painterResource(R.drawable.ic_filter), stringResource(R.string.filter_and_sort)) }
        }
        val filterControls: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(filterSummary, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                filterActions()
            }
        }
        // In landscape the navigation bar or a cutout can sit at either side; the rail takes the start side's itself.
        val sideInsets = WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout)
        var railWidth by remember { mutableStateOf(0.dp) }
        Row(Modifier.fillMaxSize().windowInsetsPadding(sideInsets.only(if (wide) WindowInsetsSides.End else WindowInsetsSides.Horizontal))) {
            if (wide) GalleryNavigationRail(selectedTab(), { navigate(it) },
                Modifier.onSizeChanged { railWidth = with(uiDensity) { it.width.toDp() } })
            Scaffold(modifier = Modifier.weight(1f).nestedScroll(headerScroll)
                .then(if (collapseTitle) Modifier.nestedScroll(titleScroll.nestedScrollConnection) else Modifier), contentWindowInsets = WindowInsets(0, 0, 0, 0),
                containerColor = if (phonePhotos) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background,
                topBar = {
                    // The header belongs to its destination, so it fades through with the screen below it.
                    Column(Modifier.scrollingGalleryHeader(headerState, collapseHeader)
                        .onSizeChanged { headerHeight = with(uiDensity) { it.height.toDp() } }
                        .graphicsLayer { alpha = tabFade.alpha }
                        // Ignore visibility: the viewer hides the bars, and the header must not jump as they return.
                        .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                        .drawBehind { drawRect(headerColor.value) }.testTag("gallery-header")
                        .then(if (collapseHeader && headerHidden) Modifier.clearAndSetSemantics {} else Modifier)) {
                        val subScreen = tab == GalleryTab.COLLECTIONS && (albumInHeader || folder != null || collectionsPage != null)
                        // The header draws its own surface behind the bar, so the bar itself stays clear.
                        val barColors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
                        val back: @Composable () -> Unit = {
                            IconButton(onClick = ::backInCollections) { Icon(AlbaIcons.ArrowBack, stringResource(R.string.back_action)) }
                        }
                        val libraryHeader = !hero && !connectPrompt && tab == GalleryTab.LIBRARY
                        val backupChip = libraryHeader && gallery.selection.isEmpty()
                        val barTitle: @Composable () -> Unit = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false).testTag("screen-title-${tab.name}"))
                                if (!subScreen && session.accountState == ICloudAccountState.Demo) DemoBadge()
                            }
                        }
                        val barActions: @Composable RowScope.() -> Unit = {
                            if (smallBar && backupChip) BackupStatusChip(session, transfers, actions::openBackup)
                            if (filtersInBar) filterActions()
                            if (!subScreen) {
                                if (phonePhotos && gallery.selection.isEmpty()) IconButton(onClick = { sheet = GallerySheet.Filters },
                                    modifier = Modifier.testTag("gallery-menu-button")) {
                                    Icon(painterResource(R.drawable.ic_filter), stringResource(R.string.filter_and_sort))
                                }
                                IconButton(onClick = if (canUpload) actions::pickUpload else actions::openAccount, modifier = Modifier.testTag("gallery-upload")) {
                                    Icon(AlbaIcons.Add, stringResource(R.string.upload_photos))
                                }
                                IconButton(onClick = { sheet = GallerySheet.Activity }, modifier = Modifier.testTag("activity-button")) {
                                    BadgedBox(badge = { if (transfers.queue.any { it.state != TransferViewState.VERIFIED } || transfers.batches.any { it.status != BatchItemState.DONE }) Badge() }) {
                                        Icon(AlbaIcons.CloudSync, stringResource(R.string.uploads_and_activity))
                                    }
                                }
                                AccountButton(signedIn?.appleId, accountStatus, contextAccountLabel, actions::openAccount,
                                    Modifier.testTag("account-button"))
                            }
                        }
                        if (heroInHeader) TopAppBar(title = {}, navigationIcon = back, colors = barColors, windowInsets = WindowInsets(0, 0, 0, 0), actions = heroActions)
                        // A short window's one bar: the title inline, and a filter, when one is set, in a line under it.
                        else if (smallBar) TopAppBar(
                            title = {
                                Column {
                                    barTitle()
                                    if (filtersInBar && filterSummary != allPhotos) Text(filterSummary, style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            navigationIcon = { if (subScreen) back() },
                            actions = barActions,
                            colors = barColors,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            modifier = Modifier.testTag("title-bar-small"),
                        )
                        // Every other screen opens with its name as a large title, Apple's large titles on Material's bar.
                        else LargeTopAppBar(
                            title = barTitle,
                            navigationIcon = { if (subScreen) back() },
                            actions = barActions,
                            colors = barColors,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            scrollBehavior = if (collapseTitle) titleScroll else null,
                            modifier = Modifier.testTag("title-bar-large"),
                        )
                        if (!smallBar && backupChip) BackupStatusChip(session, transfers, actions::openBackup)
                        // The first load's progress, calmly under the title: how far it is, over a bar that fills as pages land.
                        if (loadLabel != null) Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                            Text(loadLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp))
                            val scanFraction = session.catalogScan?.fraction()
                            // 4dp above the bar, so 8dp separate it from the line.
                            val barModifier = Modifier.padding(top = 4.dp).fillMaxWidth()
                            if (scanFraction != null) LinearProgressIndicator(progress = { scanFraction }, modifier = barModifier.testTag("catalog-scan-bar"))
                            else LinearProgressIndicator(barModifier)
                        }
                        if (headerSelection && selecting) SelectionActions(gallery, session, mediaBusy, actions, Modifier.padding(vertical = 8.dp))
                        else if (filterRow) {
                            if (tab == GalleryTab.SEARCH) GallerySearchBar(query.filename, { actions.changeQuery(query.copy(filename = it)) },
                                Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            if (!filtersInBar) filterControls()
                        }
                    }
                }) { padding ->
                val statusOverlap = (WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding() -
                    padding.calculateTopPadding()).coerceAtLeast(0.dp)
                // The header scrolls away over the grid's top: the scrubber's track stays below where it was, so its
                // thumb never rides under the status bar.
                scrubber.topInset = { (headerHeight - padding.calculateTopPadding()).coerceAtLeast(0.dp) }
                CompositionLocalProvider(LocalScrubberHost provides scrubber) {
                Box(Modifier.fillMaxSize().padding(padding)) {
                    GalleryScreenHost(screen, parentOf, backEnabled, onBack = {
                        if (tab == GalleryTab.COLLECTIONS) backInCollections() else switchTab(GalleryTab.LIBRARY)
                    }, libraryFrame = libraryFrame, faded = { tabFade.switched },
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = tabFade.alpha }) { shown ->
                    val collectionsList: @Composable () -> Unit = {
                        // Only this pull's own refresh shows the indicator: one already running, at start-up or in the background, stays quiet.
                        CollectionsPullToRefresh(enabled = shown.page == null && shown.folder == null, refreshing = session.pullRefreshing,
                            onRefresh = actions::pullToRefresh) {
                            CollectionsScreen(gallery, canEdit, previewRetryVersion, shown.folder, shown.page, summaries,
                                if (shown.page == null && shown.folder == null) collectionScroll else rememberLazyGridState(), actions::requestPreview,
                                openPage = { collectionsPage = it },
                                openFolder = { folder = it }, openCollection = { collectionGrid.requestScrollToItem(0); actions.changeQuery(GalleryQuery(collectionId = it)) },
                                openOffline = { actions.changeQuery(GalleryQuery(offlineOnly = true)) }, openTrash = { actions.changeQuery(GalleryQuery(origin = MediaOrigin.DEVICE, trashOnly = true)) },
                                openCloudTrash = { actions.changeQuery(GalleryQuery(origin = MediaOrigin.ICLOUD, trashOnly = true)) }, createAlbum = { sheet = GallerySheet.CreateAlbum },
                                openHidden = { collectionGrid.requestScrollToItem(0); actions.openHidden() },
                                createSharedAlbum = { sheet = GallerySheet.CreateSharedAlbum },
                                openSharedInvitations = { sheet = GallerySheet.SharedInvitations },
                                requestPhotos = actions::requestDevicePhotos, bottomSpace = bottomSpace)
                        }
                    }
                    val photoGrid: @Composable () -> Unit = {
                        // Like Google Photos, the photo grid stretches at its edges instead of pulling to refresh:
                        // the library refreshes on resume and from Filter and sort.
                        PhotoGrid(
                            items = gallery.items,
                            sections = sections,
                            selection = gallery.selection,
                            query = query,
                            unfiltered = unfiltered,
                            connectPrompt = connectPrompt,
                            isRefreshing = session.isRefreshing,
                            counts = PhotoCounts(gallery.cloudCount, gallery.deviceCount, limitedAccess = limitedNote,
                                catalogScan = session.catalogScan?.takeIf { it.initialLoad }, libraryPreparing = session.libraryPreparing,
                                loaded = gallery.libraryLoaded),
                            previewRetryVersion = previewRetryVersion,
                            grid = when (shown.tab) {
                                GalleryTab.LIBRARY -> libraryGrid; GalleryTab.COLLECTIONS -> collectionGrid; GalleryTab.SEARCH -> searchGrid
                            },
                            anchor = when (shown.tab) {
                                GalleryTab.LIBRARY -> libraryAnchor; GalleryTab.COLLECTIONS -> collectionAnchor; GalleryTab.SEARCH -> searchAnchor
                            },
                            level = visibleLevel,
                            onLevelChange = changeLevel,
                            bottomSpace = bottomSpace,
                            showPermissionPrompt = permissionPrompt,
                            onDismissPermissionPrompt = { if (limitedNote) limitedNoteDismissed = true else permissionDismissed = true },
                            actions = actions,
                            flat = flatGrid,
                            onAddMedia = if (hero && collection?.shared?.canContribute == true && canEdit) ({ sheet = GallerySheet.SharedPicker }) else null,
                            leading = if (hero) ({
                                AlbumHero(requireNotNull(collection), summaries[collection.id], previewRetryVersion, actions::requestPreview,
                                    activity = if (collection.shared?.generation == SharedAlbumGeneration.MODERN) ({ sheet = GallerySheet.SharedActivity }) else null,
                                    view = gallery.items.firstOrNull()?.let { first -> { actions.openMedia(first.id) } },
                                    info = if (collection.shared != null) ({ sheet = GallerySheet.SharedInfo }) else null)
                                // An empty shared album the person can add to prompts the first steps under its hero.
                                val addable = canEdit && collection.shared?.canContribute == true
                                // Offline, an empty album may only be out of reach; a viewer's empty album says photos will appear.
                                if (collection.shared != null && gallery.items.isEmpty() && !session.isRefreshing) {
                                    if (session.network?.online == false) SharedAlbumNotice(unreachable = true, refresh = actions::refresh)
                                    else if (addable) SharedAlbumEmptyState({ sheet = GallerySheet.SharedPicker },
                                        if (collection.shared?.canManage == true) ({ sheet = GallerySheet.SharedManage }) else null)
                                    else SharedAlbumNotice(unreachable = false, refresh = actions::refresh)
                                }
                            }) else if (searchAlbums.isNotEmpty()) ({
                                MatchingAlbums(searchAlbums, summaries, previewRetryVersion, actions::requestPreview) { album ->
                                    openShared(album.id, if (album.shared != null) CollectionsPage.SHARED else CollectionsPage.ALBUMS)
                                }
                            }) else if (showSharedStrip) ({
                                SharedAlbumsStrip(sharedAlbums, summaries, previewRetryVersion, actions::requestPreview, ::openShared,
                                    seeAll = { libraryQuery = gallery.query; tab = GalleryTab.COLLECTIONS; collectionsPage = CollectionsPage.SHARED; actions.clearSelection(); actions.changeQuery(GalleryQuery()) })
                            }) else if (cloudTrash) ({ RecentlyDeletedNote() }) else null,
                            // The strip takes space only once the album previews are counted, which can be well after
                            // the first photos show; its arrival reflows the grid, which must not slide photos over it.
                            leadingShape = sharedStripShows.takeIf { !hero && searchAlbums.isEmpty() && showSharedStrip },
                        )
                    }
                    if (shown.tab == GalleryTab.COLLECTIONS && splitCollections) {
                        val detail = GalleryScreen(GalleryTab.COLLECTIONS, album = GalleryScreen.albumOf(query))
                        CollectionsListDetail(detailOpen = detail.album != null, list = collectionsList) {
                            // Back closes the collection with the gesture, and leaves the list where it was.
                            GalleryScreenHost(detail, parentOf, backEnabled && detail.album != null, onBack = ::closeCollection,
                                libraryFrame = detailFrame, modifier = Modifier.fillMaxSize()) { shownDetail ->
                                if (shownDetail.album == null) CollectionDetailPlaceholder()
                                else Column(Modifier.fillMaxSize()) {
                                    CollectionDetailBar(collectionTitle, hero = hero, close = ::closeCollection, heroActions = heroActions,
                                        filters = filterControls.takeUnless { hero || smallBar || (headerSelection && selecting) },
                                        titleActions = filterActions.takeIf { smallBar && !hero })
                                    Box(Modifier.weight(1f)) { photoGrid() }
                                }
                            }
                        }
                    } else if (shown.tab == GalleryTab.COLLECTIONS && shown.album == null) collectionsList()
                    else if (shown.tab == GalleryTab.SEARCH && query.isBlankSearch) {
                        SearchLanding(summaries, bottomSpace = bottomSpace, onDates = { sheet = GallerySheet.Filters },
                            choose = { actions.changeQuery(query.copy(collectionId = it)) })
                    } else photoGrid()
                    }
                    if (collapseHeader) FloatingGridDate(visibleMonth.takeUnless { selecting },
                        dates = { sheet = GallerySheet.JumpToDate }, modifier = Modifier.align(Alignment.TopCenter).padding(top = statusOverlap).zIndex(1f),
                        recentlyScrolled = { dateScrolled }, switching = { tabFade.pending != null || tabFade.switched })
                    // Selection swaps the navigation for the floating toolbar, each riding a fast spatial spring along
                    // the bottom edge, so a quick select-and-cancel reverses mid-flight.
                    if (!headerSelection) androidx.compose.animation.AnimatedVisibility(selecting, Modifier.align(Alignment.BottomCenter),
                        enter = AlbaFloatingDefaults.enter(reducedMotion), exit = AlbaFloatingDefaults.exit(reducedMotion)) {
                        SelectionActions(gallery, session, mediaBusy, actions,
                            Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = AlbaFloatingDefaults.BottomMargin)
                                .onSizeChanged { if (it.height > 0) floatingBarHeight = with(uiDensity) { it.height.toDp() } })
                    }
                    if (!wide) androidx.compose.animation.AnimatedVisibility(!selecting, Modifier.align(Alignment.BottomCenter),
                        enter = AlbaFloatingDefaults.enter(reducedMotion), exit = AlbaFloatingDefaults.exit(reducedMotion)) {
                        // It hides behind an open keyboard; keep the grid's bottom space at its last real height.
                        FloatingGalleryNavigation(selectedTab(), { navigate(it) },
                            Modifier.navigationBarsPadding().padding(bottom = AlbaFloatingDefaults.BottomMargin)
                                .onSizeChanged { if (it.height > 0) floatingBarHeight = with(uiDensity) { it.height.toDp() } })
                    }
                }
                }
            }
        }
        if (phonePhotos) GalleryStatusBarScrim({ headerColor.value }, Modifier.align(Alignment.TopCenter).zIndex(2f))
        // The contextual bar replaces the header in place, over it, so the grid never moves when selection starts.
        SelectionTopBar(gallery.selection.size, minHeight = if (wide) 0.dp else headerHeight, reducedMotion = reducedMotion,
            selectAll = actions::selectAllShown, clear = actions::clearSelection,
            modifier = Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(sideInsets.only(if (wide) WindowInsetsSides.End else WindowInsetsSides.Horizontal))
                .padding(start = if (wide) railWidth else 0.dp).zIndex(3f))
    }
    val currentGrid = when (tab) { GalleryTab.LIBRARY -> libraryGrid; GalleryTab.COLLECTIONS -> collectionGrid; GalleryTab.SEARCH -> searchGrid }
    if (sheet == GallerySheet.JumpToDate) {
        val initialIndex = remember { currentGrid.firstVisibleItemIndex }
        GalleryDateSheet(gallery.sections, initialIndex, days = visibleLevel.dayHeaders, date = query.date,
            permissionHeader = permissionPrompt, leadingItems = leadingItems, showDateHeaders = !flatGrid,
            dismiss = { sheet = null }, jump = { index -> scope.launch { currentGrid.scrollToItem(index) } })
    }
    if (sheet == GallerySheet.Filters) GalleryFilterSheet(gallery, actions::changeQuery, actions::requestDevicePhotos, actions::refresh,
        visibleLevel, changeLevel, dismiss = { sheet = null })
    (sheet as? GallerySheet.DeleteAlbum)?.albumId?.let { id ->
        AlertDialog(onDismissRequest = { sheet = null }, title = { Text(stringResource(R.string.delete_album_title)) },
            text = { Text(stringResource(R.string.delete_album_body, gallery.collections.firstOrNull { it.id == id }?.name ?: stringResource(R.string.this_album))) },
            confirmButton = { TextButton(enabled = canEdit, onClick = { actions.deleteAlbum(id); sheet = null }) { Text(stringResource(R.string.delete_album)) } },
            dismissButton = { TextButton(onClick = { sheet = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (sheet == GallerySheet.Activity) AlbaBottomSheet(onDismissRequest = { sheet = null }) {
        AlbaSheetBody(stringResource(R.string.activity), dismiss = { sheet = null }, subtitle = stringResource(R.string.uploads_downloads_and_backup)) {
            // Backup first, as in Google Photos: its status and the one action that helps, then what moved recently.
            val demo = session.accountState == ICloudAccountState.Demo
            BackupHero(backupStatus(transfers, session.accountState, session.network, session.charging), transfers, demo, canTurnOn = canUpload,
                failures = failureGroups(transfers, session.network, demo).takeIf { transfers.backup.enabled }.orEmpty(),
                queueActions = actions,
                onAction = { action ->
                    if (action != BackupHeroAction.RETRY_STORAGE) sheet = null
                    when (action) {
                        BackupHeroAction.TURN_ON, BackupHeroAction.REVIEW -> actions.enableAutomaticBackup()
                        BackupHeroAction.ALLOW_ACCESS -> actions.openPermissions()
                        BackupHeroAction.SIGN_IN -> actions.openAccount()
                        BackupHeroAction.RETRY_STORAGE -> actions.retryAfterStorageFull()
                    }
                })
            if (transfers.queue.isEmpty() && transfers.batches.isEmpty()) {
                Text(stringResource(R.string.no_transfers_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = AlbaSpacing.lg).testTag("activity-empty"))
            }
            if (transfers.queue.isNotEmpty()) TransferQueueCard(transfers.queue, actions::continuePastUnresolved, demo = demo, onRetry = actions::retryTransfer)
            BatchProgress(transfers.batches, gallery.items, actions::retryBatch, actions::cancelDownloadBatch)
            if (transfers.queue.any { !it.automatic && it.state in setOf(TransferViewState.WAITING, TransferViewState.UPLOADING) } && !demo) {
                OutlinedButton(onClick = actions::cancelUploads, modifier = Modifier.fillMaxWidth().testTag("cancel-uploads")) {
                    Text(stringResource(R.string.cancel_uploads))
                }
            }
            FilledTonalButton(onClick = if (canUpload) actions::pickUpload else { { sheet = null; actions.openAccount() } },
                modifier = Modifier.fillMaxWidth().testTag("upload-picker-button")) {
                Icon(AlbaIcons.CloudUpload, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.upload_photos))
            }
            TextButton(onClick = { sheet = null; actions.openBackup() }, modifier = Modifier.testTag("activity-open-backup")) {
                Text(stringResource(R.string.account_storage_and_backup))
            }
        }
    }
    if (sheet == GallerySheet.SharedPicker && collection?.shared != null) {
        val generation = requireNotNull(collection.shared).generation
        val (candidates, livePhotos) = remember(gallery.catalogItems, generation) { sharedContributionCandidates(gallery.catalogItems, generation) }
        SharedContributionPicker(candidates, preview = actions::requestPreview, dismiss = { sheet = null },
            contribute = { ids, caption -> actions.contributeToSharedAlbum(collection.id, ids, caption); sheet = null },
            legacy = generation == SharedAlbumGeneration.LEGACY, requestPhotos = actions::requestDevicePhotos, excludedLivePhotos = livePhotos)
    }
    if (sheet == GallerySheet.SharedInvitations) SharedInvitations(actions, dismiss = { sheet = null }, demo = session.accountState == ICloudAccountState.Demo)
    if (sheet == GallerySheet.SharedActivity && collection?.shared?.generation == SharedAlbumGeneration.MODERN) SharedAlbumActivity(collection, gallery.catalogItems, actions, actions::requestPreview, open = { sheet = null; actions.openMedia(it) }, dismiss = { sheet = null })
    if (sheet == GallerySheet.SharedManage && collection?.shared != null) SharedAlbumControls(collection, actions) { sheet = null }
    if (sheet == GallerySheet.SharedInfo && collection?.shared != null) SharedAlbumDetails(collection) { sheet = null }
    when (val current = sheet) {
        GallerySheet.CreateSharedAlbum -> CreateSharedAlbumDialog(dismiss = { sheet = null }) { name, generation ->
            actions.createSharedAlbum(name, generation); sheet = null
        }
        GallerySheet.CreateAlbum -> AlbumNameDialog(stringResource(R.string.new_album), "", { sheet = null }) { name ->
            actions.createAlbum(name); sheet = null
        }
        is GallerySheet.RenameAlbum -> AlbumNameDialog(stringResource(R.string.rename_album),
            gallery.collections.firstOrNull { it.id == current.albumId }?.name.orEmpty(), { sheet = null }) { name ->
            actions.renameAlbum(current.albumId, name); sheet = null
        }
        else -> Unit
    }
}

/**
 * The one sheet or dialog open over the gallery. Only one can be open, closing it is one assignment, and it is
 * saved so it survives recreation.
 */
internal sealed interface GallerySheet {
    data object Filters : GallerySheet
    data object Activity : GallerySheet
    data object JumpToDate : GallerySheet
    data object SharedInvitations : GallerySheet
    data object SharedPicker : GallerySheet
    data object SharedActivity : GallerySheet
    data object SharedManage : GallerySheet
    data object SharedInfo : GallerySheet
    data object CreateAlbum : GallerySheet
    data object CreateSharedAlbum : GallerySheet
    data class RenameAlbum(val albumId: String) : GallerySheet
    data class DeleteAlbum(val albumId: String) : GallerySheet

    companion object {
        private val objects: List<GallerySheet> = listOf(Filters, Activity, JumpToDate, SharedInvitations, SharedPicker,
            SharedActivity, SharedManage, SharedInfo, CreateAlbum, CreateSharedAlbum)
        private const val RENAME = "rename:"
        private const val DELETE = "delete:"

        val Saver = Saver<GallerySheet?, String>(
            save = { sheet ->
                when (sheet) {
                    is RenameAlbum -> RENAME + sheet.albumId
                    is DeleteAlbum -> DELETE + sheet.albumId
                    null -> null
                    else -> sheet.toString()
                }
            },
            restore = { saved ->
                when {
                    saved.startsWith(RENAME) -> RenameAlbum(saved.removePrefix(RENAME))
                    saved.startsWith(DELETE) -> DeleteAlbum(saved.removePrefix(DELETE))
                    else -> objects.firstOrNull { it.toString() == saved }
                }
            },
        )
    }
}

/**
 * The library's totals, shown under the grid. [limitedAccess] marks Android 14's partial access, where [device] counts
 * only the photos the user selected for Alba.
 */
internal data class PhotoCounts(
    val cloud: Int,
    val device: Int,
    val limitedAccess: Boolean = false,
    /** The first full read of the iCloud library, while it is unfinished. */
    val catalogScan: CatalogScanProgress? = null,
    /** Apple is still indexing the iCloud library. */
    val libraryPreparing: LibraryPreparing? = null,
    /** The stored library is read whole; until then the grid holds at most its newest photos and no totals. */
    val loaded: Boolean = true,
)

/**
 * Whether the grid's tiles animate their placement: only photos arriving or leaving slide. Headers and the items above
 * the photos move at once, so when anything else reflows the grid, sliding tiles would lag behind them and draw over
 * the rows that pushed them. A change of any of [layout] (the level, the whole library replacing the first screen's
 * newest photos, the leading item changing size as the Shared Albums strip does once its previews are counted, a note
 * above the photos coming or going) lays the grid out at once, and placement animates again from two frames later.
 */
@Composable
private fun rememberPlacementAnimates(vararg layout: Any?): State<Boolean> {
    val animates = remember(*layout) { mutableStateOf(false) }
    LaunchedEffect(*layout) { withFrameNanos {}; withFrameNanos {}; animates.value = true }
    return animates
}

/**
 * A grid of photos grouped by month, and by day from the [GridLevel.DAY] level up. Takes the lists it shows rather
 * than the whole gallery state, and remembers everything derived from them, so a message or progress change does not
 * redo that work. Pinching morphs live into the neighbouring level (see [GridPinch]).
 */
@Composable
private fun PhotoGrid(
    items: List<GalleryMedia>,
    sections: List<GallerySection>,
    selection: Set<String>,
    query: GalleryQuery,
    unfiltered: GalleryQuery,
    connectPrompt: Boolean,
    isRefreshing: Boolean,
    counts: PhotoCounts,
    previewRetryVersion: Int,
    grid: LazyGridState,
    anchor: GridAnchor,
    level: GridLevel,
    onLevelChange: (GridLevel) -> Unit,
    bottomSpace: Dp,
    showPermissionPrompt: Boolean,
    onDismissPermissionPrompt: () -> Unit,
    actions: BrowseActions,
    leading: (@Composable () -> Unit)? = null,
    leadingShape: Any? = null,
    flat: Boolean = false,
    onAddMedia: (() -> Unit)? = null,
) {
    val leadingItems = if (leading != null) 1 else 0
    val date = query.date
    val mediaIds = remember(items) { items.map { it.id } }
    val positions = remember(mediaIds) { mediaIds.withIndex().associate { it.value to it.index } }
    // Grouped once per header style; a pinch lays out the neighbouring level from the same cache.
    val groupings = remember(sections, flat, date) { mutableMapOf<Boolean, List<Pair<GallerySection, Map<LocalDate?, List<GalleryMedia>>>>>() }
    fun groupsFor(shown: GridLevel) = (!flat && shown.dayHeaders).let { days ->
        groupings.getOrPut(days) {
            sections.map { section -> section to if (days) section.items.groupBy { galleryDay(it, date) } else mapOf(null to section.items) }
        }
    }
    val groupedSections = groupsFor(level)
    val dayHeaders = level.dayHeaders
    val gridIndexOf: (String, GridLevel) -> Int = remember(sections, showPermissionPrompt, date, leadingItems, flat) {
        { id, shown -> galleryPosition(sections, showPermissionPrompt, shown.dayHeaders, date, id, leadingItems, showDateHeaders = !flat) }
    }
    val prefetch by rememberUpdatedState(actions::prefetchPreviews)
    LaunchedEffect(grid, positions, previewRetryVersion) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.filter { it in positions } }
            .distinctUntilChanged().collect { visible ->
                val first = visible.firstOrNull()?.let(positions::get)
                val last = visible.lastOrNull()?.let(positions::get)
                if (first != null && last != null) {
                    val ahead = mediaIds.subList((last + 1).coerceAtMost(mediaIds.size), (last + 13).coerceAtMost(mediaIds.size))
                    val behind = mediaIds.subList((first - 12).coerceAtLeast(0), first).asReversed()
                    prefetch((visible + ahead + behind).distinct().take(48))
                } else prefetch(emptyList())
            }
    }
    DisposableEffect(Unit) { onDispose { prefetch(emptyList()) } }
    KeepGridAnchor(grid, anchor, positions) { id -> gridIndexOf(id, level) }
    val returningId = LocalPhotoTransition.current?.returningMediaId
    // Kept in the anchor, which GalleryHome saves: PhotoGrid's own saved state does not survive the viewer.
    LaunchedEffect(returningId) {
        if (returningId != null && returningId !in anchor.visibleWhenOpened) {
            grid.requestScrollToItem(gridIndexOf(returningId, level))
        }
    }
    val selecting = selection.isNotEmpty()
    val haptics = LocalHapticFeedback.current
    val latestSelection by rememberUpdatedState(selection)
    val openOrToggle: (String) -> Unit = remember(grid, anchor, actions, selecting, haptics) {
        { id ->
            if (!selecting) {
                anchor.visibleWhenOpened = grid.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }
                actions.openMedia(id)
            } else {
                // Google Photos ticks on every tap that selects or deselects a photo.
                haptics.performHapticFeedback(if (id in latestSelection) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                actions.toggleSelection(id)
            }
        }
    }
    val scope = rememberCoroutineScope()
    val reducedMotion = reduceGalleryMotion()
    val pinch = remember(grid) { GridPinch(scope) }
    SideEffect {
        pinch.level = level; pinch.grid = grid; pinch.reducedMotion = reducedMotion
        pinch.mediaKeys = positions.keys; pinch.indexIn = gridIndexOf; pinch.commit = onLevelChange
    }
    val placementAnimates by rememberPlacementAnimates(level, counts.loaded, leading != null, leadingShape,
        showPermissionPrompt, counts.libraryPreparing != null)
    val checkboxEnter = if (reducedMotion) EnterTransition.None else
        scaleIn(tween(GalleryFeel.SELECTION_MODE_MILLIS, easing = FastOutSlowInEasing)) +
            fadeIn(tween(GalleryFeel.CHECK_FADE_IN_MILLIS, delayMillis = GalleryFeel.CHECK_FADE_IN_DELAY_MILLIS))
    val checkboxExit = if (reducedMotion) ExitTransition.None else fadeOut(tween(GalleryFeel.CHECK_FADE_OUT_MILLIS))

    /** The grid's items at [shown]; [incoming] marks the next level laid out underneath during a pinch. */
    fun LazyGridScope.photoItems(shown: GridLevel, incoming: Boolean) {
        val posed = { key: Any -> Modifier.pinchPose(pinch, key, incoming) }
        if (showPermissionPrompt && counts.limitedAccess) item(key = "permission", span = { GridItemSpan(maxLineSpan) }) {
            LimitedAccessNote(counts.device, change = actions::requestDevicePhotos, dismiss = onDismissPermissionPrompt,
                modifier = posed("permission"))
        } else if (showPermissionPrompt) item(key = "permission", span = { GridItemSpan(maxLineSpan) }) {
            AlbaCard(posed("permission").padding(horizontal = 16.dp, vertical = 12.dp).testTag("phone-photo-prompt"),
                color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(AlbaIcons.PhoneAndroid, null, Modifier.padding(top = 2.dp).size(AlbaIconSize.standard), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.include_photos_from_this_phone), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.phone_photo_prompt_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton(onClick = onDismissPermissionPrompt, modifier = Modifier.testTag("phone-photo-prompt-dismiss")) {
                                Text(stringResource(R.string.not_now))
                            }
                            Button(onClick = actions::requestDevicePhotos) { Text(stringResource(R.string.phone_photo_prompt_action)) }
                        }
                    }
                }
            }
        }
        // Only the main library waits for Apple's indexing; the phone's own photos show below the note meanwhile.
        val preparing = counts.libraryPreparing?.takeIf { query == unfiltered && query.collectionId == null &&
            !query.trashOnly && !query.offlineOnly }
        if (preparing != null) item(key = "library-preparing", span = { GridItemSpan(maxLineSpan) }) {
            LibraryPreparingCard(preparing, checking = isRefreshing, checkNow = actions::refresh,
                modifier = posed("library-preparing").padding(horizontal = 16.dp, vertical = 12.dp))
        }
        if (leading != null) item(key = "gallery-leading", span = { GridItemSpan(maxLineSpan) }) { Column(posed("gallery-leading")) { leading() } }
        // Nothing is known to be missing before the stored library is read: no empty state or loading line for that moment.
        if (items.isEmpty() && counts.loaded) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Only the main library loads page by page; Trash, Hidden and albums keep the plain loading line.
                val scan = counts.catalogScan?.takeIf { query.collectionId == null && !query.trashOnly && !query.offlineOnly }
                if (preparing != null) Unit
                else if (isRefreshing && scan != null) LibraryLoading(scan)
                else if (isRefreshing) { CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.loading_photos)) }
                // Where photos can be added, the album's leading item shows the first steps instead.
                // A shared album's hero shows its own empty or unreachable state.
                else if (query.collectionId?.startsWith("shared:") == true) Unit
                else if (query.trashOnly) Text(stringResource(R.string.trash_empty))
                else if (query.collectionId == GalleryQuery.HIDDEN) Text(stringResource(R.string.hidden_empty))
                else if (connectPrompt) ConnectLibrary(connect = actions::openAccount, demo = demoEntry(actions::startDemo))
                else {
                    EmptyLibrary()
                    if (query != unfiltered) TextButton(onClick = { actions.changeQuery(unfiltered) }) { Text(stringResource(R.string.clear_filters)) }
                }
            }
        }
        groupsFor(shown).forEach { (section, groups) ->
            val month = section.month
            if (!flat) item(key = "month-$month", span = { GridItemSpan(maxLineSpan) }) {
                Row(posed("month-$month").fadeUnderStatusBar(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { MonthHeader(month, section.items.size) }
                    val selectionLabel = stringResource(R.string.label_with_detail, stringResource(R.string.select_all_shown), monthLabel(month))
                    AnimatedVisibility(selecting, Modifier.withoutHeight(), enter = checkboxEnter, exit = checkboxExit) {
                        HeaderSelectionCircle(section.items.all { it.id in selection }, selectionLabel,
                            { actions.selectItems(section.items.map { it.id }.toSet(), it) })
                    }
                }
            }
            groups.forEach { (day, photos) ->
            if (day != null) item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) {
                Row(posed("day-$day").fadeUnderStatusBar().fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dateLabel(day), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    val selectionLabel = stringResource(R.string.label_with_detail, stringResource(R.string.select_all_shown), dateLabel(day))
                    AnimatedVisibility(selecting, Modifier.withoutHeight(), enter = checkboxEnter, exit = checkboxExit) {
                        // The target overhangs the row's end padding so the circle stays aligned with the photos' edge.
                        HeaderSelectionCircle(photos.all { it.id in selection }, selectionLabel,
                            { checked -> actions.selectItems(photos.map { it.id }.toSet(), checked) }, Modifier.offset(x = 12.dp))
                    }
                }
            }
            items(photos, key = GalleryMedia::id) { media ->
                val id = media.id
                // Placement animates reflow when photos arrive or leave; the pinch morph draws level changes itself.
                Box((if (incoming) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                    placementSpec = if (placementAnimates && !reducedMotion) spring(dampingRatio = 1f, stiffness = 450f) else null))
                    .then(posed(id))) {
                    MediaTile(
                        retryVersion = previewRetryVersion,
                        selected = id in selection,
                        selecting = selecting,
                        media = media,
                        onClick = { openOrToggle(id) },
                        onVisible = if (incoming) ({}) else ({ actions.requestPreview(id) }),
                        selectionAction = { actions.toggleSelection(id) },
                    )
                }
            }
            }
        }
        if (onAddMedia != null && items.isNotEmpty()) item(key = "add-media") {
            Surface(onClick = onAddMedia, modifier = posed("add-media").aspectRatio(1f).testTag("album-add-media"),
                shape = MaterialTheme.shapes.tile, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(AlbaIcons.Add, stringResource(R.string.shared_add_photos), Modifier.size(AlbaIconSize.large),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (items.isNotEmpty() && counts.loaded) item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
            Column(posed("summary").fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(itemCount(items.size), style = MaterialTheme.typography.bodyMedium)
                // While Apple is indexing, "0 iCloud photos" would read as an empty library: count only the phone's.
                // A trash counts only itself: the library's totals beneath its own count read as what it holds.
                if (query.collectionId?.startsWith(SharedMediaIdentity.PREFIX) != true && !query.trashOnly) Text(if (counts.libraryPreparing != null) stringResource(R.string.phone_photo_count, counts.device)
                    else stringResource(R.string.cloud_phone_counts, pluralStringResource(R.plurals.cloud_photo_count, counts.cloud, counts.cloud), stringResource(R.string.phone_photo_count, counts.device)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val windowWidth = albaLayout().width
    val gridWidth = maxWidth.value
    val columns = level.columns(windowWidth, gridWidth)
    val spacing = Arrangement.spacedBy(GalleryFeel.GRID_SPACING)
    pinch.target?.let { target ->
        // The next level, laid out with the anchor's row at the same top. It draws only what the current grid cannot.
        val incomingGrid = remember(target) { LazyGridState().apply { requestScrollToItem(pinch.incomingIndex, pinch.incomingOffset) } }
        SideEffect { pinch.incomingGrid = incomingGrid }
        LaunchedEffect(incomingGrid) {
            pinch.measured(snapshotFlow { incomingGrid.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() })
        }
        CompositionLocalProvider(LocalPhotoTransition provides null) {
            LazyVerticalGrid(GridCells.Fixed(target.columns(windowWidth, gridWidth)), state = incomingGrid, userScrollEnabled = false,
                modifier = Modifier.fillMaxSize().clearAndSetSemantics {}, horizontalArrangement = spacing, verticalArrangement = spacing,
                contentPadding = PaddingValues(bottom = bottomSpace)) { photoItems(target, incoming = true) }
        }
    }
    LazyVerticalGrid(columns = GridCells.Fixed(columns), state = grid, modifier = Modifier.fillMaxSize()
        .graphicsLayer {
            val rubber = pinch.rubber
            if (rubber != 1f && size.width > 0f && size.height > 0f) {
                scaleX = rubber; scaleY = rubber
                transformOrigin = TransformOrigin(pinch.pivot.x / size.width, pinch.pivot.y / size.height)
            }
        }
        .galleryGestures(grid, mediaIds, selection, pinch, actions::selectItems).testTag("gallery-grid"),
        horizontalArrangement = spacing, verticalArrangement = spacing, contentPadding = PaddingValues(bottom = bottomSpace)) {
        photoItems(level, incoming = false)
    }
    val offsets = remember(sections, showPermissionPrompt, dayHeaders, date, leadingItems, flat) {
        galleryMonthOffsets(sections, showPermissionPrompt, dayHeaders, date, leadingItems, showDateHeaders = !flat)
    }
    val locale = currentLocale()
    // Recently Deleted is in deletion order, so a capture month would mislabel the scrollbar.
    val scrollLabels = remember(sections, offsets, locale, query.trashOnly) {
        if (query.trashOnly) emptyList()
        else sections.mapIndexed { i, s -> GalleryScrollLabel(offsets[i], monthLabel(s.month, abbreviated = true, locale = locale), s.month.year) }
    }
    val pixelDensity = LocalDensity.current
    val fullSpans = remember(groupedSections, showPermissionPrompt, leadingItems, flat, onAddMedia != null, pixelDensity) {
        with(pixelDensity) {
            buildMap<Int, Float> {
                var index = 0
                if (showPermissionPrompt) put(index++, 190.dp.toPx())
                if (leadingItems > 0) put(index++, (if (query.trashOnly) 96.dp else if (flat) 420.dp else 280.dp).toPx())
                groupedSections.forEach { (_, groups) ->
                    if (!flat) put(index++, 56.dp.toPx())
                    groups.forEach { (day, photos) ->
                        if (day != null) put(index++, 32.dp.toPx())
                        index += photos.size
                    }
                }
                if (onAddMedia != null) index++
                put(index, 80.dp.toPx())
            }
        }
    }
    GalleryFastScroll(grid, scrollLabels, fullSpans, columns, Modifier.align(Alignment.CenterEnd).fillMaxHeight(), bottomSpace)
    }
}

/**
 * The detail pane's own bar on an expanded window, where the header belongs to the list: the collection's name with
 * Close, or, over an album's hero, only its actions; then the collection's filter row. A short window has no room for
 * that row: its filter actions sit in the bar, as [titleActions].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionDetailBar(title: String, hero: Boolean, close: () -> Unit, heroActions: @Composable RowScope.() -> Unit,
    filters: (@Composable () -> Unit)?, titleActions: (@Composable RowScope.() -> Unit)? = null) {
    val colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
    val closeButton: @Composable () -> Unit = {
        IconButton(onClick = close, modifier = Modifier.testTag("collection-detail-close")) {
            Icon(AlbaIcons.Close, stringResource(R.string.close_surface))
        }
    }
    Column(Modifier.fillMaxWidth().testTag("collection-detail-bar")) {
        if (hero) TopAppBar(title = {}, navigationIcon = closeButton, actions = heroActions, colors = colors, windowInsets = WindowInsets(0, 0, 0, 0))
        else TopAppBar(title = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("collection-detail-title")) }, navigationIcon = closeButton, colors = colors,
            windowInsets = WindowInsets(0, 0, 0, 0), actions = titleActions ?: {})
        filters?.invoke()
    }
}

/**
 * A date header's select-all control: the same check circle as a photo's, as in Google Photos, centred in a 48dp
 * target. The header lays it out without height, so the target never makes the row taller.
 */
@Composable
private fun HeaderSelectionCircle(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    SelectionCircle(checked, onPhoto = false, modifier = modifier
        .toggleable(checked, interactionSource = null, indication = ripple(bounded = false, radius = 20.dp), role = Role.Checkbox,
            onValueChange = onCheckedChange)
        .semantics { contentDescription = label }
        .minimumInteractiveComponentSize())
}

/**
 * Takes no height in its row, drawing centred on it: entering selection must never make a date header taller and move
 * the photo under the finger.
 */
private fun Modifier.withoutHeight(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    layout(placeable.width, 0) { placeable.place(0, -placeable.height / 2) }
}
