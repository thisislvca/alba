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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.lvca.alba.app.R
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.gallery.GallerySection
import me.lvca.alba.app.gallery.GalleryState
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.BrowseActions
import me.lvca.alba.app.ui.GalleryScreenActions
import me.lvca.alba.app.ui.collections.AlbumHero
import me.lvca.alba.app.ui.collections.AlbumNameDialog
import me.lvca.alba.app.ui.collections.CollectionRow
import me.lvca.alba.app.ui.collections.CollectionsPage
import me.lvca.alba.app.ui.collections.CollectionsScreen
import me.lvca.alba.app.ui.collections.SharedAlbumsStrip
import me.lvca.alba.app.ui.collections.icon
import me.lvca.alba.app.ui.collections.isPersonal
import me.lvca.alba.app.ui.collections.rememberCollectionPreviews
import me.lvca.alba.app.ui.currentLocale
import me.lvca.alba.app.ui.dateLabel
import me.lvca.alba.app.ui.designsystem.AlbaBottomSheet
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.AlbaSheetBody
import me.lvca.alba.app.ui.designsystem.DemoBadge
import me.lvca.alba.app.ui.designsystem.ICloudIcon
import me.lvca.alba.app.ui.designsystem.InitialAvatar
import me.lvca.alba.app.ui.designsystem.theme.galleryOverlay
import me.lvca.alba.app.ui.designsystem.theme.tile
import me.lvca.alba.app.ui.itemCount
import me.lvca.alba.app.ui.localizedName
import me.lvca.alba.app.ui.localizedTitle
import me.lvca.alba.app.ui.monthLabel
import me.lvca.alba.app.ui.navigation.FadeThrough
import me.lvca.alba.app.ui.shared.CreateSharedAlbumDialog
import me.lvca.alba.app.ui.shared.SharedAlbumActivity
import me.lvca.alba.app.ui.shared.SharedAlbumControls
import me.lvca.alba.app.ui.shared.SharedAlbumDetails
import me.lvca.alba.app.ui.shared.SharedAlbumEmptyState
import me.lvca.alba.app.ui.shared.SharedContributionPicker
import me.lvca.alba.app.ui.shared.SharedInvitations
import me.lvca.alba.app.ui.shared.sharedContributionCandidates
import me.lvca.alba.app.ui.transfers.BatchProgress
import me.lvca.alba.app.ui.transfers.TransferQueueCard
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
    val summaries = key(identity) { rememberCollectionPreviews(gallery.catalogItems, gallery.collections) }
    val sharedAlbums = remember(gallery.collections) { gallery.collections.filter { it.shared != null } }
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
    val inCollection = tab == GalleryTab.COLLECTIONS && (query.collectionId != null || query.offlineOnly || query.trashOnly)
    val collection = gallery.collections.firstOrNull { it.id == query.collectionId }
    val collectionTitle = when {
        query.trashOnly -> stringResource(if (query.origin == MediaOrigin.ICLOUD) R.string.icloud_recently_deleted else R.string.phone_trash)
        query.offlineOnly -> stringResource(R.string.offline)
        query.collectionId == GalleryQuery.FAVORITES -> stringResource(R.string.favorites)
        else -> collection?.localizedName() ?: SmartCollection.entries.firstOrNull { it.id == query.collectionId }?.localizedTitle() ?: stringResource(R.string.collection)
    }
    fun backInCollections() {
        if (inCollection) actions.changeQuery(GalleryQuery())
        else if (folder != null) folder = gallery.collections.firstOrNull { it.id == folder }?.parentId
        else if (collectionsPage != null) collectionsPage = null
        else switchTab(GalleryTab.LIBRARY)
    }
    val backEnabled = LocalPhotoTransition.current?.active != false && gallery.selection.isEmpty()
    val parentOf: (String) -> String? = { id -> gallery.collections.firstOrNull { it.id == id }?.parentId }
    val screen = if (tab == GalleryTab.COLLECTIONS) GalleryScreen(tab, collectionsPage, folder, GalleryScreen.albumOf(query))
        else GalleryScreen(tab)
    val libraryFrame = rememberGraphicsLayer()
    val title = if (inCollection) collectionTitle else if (tab == GalleryTab.COLLECTIONS && folder != null)
        gallery.collections.firstOrNull { it.id == folder }?.name ?: stringResource(R.string.albums) else stringResource(if (tab == GalleryTab.COLLECTIONS) collectionsPage?.title ?: tab.title else tab.title)
    val contextAccountLabel = stringResource(R.string.open_icloud_account)
    val signedIn = session.accountState as? ICloudAccountState.SignedIn
    val status = when {
        session.accountState == ICloudAccountState.Demo -> stringResource(R.string.library)
        session.accountState == ICloudAccountState.Restoring -> stringResource(R.string.restoring_icloud)
        signedIn?.status == SessionStatus.EXPIRED -> stringResource(R.string.sign_in_again_saved_photos_available)
        signedIn?.status == SessionStatus.PHOTOS_NOT_ENABLED -> stringResource(R.string.icloud_photos_is_not_active)
        session.network?.online == false || signedIn?.status == SessionStatus.OFFLINE -> stringResource(R.string.offline_saved_photos_available)
        session.isRefreshing -> stringResource(R.string.updating_library)
        signedIn?.status == SessionStatus.VERIFIED -> stringResource(R.string.icloud_photos)
        else -> stringResource(R.string.connect_to_icloud)
    }
    val canUpload = signedIn?.status == SessionStatus.VERIFIED || session.accountState == ICloudAccountState.Demo
    // Clearing filters keeps the place being browsed: an open collection, offline copies or a trash.
    val unfiltered = if (inCollection) GalleryQuery(origin = query.origin.takeIf { query.trashOnly }, collectionId = query.collectionId,
        offlineOnly = query.offlineOnly, trashOnly = query.trashOnly, sort = query.sort, date = query.date)
        else GalleryQuery(sort = query.sort, date = query.date)
    // Without an account or photos there is nothing to filter: offer connecting iCloud and the demo instead.
    val connectPrompt = session.accountState == ICloudAccountState.SignedOut && tab == GalleryTab.LIBRARY &&
        query == unfiltered && gallery.items.isEmpty() && !session.isRefreshing
    val hero = inCollection && collection != null && !collection.isFolder && !collection.id.startsWith("smart:") && !collection.id.startsWith("device-folder:")
    val visibleLevel = if (hero) albumLevel else level
    val changeLevel: (GridLevel) -> Unit = { if (hero) albumLevel = it else level = it }
    // Reserve the leading grid item before the catalog arrives, so lazy-key anchoring cannot hide it.
    val showSharedStrip = tab == GalleryTab.LIBRARY && query.collectionId == null &&
        query.fromDate == null && query.throughDate == null && !query.offlineOnly && !query.trashOnly
    val leadingItems = if (hero || showSharedStrip) 1 else 0
    val permissionPrompt = tab == GalleryTab.LIBRARY && !gallery.deviceMediaAccess && !permissionDismissed
    fun openShared(id: String) {
        libraryQuery = gallery.query
        tab = GalleryTab.COLLECTIONS
        collectionsPage = CollectionsPage.SHARED
        actions.clearSelection()
        collectionGrid.requestScrollToItem(0)
        actions.changeQuery(GalleryQuery(collectionId = id))
    }
    val uiDensity = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        val wide = maxWidth >= 600.dp
        val phonePhotos = !wide && tab == GalleryTab.LIBRARY
        // Selecting keeps the header where it is: resetting it would shift the grid under a drag-selecting finger.
        val collapseHeader = phonePhotos
        val selecting = gallery.selection.isNotEmpty()
        // Kept while selecting: the selection bar covers it, and removing it would shift the grid.
        val filterRow = !hero && (wide || tab != GalleryTab.LIBRARY) && (tab != GalleryTab.COLLECTIONS || inCollection)
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
        val monthOffsets = remember(sections, gallery.deviceMediaAccess, permissionDismissed, leadingItems, visibleLevel, query.date) {
            galleryMonthOffsets(sections, !gallery.deviceMediaAccess && !permissionDismissed, visibleLevel.dayHeaders, query.date, leadingItems)
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
        LaunchedEffect(collapseHeader) {
            if (!collapseHeader) { headerState.heightOffset = 0f; headerState.contentOffset = 0f }
        }
        var floatingBarHeight by remember { mutableStateOf(80.dp) }
        var headerHeight by remember { mutableStateOf(0.dp) }
        // The floating bar sits up to 16dp above the navigation bar; keep the grid's last row a clear 24dp above it.
        val bottomSpace = if (wide) 24.dp else floatingBarHeight + 40.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LaunchedEffect(bottomSpace) { onBottomSpaceChanged(bottomSpace) }
        Row(Modifier.fillMaxSize()) {
            if (wide) NavigationRail(Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).testTag("navigation-rail"), containerColor = MaterialTheme.colorScheme.surface) {
                Spacer(Modifier.height(24.dp))
                GalleryTab.entries.forEach { destination -> NavigationRailItem(selected = selectedTab() == destination,
                    onClick = { navigate(destination) }, icon = { Icon(destination.icon(), null, Modifier.size(24.dp)) }, label = { Text(stringResource(destination.title)) },
                    modifier = Modifier.testTag("tab-${destination.name}")) }
            }
            Scaffold(modifier = Modifier.weight(1f).nestedScroll(headerScroll), contentWindowInsets = WindowInsets(0, 0, 0, 0),
                containerColor = if (phonePhotos) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background,
                topBar = {
                    Column(Modifier.scrollingGalleryHeader(headerState, collapseHeader)
                        .onSizeChanged { headerHeight = with(uiDensity) { it.height.toDp() } }
                        // Ignore visibility: the viewer hides the bars, and the header must not jump as they return.
                        .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                        .drawBehind { drawRect(headerColor.value) }.testTag("gallery-header")
                        .then(if (collapseHeader && headerHidden) Modifier.clearAndSetSemantics {} else Modifier)) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (tab == GalleryTab.COLLECTIONS && (inCollection || folder != null || collectionsPage != null)) {
                                IconButton(onClick = ::backInCollections) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back_action)) }
                                if (!hero) Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                else Spacer(Modifier.weight(1f))
                            } else {
                                Box(Modifier.size(44.dp).clip(MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                                    Image(painterResource(R.drawable.alba_logo_mark), stringResource(R.string.brand_name), Modifier.size(44.dp))
                                }
                                if (!wide && tab == GalleryTab.LIBRARY) Spacer(Modifier.weight(1f))
                                else Row(Modifier.weight(1f).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (session.accountState == ICloudAccountState.Demo) DemoBadge()
                                }
                            }
                            if (hero) {
                                if (collection?.shared != null) IconButton(onClick = { sheet = GallerySheet.SharedManage }, enabled = canEdit) {
                                    Icon(Icons.Outlined.AccountCircle, stringResource(R.string.shared_manage))
                                }
                                IconButton(onClick = { sheet = GallerySheet.Filters }, modifier = Modifier.testTag("filter-button")) { Icon(painterResource(R.drawable.ic_filter), stringResource(R.string.filter_and_sort)) }
                                Box {
                                    IconButton(onClick = { albumMenu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.album_options)) }
                                    DropdownMenu(expanded = albumMenu, onDismissRequest = { albumMenu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.browse_by_date)) }, onClick = { albumMenu = false; sheet = GallerySheet.JumpToDate }, enabled = sections.isNotEmpty())
                                        if (collection?.shared != null) {
                                            DropdownMenuItem(text = { Text(stringResource(R.string.shared_details)) }, onClick = { albumMenu = false; sheet = GallerySheet.SharedInfo })
                                            if (collection.shared?.canContribute == true) DropdownMenuItem(text = { Text(stringResource(R.string.shared_add_photos)) }, onClick = { albumMenu = false; sheet = GallerySheet.SharedPicker }, enabled = canEdit)
                                        } else {
                                            DropdownMenuItem(text = { Text(stringResource(R.string.rename_album)) }, onClick = { albumMenu = false; sheet = collection?.id?.let(GallerySheet::RenameAlbum) }, enabled = canEdit)
                                            DropdownMenuItem(text = { Text(stringResource(R.string.delete_album)) }, onClick = { albumMenu = false; sheet = collection?.id?.let(GallerySheet::DeleteAlbum) }, enabled = canEdit)
                                        }
                                    }
                                }
                                TextButton(onClick = { if (gallery.selection.isEmpty()) gallery.items.firstOrNull()?.let { actions.toggleSelection(it.id) } else actions.clearSelection() }, enabled = gallery.items.isNotEmpty()) {
                                    Text(stringResource(if (gallery.selection.isEmpty()) R.string.select_items else R.string.cancel))
                                }
                            } else if (!(tab == GalleryTab.COLLECTIONS && (inCollection || folder != null || collectionsPage != null))) {
                                if (phonePhotos && gallery.selection.isEmpty()) IconButton(onClick = { sheet = GallerySheet.Filters },
                                    modifier = Modifier.testTag("gallery-menu-button")) {
                                    Icon(painterResource(R.drawable.ic_filter), stringResource(R.string.filter_and_sort))
                                }
                                IconButton(onClick = if (canUpload) actions::pickUpload else actions::openAccount) { Icon(Icons.Outlined.Add, stringResource(R.string.upload_photos)) }
                                IconButton(onClick = { sheet = GallerySheet.Activity }, modifier = Modifier.testTag("activity-button")) {
                                    BadgedBox(badge = { if (transfers.queue.any { it.state != TransferViewState.VERIFIED } || transfers.batches.any { it.status != BatchItemState.DONE }) Badge() }) {
                                        Icon(AlbaIcons.SwapVert, stringResource(R.string.uploads_and_activity))
                                    }
                                }
                                IconButton(onClick = actions::openAccount, modifier = Modifier.testTag("account-button").semantics { contentDescription = contextAccountLabel }) {
                                    InitialAvatar(signedIn?.appleId, 36.dp, color = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer, textStyle = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                        if (!hero && !connectPrompt && session.accountState != ICloudAccountState.Demo && (tab == GalleryTab.LIBRARY || session.accountState == ICloudAccountState.Restoring || signedIn?.status == SessionStatus.EXPIRED)) {
                            Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp).clickable(role = Role.Button, onClick = actions::openAccount))
                        }
                        if (session.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                        if (wide && selecting) SelectionActions(gallery, session, mediaBusy, actions)
                        else if (filterRow) {
                            if (tab == GalleryTab.SEARCH) GallerySearchBar(query.filename, { actions.changeQuery(query.copy(filename = it)) },
                                Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                val filterName = query.collectionId?.let { id -> if (id == GalleryQuery.FAVORITES) stringResource(R.string.favorites) else SmartCollection.entries.firstOrNull { it.id == id }?.localizedTitle() }
                                Text(listOfNotNull(query.origin?.let { if (it == MediaOrigin.DEVICE) stringResource(R.string.this_phone) else stringResource(R.string.icloud) } ?: stringResource(R.string.all_photos), filterName,
                                    if (query.fromDate != null || query.throughDate != null) stringResource(R.string.date_range) else null,
                                    if (query.date == GalleryDate.ADDED) stringResource(R.string.date_added) else null).joinToString(" · "),
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)

                                if (inCollection && collection != null && collection.isPersonal) {
                                    IconButton(enabled = canEdit, onClick = { sheet = GallerySheet.RenameAlbum(collection.id) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.rename_album)) }
                                    IconButton(enabled = canEdit, onClick = { sheet = GallerySheet.DeleteAlbum(collection.id) }) { Icon(AlbaIcons.DeleteOutline, stringResource(R.string.delete_album)) }
                                }
                                IconButton(enabled = sections.isNotEmpty(), onClick = { sheet = GallerySheet.JumpToDate }) { Icon(Icons.Outlined.DateRange, stringResource(R.string.browse_by_date)) }
                                IconButton(onClick = { sheet = GallerySheet.Filters }, modifier = Modifier.testTag("filter-button")) { Icon(painterResource(R.drawable.ic_filter), stringResource(R.string.filter_and_sort)) }
                            }
                        }
                    }
                }) { padding ->
                val statusOverlap = (WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding() -
                    padding.calculateTopPadding()).coerceAtLeast(0.dp)
                Box(Modifier.fillMaxSize().padding(padding)) {
                    GalleryScreenHost(screen, parentOf, backEnabled, onBack = {
                        if (tab == GalleryTab.COLLECTIONS) backInCollections() else switchTab(GalleryTab.LIBRARY)
                    }, libraryFrame = libraryFrame, faded = { tabFade.switched },
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = tabFade.alpha }) { shown ->
                    if (shown.tab == GalleryTab.COLLECTIONS && shown.album == null) PullToRefreshBox(isRefreshing = session.isRefreshing, onRefresh = actions::refresh) {
                        CollectionsScreen(gallery, canEdit, previewRetryVersion, shown.folder, shown.page, summaries,
                            if (shown.page == null && shown.folder == null) collectionScroll else rememberLazyGridState(), actions::requestPreview,
                            openPage = { collectionsPage = it },
                            openFolder = { folder = it }, openCollection = { collectionGrid.requestScrollToItem(0); actions.changeQuery(GalleryQuery(collectionId = it)) },
                            openOffline = { actions.changeQuery(GalleryQuery(offlineOnly = true)) }, openTrash = { actions.changeQuery(GalleryQuery(origin = MediaOrigin.DEVICE, trashOnly = true)) },
                            openCloudTrash = { actions.changeQuery(GalleryQuery(origin = MediaOrigin.ICLOUD, trashOnly = true)) }, createAlbum = { sheet = GallerySheet.CreateAlbum },
                            createSharedAlbum = { sheet = GallerySheet.CreateSharedAlbum }, openSharedInvitations = { sheet = GallerySheet.SharedInvitations },
                            requestPhotos = actions::requestDevicePhotos, bottomSpace = bottomSpace)
                    }
                    else if (shown.tab == GalleryTab.SEARCH && query.isBlankSearch) {
                        SearchLanding(bottomSpace = bottomSpace, onDates = { sheet = GallerySheet.Filters }, choose = { actions.changeQuery(query.copy(collectionId = it)) })
                    } else {
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
                            counts = PhotoCounts(gallery.cloudCount, gallery.deviceCount),
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
                            onDismissPermissionPrompt = { permissionDismissed = true },
                            actions = actions,
                            flat = hero,
                            onAddMedia = if (hero && collection?.shared?.canContribute == true && canEdit) ({ sheet = GallerySheet.SharedPicker }) else null,
                            leading = if (hero) ({
                                AlbumHero(requireNotNull(collection), summaries[collection.id], previewRetryVersion, actions::requestPreview,
                                    activity = if (collection.shared?.generation == SharedAlbumGeneration.MODERN) ({ sheet = GallerySheet.SharedActivity }) else null,
                                    view = gallery.items.firstOrNull()?.let { first -> { actions.openMedia(first.id) } },
                                    info = if (collection.shared != null) ({ sheet = GallerySheet.SharedInfo }) else null)
                                // An empty shared album the person can add to prompts the first steps under its hero.
                                val addable = canEdit && collection.shared?.canContribute == true
                                if (addable && gallery.items.isEmpty() && !session.isRefreshing) {
                                    SharedAlbumEmptyState({ sheet = GallerySheet.SharedPicker },
                                        if (collection.shared?.canManage == true) ({ sheet = GallerySheet.SharedManage }) else null)
                                }
                            }) else if (showSharedStrip) ({
                                SharedAlbumsStrip(sharedAlbums, summaries, previewRetryVersion, actions::requestPreview, ::openShared,
                                    seeAll = { libraryQuery = gallery.query; tab = GalleryTab.COLLECTIONS; collectionsPage = CollectionsPage.SHARED; actions.clearSelection(); actions.changeQuery(GalleryQuery()) })
                            }) else null,
                        )
                    }
                    }
                    if (collapseHeader) FloatingGridDate(visibleMonth.takeUnless { selecting },
                        dates = { sheet = GallerySheet.JumpToDate }, modifier = Modifier.align(Alignment.TopCenter).padding(top = statusOverlap).zIndex(1f))
                    // Selection swaps the navigation for the actions, each sliding along the bottom edge.
                    val barSlide = if (reducedMotion) snap<IntOffset>() else tween(GalleryFeel.CONTEXT_BAR_SLIDE_MILLIS)
                    if (!wide) androidx.compose.animation.AnimatedVisibility(selecting, Modifier.align(Alignment.BottomCenter),
                        enter = slideInVertically(barSlide) { it }, exit = slideOutVertically(barSlide) { it }) {
                        Surface(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)
                            .onSizeChanged { floatingBarHeight = with(uiDensity) { it.height.toDp() } }, shape = MaterialTheme.shapes.extraLarge, tonalElevation = 8.dp, shadowElevation = 8.dp) {
                            SelectionActions(gallery, session, mediaBusy, actions)
                        }
                    }
                    if (!wide) androidx.compose.animation.AnimatedVisibility(!selecting, Modifier.align(Alignment.BottomCenter),
                        enter = slideInVertically(barSlide) { it }, exit = slideOutVertically(barSlide) { it }) {
                        // It hides behind an open keyboard; keep the grid's bottom space at its last real height.
                        FloatingGalleryNavigation(selectedTab(), { navigate(it) },
                            Modifier.navigationBarsPadding().padding(bottom = 16.dp)
                                .onSizeChanged { if (it.height > 0) floatingBarHeight = with(uiDensity) { it.height.toDp() } })
                    }
                }
            }
        }
        if (phonePhotos) GalleryStatusBarScrim({ headerColor.value }, Modifier.align(Alignment.TopCenter).zIndex(2f))
        // The contextual bar replaces the header in place, over it, so the grid never moves when selection starts.
        SelectionTopBar(gallery.selection.size, minHeight = if (wide) 0.dp else headerHeight, reducedMotion = reducedMotion,
            selectAll = actions::selectAllShown, clear = actions::clearSelection,
            modifier = Modifier.align(Alignment.TopCenter).padding(start = if (wide) 80.dp else 0.dp).zIndex(3f))
    }
    val currentGrid = when (tab) { GalleryTab.LIBRARY -> libraryGrid; GalleryTab.COLLECTIONS -> collectionGrid; GalleryTab.SEARCH -> searchGrid }
    if (sheet == GallerySheet.JumpToDate) {
        val initialIndex = remember { currentGrid.firstVisibleItemIndex }
        GalleryDateSheet(gallery.sections, initialIndex, days = visibleLevel.dayHeaders, date = query.date,
            permissionHeader = permissionPrompt, leadingItems = leadingItems, showDateHeaders = !hero,
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
            Button(onClick = if (canUpload) actions::pickUpload else { { sheet = null; actions.openAccount() } },
                modifier = Modifier.fillMaxWidth().testTag("upload-picker-button")) { Icon(AlbaIcons.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.upload_photos)) }
            OutlinedButton(onClick = { sheet = null; actions.openAccount() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_storage_and_backup)) }
            if (transfers.queue.isEmpty() && transfers.batches.isEmpty()) Text(stringResource(R.string.no_transfers_yet), Modifier.padding(vertical = 16.dp))
            if (transfers.queue.isNotEmpty()) TransferQueueCard(transfers.queue, actions::continuePastUnresolved, demo = session.accountState == ICloudAccountState.Demo)
            BatchProgress(transfers.batches, gallery.items, actions::retryBatch, actions::cancelDownloadBatch)
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

/** The library's totals, shown under the grid. */
internal data class PhotoCounts(val cloud: Int, val device: Int)

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
    val openOrToggle: (String) -> Unit = remember(grid, anchor, actions, selecting) {
        { id ->
            if (!selecting) {
                anchor.visibleWhenOpened = grid.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }
                actions.openMedia(id)
            } else actions.toggleSelection(id)
        }
    }
    val scope = rememberCoroutineScope()
    val reducedMotion = reduceGalleryMotion()
    val pinch = remember(grid) { GridPinch(scope) }
    SideEffect {
        pinch.level = level; pinch.grid = grid; pinch.reducedMotion = reducedMotion
        pinch.mediaKeys = positions.keys; pinch.indexIn = gridIndexOf; pinch.commit = onLevelChange
    }
    // A level change lays the grid out afresh at its anchor; animating every tile's placement would fight that.
    var placementAnimates by remember(level) { mutableStateOf(false) }
    LaunchedEffect(level) { withFrameNanos {}; withFrameNanos {}; placementAnimates = true }
    val checkboxEnter = if (reducedMotion) EnterTransition.None else
        scaleIn(tween(GalleryFeel.SELECTION_MODE_MILLIS, easing = FastOutSlowInEasing)) +
            fadeIn(tween(GalleryFeel.CHECK_FADE_IN_MILLIS, delayMillis = GalleryFeel.CHECK_FADE_IN_DELAY_MILLIS))
    val checkboxExit = if (reducedMotion) ExitTransition.None else fadeOut(tween(GalleryFeel.CHECK_FADE_OUT_MILLIS))

    /** The grid's items at [shown]; [incoming] marks the next level laid out underneath during a pinch. */
    fun LazyGridScope.photoItems(shown: GridLevel, incoming: Boolean) {
        val posed = { key: Any -> Modifier.pinchPose(pinch, key, incoming) }
        if (showPermissionPrompt) item(key = "permission", span = { GridItemSpan(maxLineSpan) }) {
            AlbaCard(posed("permission").padding(horizontal = 16.dp, vertical = 12.dp).testTag("phone-photo-prompt"),
                color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(AlbaIcons.PhoneAndroid, null, Modifier.padding(top = 2.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.include_photos_from_this_phone), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.phone_photo_prompt_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton(onClick = onDismissPermissionPrompt) { Text(stringResource(R.string.not_now)) }
                            Button(onClick = actions::requestDevicePhotos) { Text(stringResource(R.string.phone_photo_prompt_action)) }
                        }
                    }
                }
            }
        }
        if (leading != null) item(key = "gallery-leading", span = { GridItemSpan(maxLineSpan) }) { Column(posed("gallery-leading")) { leading() } }
        if (items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (isRefreshing) { CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.loading_photos)) }
                // Where photos can be added, the album's leading item shows the first steps instead.
                else if (query.collectionId?.startsWith("shared:") == true) { if (onAddMedia == null) Text(stringResource(R.string.shared_album_empty)) }
                else if (query.trashOnly) Text(stringResource(R.string.trash_empty))
                else if (connectPrompt) ConnectLibrary(connect = actions::openAccount, demo = actions::startDemo)
                else {
                    EmptyLibrary()
                    if (query != unfiltered) TextButton(onClick = { actions.changeQuery(unfiltered) }) { Text(stringResource(R.string.clear_filters)) }
                }
            }
        }
        groupsFor(shown).forEach { (section, groups) ->
            val month = section.month
            if (!flat) item(key = "month-$month", span = { GridItemSpan(maxLineSpan) }) {
                Row(posed("month-$month"), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { MonthHeader(month, section.items.size) }
                    val selectionLabel = stringResource(R.string.label_with_detail, stringResource(R.string.select_all_shown), monthLabel(month))
                    AnimatedVisibility(selecting, Modifier.withoutHeight(), enter = checkboxEnter, exit = checkboxExit) {
                        HeaderSelectionCircle(section.items.all { it.id in selection }, selectionLabel,
                            { actions.selectItems(section.items.map { it.id }.toSet(), it) }, Modifier.padding(end = 12.dp))
                    }
                }
            }
            groups.forEach { (day, photos) ->
            if (day != null) item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) {
                Row(posed("day-$day").fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dateLabel(day), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    val selectionLabel = stringResource(R.string.label_with_detail, stringResource(R.string.select_all_shown), dateLabel(day))
                    AnimatedVisibility(selecting, Modifier.withoutHeight(), enter = checkboxEnter, exit = checkboxExit) {
                        HeaderSelectionCircle(photos.all { it.id in selection }, selectionLabel,
                            { checked -> actions.selectItems(photos.map { it.id }.toSet(), checked) })
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
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, stringResource(R.string.shared_add_photos), Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary) }
            }
        }
        if (items.isNotEmpty()) item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
            Column(posed("summary").fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(itemCount(items.size), style = MaterialTheme.typography.bodyMedium)
                if (query.collectionId?.startsWith(SharedMediaIdentity.PREFIX) != true) Text(stringResource(R.string.cloud_phone_counts, pluralStringResource(R.plurals.cloud_photo_count, counts.cloud, counts.cloud), stringResource(R.string.phone_photo_count, counts.device)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val smallestWidth = LocalConfiguration.current.smallestScreenWidthDp.toFloat()
    val gridWidth = maxWidth.value
    val columns = level.columns(smallestWidth, gridWidth)
    val spacing = Arrangement.spacedBy(GalleryFeel.GRID_SPACING)
    pinch.target?.let { target ->
        // The next level, laid out with the anchor's row at the same top. It draws only what the current grid cannot.
        val incomingGrid = remember(target) { LazyGridState().apply { requestScrollToItem(pinch.incomingIndex, pinch.incomingOffset) } }
        SideEffect { pinch.incomingGrid = incomingGrid }
        LaunchedEffect(incomingGrid) {
            pinch.measured(snapshotFlow { incomingGrid.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() })
        }
        CompositionLocalProvider(LocalPhotoTransition provides null) {
            LazyVerticalGrid(GridCells.Fixed(target.columns(smallestWidth, gridWidth)), state = incomingGrid, userScrollEnabled = false,
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
    val scrollLabels = remember(sections, offsets, locale) {
        sections.mapIndexed { i, s -> GalleryScrollLabel(offsets[i], monthLabel(s.month, abbreviated = true, locale = locale), s.month.year) }
    }
    val pixelDensity = LocalDensity.current
    val fullSpans = remember(groupedSections, showPermissionPrompt, leadingItems, flat, onAddMedia != null, pixelDensity) {
        with(pixelDensity) {
            buildMap<Int, Float> {
                var index = 0
                if (showPermissionPrompt) put(index++, 190.dp.toPx())
                if (leadingItems > 0) put(index++, (if (flat) 420.dp else 280.dp).toPx())
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

@Composable
private fun SearchLanding(bottomSpace: Dp, onDates: () -> Unit, choose: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).padding(bottom = bottomSpace)) {
        Text(stringResource(R.string.find_a_moment), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.search_by_filename_or_narrow_your_library_by_date_and_media_type), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        CollectionRow(stringResource(R.string.capture_date), stringResource(R.string.choose_a_date_range), icon = { Icon(Icons.Outlined.DateRange, null) }, onClick = onDates)
        CollectionRow(stringResource(R.string.favorites), stringResource(R.string.photos_you_ve_marked_with_a_heart), icon = { ICloudIcon(R.drawable.icloud_favorites) }, onClick = { choose(GalleryQuery.FAVORITES) })
        SmartCollection.entries.take(3).forEach { smart -> CollectionRow(smart.localizedTitle(), null, icon = { ICloudIcon(smart.icon()) }, onClick = { choose(smart.id) }) }
    }
}

/**
 * A date header's select-all control: the same check circle as a photo's, as in Google Photos. Compose extends its
 * touch area to the minimum touch target.
 */
@Composable
private fun HeaderSelectionCircle(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    SelectionCircle(checked, onPhoto = false, modifier = modifier
        .toggleable(checked, interactionSource = null, indication = ripple(bounded = false, radius = 20.dp), role = Role.Checkbox,
            onValueChange = onCheckedChange)
        .semantics { contentDescription = label })
}

/**
 * Takes no height in its row, drawing centred on it: entering selection must never make a date header taller and move
 * the photo under the finger.
 */
private fun Modifier.withoutHeight(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    layout(placeable.width, 0) { placeable.place(0, -placeable.height / 2) }
}
