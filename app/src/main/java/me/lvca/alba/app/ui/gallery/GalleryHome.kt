package me.lvca.alba.app.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.distinctUntilChanged
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
import me.lvca.alba.app.ui.shared.CreateSharedAlbumDialog
import me.lvca.alba.app.ui.shared.SharedAlbumActivity
import me.lvca.alba.app.ui.shared.SharedAlbumControls
import me.lvca.alba.app.ui.shared.SharedAlbumDetails
import me.lvca.alba.app.ui.shared.SharedContributionPicker
import me.lvca.alba.app.ui.shared.SharedInvitations
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
internal class GridAnchor(var mediaId: String? = null, var offset: Int = 0, restoring: Boolean = false) {
    var restoring by mutableStateOf(restoring && mediaId != null)

    companion object {
        val Saver = listSaver<GridAnchor, Any?>(
            save = { listOf(it.mediaId, it.offset) },
            restore = { GridAnchor(it[0] as String?, it[1] as Int, restoring = true) })
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
                ?.let { (it.key as String) to (-it.offset.y).coerceAtLeast(0) }
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
    var density by rememberSaveable { mutableIntStateOf(100) }
    var albumDensity by rememberSaveable { mutableIntStateOf(80) }
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
    val collectionPageScroll = rememberLazyGridState()
    val summaries = key(identity) { rememberCollectionPreviews(gallery.catalogItems, gallery.collections) }
    val sharedAlbums = remember(gallery.collections) { gallery.collections.filter { it.shared != null } }
    fun navigate(next: GalleryTab) {
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
        else navigate(GalleryTab.LIBRARY)
    }
    BackHandler(enabled = LocalPhotoTransition.current?.active != false && gallery.selection.isEmpty() && (tab != GalleryTab.LIBRARY)) {
        if (tab == GalleryTab.COLLECTIONS) backInCollections() else navigate(GalleryTab.LIBRARY)
    }
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
    val visibleDensity = if (hero) albumDensity else density
    val changeDensity: (Int) -> Unit = { if (hero) albumDensity = it else density = it }
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
    BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        val wide = maxWidth >= 600.dp
        val uiDensity = LocalDensity.current
        val phonePhotos = !wide && tab == GalleryTab.LIBRARY
        val collapseHeader = phonePhotos && gallery.selection.isEmpty()
        val headerState = rememberTopAppBarState()
        // A short grid can fit after collapse; it must still allow a reverse drag to restore the header.
        val collapseAllowed by rememberUpdatedState(collapseHeader)
        val headerScroll = TopAppBarDefaults.enterAlwaysScrollBehavior(headerState, canScroll = {
            collapseAllowed && (headerState.heightOffset < 0f || libraryGrid.canScrollForward || libraryGrid.canScrollBackward)
        },
            snapAnimationSpec = spring(dampingRatio = 1f, stiffness = 400f))
        val headerHidden by remember { derivedStateOf { headerState.collapsedFraction >= .99f } }
        // Returning the header is independent of reaching the beginning of the grid.
        val galleryScrolled by remember(libraryGrid, headerState) {
            derivedStateOf { libraryGrid.canScrollBackward || headerState.heightOffset < -.5f }
        }
        val reducedMotion = reduceGalleryMotion()
        val headerColor = animateColorAsState(
            if (phonePhotos) {
                if (galleryScrolled) MaterialTheme.colorScheme.galleryOverlay else MaterialTheme.colorScheme.surface
            } else MaterialTheme.colorScheme.background,
            animationSpec = if (reducedMotion) snap() else tween(250, easing = galleryColorEase), label = "gallery-header-surface")
        val sections = gallery.sections
        val monthOffsets = remember(sections, gallery.deviceMediaAccess, permissionDismissed, leadingItems, visibleDensity, query.date) {
            galleryMonthOffsets(sections, !gallery.deviceMediaAccess && !permissionDismissed, visibleDensity >= 160, query.date, leadingItems)
        }
        val visibleMonth by remember(libraryGrid, sections, monthOffsets) {
            derivedStateOf {
                val first = libraryGrid.layoutInfo.visibleItemsInfo.firstOrNull {
                    it.offset.y + it.size.height > 0
                }
                // A month farther down the viewport must not label the opening featured section.
                if (!galleryScrolled || first == null || first.index < (monthOffsets.firstOrNull() ?: Int.MAX_VALUE)) null
                else sections.getOrNull(monthOffsets.indexOfLast { it <= first.index })?.month
            }
        }
        LaunchedEffect(collapseHeader) {
            if (!collapseHeader) { headerState.heightOffset = 0f; headerState.contentOffset = 0f }
        }
        var floatingBarHeight by remember { mutableStateOf(80.dp) }
        // The floating bar sits up to 16dp above the navigation bar; keep the grid's last row a clear 24dp above it.
        val bottomSpace = if (wide) 24.dp else floatingBarHeight + 40.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LaunchedEffect(bottomSpace) { onBottomSpaceChanged(bottomSpace) }
        Row(Modifier.fillMaxSize()) {
            if (wide) NavigationRail(Modifier.statusBarsPadding().testTag("navigation-rail"), containerColor = MaterialTheme.colorScheme.surface) {
                Spacer(Modifier.height(24.dp))
                GalleryTab.entries.forEach { destination -> NavigationRailItem(selected = tab == destination,
                    onClick = { navigate(destination) }, icon = { Icon(destination.icon(), null, Modifier.size(24.dp)) }, label = { Text(stringResource(destination.title)) },
                    modifier = Modifier.testTag("tab-${destination.name}")) }
            }
            Scaffold(modifier = Modifier.weight(1f).nestedScroll(headerScroll.nestedScrollConnection), contentWindowInsets = WindowInsets(0, 0, 0, 0),
                containerColor = if (phonePhotos) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background,
                topBar = {
                    Column(Modifier.scrollingGalleryHeader(headerState, collapseHeader).statusBarsPadding()
                        .drawBehind { drawRect(headerColor.value) }.testTag("gallery-header")
                        .then(if (collapseHeader && headerHidden) Modifier.clearAndSetSemantics {} else Modifier)) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (tab == GalleryTab.COLLECTIONS && (inCollection || folder != null || collectionsPage != null)) {
                                IconButton(onClick = ::backInCollections) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back_to_collections)) }
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
                        if (wide && gallery.selection.isNotEmpty()) SelectionActions(gallery, session, mediaBusy, actions)
                        else if (!hero && gallery.selection.isEmpty() && (wide || tab != GalleryTab.LIBRARY) && (tab != GalleryTab.COLLECTIONS || inCollection)) {
                            if (tab == GalleryTab.SEARCH) {
                                OutlinedTextField(query.filename, { actions.changeQuery(query.copy(filename = it)) },
                                    label = { Text(stringResource(R.string.search_filenames)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                    trailingIcon = { if (query.filename.isNotEmpty()) IconButton(onClick = { actions.changeQuery(query.copy(filename = "")) }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear_search)) } },
                                    singleLine = true, shape = MaterialTheme.shapes.extraLarge,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("search-field"))
                            }
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
                val statusOverlap = (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() -
                    padding.calculateTopPadding()).coerceAtLeast(0.dp)
                Box(Modifier.fillMaxSize().padding(padding)) {
                    if (tab == GalleryTab.COLLECTIONS && !inCollection) PullToRefreshBox(isRefreshing = session.isRefreshing, onRefresh = actions::refresh) {
                        CollectionsScreen(gallery, canEdit, previewRetryVersion, folder, collectionsPage, summaries,
                            if (collectionsPage == null) collectionScroll else collectionPageScroll, actions::requestPreview,
                            openPage = { collectionsPage = it; scope.launch { collectionPageScroll.scrollToItem(0) } },
                            openFolder = { folder = it }, openCollection = { collectionGrid.requestScrollToItem(0); actions.changeQuery(GalleryQuery(collectionId = it)) },
                            openOffline = { actions.changeQuery(GalleryQuery(offlineOnly = true)) }, openTrash = { actions.changeQuery(GalleryQuery(origin = MediaOrigin.DEVICE, trashOnly = true)) },
                            openCloudTrash = { actions.changeQuery(GalleryQuery(origin = MediaOrigin.ICLOUD, trashOnly = true)) }, createAlbum = { sheet = GallerySheet.CreateAlbum },
                            createSharedAlbum = { sheet = GallerySheet.CreateSharedAlbum }, openSharedInvitations = { sheet = GallerySheet.SharedInvitations },
                            requestPhotos = actions::requestDevicePhotos, bottomSpace = bottomSpace)
                    }
                    else if (tab == GalleryTab.SEARCH && query.filename.isBlank() && query.fromDate == null && query.throughDate == null && query.collectionId == null) {
                        SearchLanding(bottomSpace = bottomSpace, onDates = { sheet = GallerySheet.Filters }, choose = { actions.changeQuery(query.copy(collectionId = it)) })
                    } else PullToRefreshBox(isRefreshing = session.isRefreshing, onRefresh = actions::refresh) {
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
                            grid = when (tab) { GalleryTab.LIBRARY -> libraryGrid; GalleryTab.COLLECTIONS -> collectionGrid; GalleryTab.SEARCH -> searchGrid },
                            anchor = when (tab) { GalleryTab.LIBRARY -> libraryAnchor; GalleryTab.COLLECTIONS -> collectionAnchor; GalleryTab.SEARCH -> searchAnchor },
                            density = visibleDensity,
                            onDensityChange = changeDensity,
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
                            }) else if (showSharedStrip) ({
                                SharedAlbumsStrip(sharedAlbums, summaries, previewRetryVersion, actions::requestPreview, ::openShared,
                                    seeAll = { libraryQuery = gallery.query; tab = GalleryTab.COLLECTIONS; collectionsPage = CollectionsPage.SHARED; actions.clearSelection(); actions.changeQuery(GalleryQuery()) })
                            }) else null,
                        )
                    }
                    if (collapseHeader) FloatingGridDate(visibleMonth,
                        dates = { sheet = GallerySheet.JumpToDate }, modifier = Modifier.align(Alignment.TopCenter).padding(top = statusOverlap).zIndex(1f))
                    if (!wide && gallery.selection.isNotEmpty()) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)
                        .onSizeChanged { floatingBarHeight = with(uiDensity) { it.height.toDp() } }, shape = MaterialTheme.shapes.extraLarge, tonalElevation = 8.dp, shadowElevation = 8.dp) {
                        SelectionActions(gallery, session, mediaBusy, actions)
                    }
                    else if (!wide) FloatingGalleryNavigation(tab, { navigate(it) },
                        Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(bottom = 16.dp)
                            .onSizeChanged { floatingBarHeight = with(uiDensity) { it.height.toDp() } })
                }
            }
        }
        if (phonePhotos) GalleryStatusBarScrim({ headerColor.value }, Modifier.align(Alignment.TopCenter).zIndex(2f))
    }
    val currentGrid = when (tab) { GalleryTab.LIBRARY -> libraryGrid; GalleryTab.COLLECTIONS -> collectionGrid; GalleryTab.SEARCH -> searchGrid }
    if (sheet == GallerySheet.JumpToDate) {
        val initialIndex = remember { currentGrid.firstVisibleItemIndex }
        GalleryDateSheet(gallery.sections, initialIndex, days = visibleDensity >= 160, date = query.date,
            permissionHeader = permissionPrompt, leadingItems = leadingItems, showDateHeaders = !hero,
            dismiss = { sheet = null }, jump = { index -> scope.launch { currentGrid.scrollToItem(index) } })
    }
    if (sheet == GallerySheet.Filters) GalleryFilterSheet(gallery, actions::changeQuery, actions::requestDevicePhotos, actions::refresh, visibleDensity, changeDensity, dismiss = { sheet = null })
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
    if (sheet == GallerySheet.SharedPicker && collection?.shared != null) SharedContributionPicker(gallery.catalogItems.filter {
        !it.isShared && !it.isTrashed &&
            (if (collection.shared?.generation == SharedAlbumGeneration.LEGACY || it.origin == MediaOrigin.DEVICE) supportsLegacySharedUpload(it.kind, it.mimeType) else true) },
        preview = actions::requestPreview, dismiss = { sheet = null }, contribute = { ids -> actions.contributeToSharedAlbum(collection.id, ids); sheet = null },
        legacy = collection.shared?.generation == SharedAlbumGeneration.LEGACY, requestPhotos = actions::requestDevicePhotos)
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
 * A grid of photos grouped by month, and by day at large tile sizes. Takes the lists it shows rather than the whole
 * gallery state, and remembers everything derived from them, so a message or progress change does not redo that work.
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
    density: Int,
    onDensityChange: (Int) -> Unit,
    bottomSpace: Dp,
    showPermissionPrompt: Boolean,
    onDismissPermissionPrompt: () -> Unit,
    actions: BrowseActions,
    leading: (@Composable () -> Unit)? = null,
    flat: Boolean = false,
    onAddMedia: (() -> Unit)? = null,
) {
    val leadingItems = if (leading != null) 1 else 0
    val dayHeaders = density >= 160
    val date = query.date
    val mediaIds = remember(items) { items.map { it.id } }
    val positions = remember(mediaIds) { mediaIds.withIndex().associate { it.value to it.index } }
    val groupedSections = remember(sections, flat, dayHeaders, date) {
        sections.map { section ->
            section to if (!flat && dayHeaders) section.items.groupBy { galleryDay(it, date) } else mapOf(null to section.items)
        }
    }
    val gridIndexOf: (String, Int) -> Int = remember(sections, showPermissionPrompt, date, leadingItems, flat) {
        { id, tileSize -> galleryPosition(sections, showPermissionPrompt, tileSize >= 160, date, id, leadingItems, showDateHeaders = !flat) }
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
    KeepGridAnchor(grid, anchor, positions) { id -> gridIndexOf(id, density) }
    val returningId = LocalPhotoTransition.current?.returningMediaId
    var visibleWhenOpened by rememberSaveable { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(returningId) {
        if (returningId != null && returningId !in visibleWhenOpened) {
            grid.requestScrollToItem(gridIndexOf(returningId, density))
        }
    }
    val selecting = selection.isNotEmpty()
    val openOrToggle: (String) -> Unit = remember(grid, actions, selecting) {
        { id ->
            if (!selecting) {
                visibleWhenOpened = grid.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }
                actions.openMedia(id)
            } else actions.toggleSelection(id)
        }
    }
    Box(Modifier.fillMaxSize()) {
    LookaheadScope {
    LazyVerticalGrid(columns = GridCells.Adaptive(density.dp), state = grid, modifier = Modifier.fillMaxSize()
        .galleryGestures(grid, mediaIds, selection, density, onDensityChange, gridIndexOf, actions::selectItems).testTag("gallery-grid"),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = bottomSpace)) {
        if (showPermissionPrompt) item(key = "permission", span = { GridItemSpan(maxLineSpan) }) {
            AlbaCard(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("phone-photo-prompt"),
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
        if (leading != null) item(key = "gallery-leading", span = { GridItemSpan(maxLineSpan) }) { Column { leading() } }
        if (items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (isRefreshing) { CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.loading_photos)) }
                else if (query.collectionId?.startsWith("shared:") == true) Text(stringResource(R.string.shared_album_empty))
                else if (query.trashOnly) Text(stringResource(R.string.trash_empty))
                else if (connectPrompt) ConnectLibrary(connect = actions::openAccount, demo = actions::startDemo)
                else {
                    EmptyLibrary()
                    if (query != unfiltered) TextButton(onClick = { actions.changeQuery(unfiltered) }) { Text(stringResource(R.string.clear_filters)) }
                }
            }
        }
        groupedSections.forEach { (section, groups) ->
            val month = section.month
            if (!flat) item(key = "month-$month", span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { MonthHeader(month, section.items.size) }
                    val selectionLabel = stringResource(R.string.label_with_detail, stringResource(R.string.select_all_shown), monthLabel(month))
                    if (selecting) Checkbox(checked = section.items.all { it.id in selection },
                        onCheckedChange = { actions.selectItems(section.items.map { it.id }.toSet(), it) }, modifier = Modifier.padding(end = 12.dp).semantics { contentDescription = selectionLabel })
                }
            }
            groups.forEach { (day, photos) ->
            if (day != null) item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dateLabel(day), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    val selectionLabel = stringResource(R.string.label_with_detail, stringResource(R.string.select_all_shown), dateLabel(day))
                    if (selecting) Checkbox(photos.all { it.id in selection }, { checked -> actions.selectItems(photos.map { it.id }.toSet(), checked) },
                        modifier = Modifier.semantics { contentDescription = selectionLabel })
                }
            }
            items(photos, key = GalleryMedia::id) { media ->
                // Lazy placement animates reflow; bounds animate the actual thumbnail size.
                // Excluding the scrolling frame keeps ordinary swipes attached to the finger.
                Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                    placementSpec = spring(dampingRatio = 1f, stiffness = 450f))
                    .animateBounds(this@LookaheadScope,
                        boundsTransform = { _, _ -> spring(dampingRatio = 1f, stiffness = 450f) })) {
                    val id = media.id
                    MediaTile(
                        retryVersion = previewRetryVersion,
                        selected = id in selection,
                        selecting = selecting,
                        media = media,
                        onClick = { openOrToggle(id) },
                        onVisible = { actions.requestPreview(id) },
                        selectionAction = { actions.toggleSelection(id) },
                    )
                }
            }
            }
        }
        if (onAddMedia != null) item(key = "add-media") {
            Surface(onClick = onAddMedia, modifier = Modifier.aspectRatio(1f).testTag("album-add-media"),
                shape = MaterialTheme.shapes.tile, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, stringResource(R.string.shared_add_photos), Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary) }
            }
        }
        if (items.isNotEmpty()) item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(itemCount(items.size), style = MaterialTheme.typography.bodyMedium)
                if (query.collectionId?.startsWith(SharedMediaIdentity.PREFIX) != true) Text(stringResource(R.string.cloud_phone_counts, pluralStringResource(R.plurals.cloud_photo_count, counts.cloud, counts.cloud), stringResource(R.string.phone_photo_count, counts.device)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
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
    GalleryFastScroll(grid, scrollLabels, fullSpans, density, Modifier.align(Alignment.CenterEnd).fillMaxHeight(), bottomSpace)
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
