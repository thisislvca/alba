package dev.mela.app.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.layout.LookaheadScope
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import dev.mela.app.GalleryUiState
import dev.mela.app.R
import dev.mela.app.ui.theme.galleryOverlay
import dev.mela.engine.companion.BatchAction
import dev.mela.engine.model.*
import dev.mela.protocol.account.ICloudAccountState
import dev.mela.protocol.account.SessionStatus
import java.time.LocalDate
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

private val QuerySaver = listSaver<GalleryQuery, String>(
    save = { listOf(it.origin?.name.orEmpty(), it.collectionId.orEmpty(), it.filename,
        it.fromDate?.toString().orEmpty(), it.throughDate?.toString().orEmpty(), it.sort.name, it.offlineOnly.toString(), it.date.name, it.trashOnly.toString()) },
    restore = { GalleryQuery(it[0].takeIf(String::isNotEmpty)?.let(MediaOrigin::valueOf), it[1].takeIf(String::isNotEmpty),
        it[2], it[3].takeIf(String::isNotEmpty)?.let(LocalDate::parse), it[4].takeIf(String::isNotEmpty)?.let(LocalDate::parse),
        GallerySort.valueOf(it[5]), it[6].toBoolean(), it.getOrNull(7)?.let(GalleryDate::valueOf) ?: GalleryDate.CAPTURED, it.getOrNull(8)?.toBoolean() ?: false) })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryHome(
    state: GalleryUiState, actions: LibraryActions, change: (GalleryQuery) -> Unit,
    toggle: (String) -> Unit, selectAll: () -> Unit, clear: () -> Unit,
    batch: (BatchAction) -> Unit, retry: (String) -> Unit, refresh: () -> Unit,
    open: (String) -> Unit, preview: (String) -> Unit, requestPhotos: () -> Unit,
    upload: () -> Unit, continueUpload: (TransferId) -> Unit, account: () -> Unit,
    onBottomSpaceChanged: (Dp) -> Unit,
) {
    val identity = if (state.accountState == ICloudAccountState.Restoring) null else
        (state.accountState as? ICloudAccountState.SignedIn)?.appleId ?: state.cloudSourceLabel
    var tab by rememberSaveable { mutableStateOf(GalleryTab.LIBRARY) }
    var libraryQuery by rememberSaveable(stateSaver = QuerySaver) { mutableStateOf(GalleryQuery()) }
    var collectionQuery by rememberSaveable(stateSaver = QuerySaver) { mutableStateOf(GalleryQuery()) }
    var searchQuery by rememberSaveable(stateSaver = QuerySaver) { mutableStateOf(GalleryQuery()) }
    var collectionsPage by rememberSaveable { mutableStateOf<CollectionsPage?>(null) }
    var albumMenu by remember { mutableStateOf(false) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var density by rememberSaveable { mutableIntStateOf(100) }
    var albumDensity by rememberSaveable { mutableIntStateOf(80) }
    var jump by rememberSaveable { mutableStateOf(false) }
    var deleteAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var filters by rememberSaveable { mutableStateOf(false) }
    var activity by rememberSaveable { mutableStateOf(false) }
    var sharedInvitations by rememberSaveable { mutableStateOf(false) }
    var sharedPicker by rememberSaveable { mutableStateOf(false) }
    var sharedActivity by rememberSaveable { mutableStateOf(false) }
    var sharedManage by rememberSaveable { mutableStateOf(false) }
    var sharedInfo by rememberSaveable { mutableStateOf(false) }
    var editAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionDismissed by rememberSaveable { mutableStateOf(false) }
    var owner by rememberSaveable { mutableStateOf(identity) }
    LaunchedEffect(identity) {
        if (identity != null) {
            if (owner != null && owner != identity) {
                tab = GalleryTab.LIBRARY
                libraryQuery = GalleryQuery(); collectionQuery = GalleryQuery(); searchQuery = GalleryQuery()
                folder = null; collectionsPage = null; editAlbum = null; sharedInfo = false; sharedInvitations = false; sharedManage = false; sharedActivity = false; sharedPicker = false; deleteAlbum = null; jump = false; filters = false; activity = false
                change(GalleryQuery())
            }
            owner = identity
        }
    }
    val libraryGrid = rememberLazyGridState()
    val collectionGrid = rememberLazyGridState()
    val searchGrid = rememberLazyGridState()
    val collectionScroll = rememberLazyGridState()
    val collectionPageScroll = rememberLazyGridState()
    val summaries = key(identity) { rememberCollectionPreviews(state.catalogItems) }
    val sharedAlbums = remember(state.collections) { state.collections.filter { it.shared != null } }
    fun navigate(next: GalleryTab) {
        if (next == tab) return
        when (tab) {
            GalleryTab.LIBRARY -> libraryQuery = state.query
            GalleryTab.COLLECTIONS -> collectionQuery = state.query
            GalleryTab.SEARCH -> searchQuery = state.query
        }
        tab = next
        clear()
        change(when (next) {
            GalleryTab.LIBRARY -> libraryQuery
            GalleryTab.COLLECTIONS -> collectionQuery
            GalleryTab.SEARCH -> searchQuery
        })
    }
    val inCollection = tab == GalleryTab.COLLECTIONS && (state.query.collectionId != null || state.query.offlineOnly || state.query.trashOnly)
    val collection = state.collections.firstOrNull { it.id == state.query.collectionId }
    val collectionTitle = when {
        state.query.trashOnly -> stringResource(if (state.query.origin == MediaOrigin.ICLOUD) R.string.icloud_recently_deleted else R.string.phone_trash)
        state.query.offlineOnly -> stringResource(R.string.offline)
        state.query.collectionId == GalleryQuery.FAVORITES -> stringResource(R.string.favorites)
        else -> collection?.localizedName() ?: SmartCollection.entries.firstOrNull { it.id == state.query.collectionId }?.localizedTitle() ?: stringResource(R.string.collection)
    }
    fun backInCollections() {
        if (inCollection) change(GalleryQuery())
        else if (folder != null) folder = state.collections.firstOrNull { it.id == folder }?.parentId
        else if (collectionsPage != null) collectionsPage = null
        else navigate(GalleryTab.LIBRARY)
    }
    BackHandler(enabled = LocalPhotoTransition.current?.active != false && state.selection.isEmpty() && (tab != GalleryTab.LIBRARY)) {
        if (tab == GalleryTab.COLLECTIONS) backInCollections() else navigate(GalleryTab.LIBRARY)
    }
    val title = if (inCollection) collectionTitle else if (tab == GalleryTab.COLLECTIONS && folder != null)
        state.collections.firstOrNull { it.id == folder }?.name ?: stringResource(R.string.albums) else stringResource(if (tab == GalleryTab.COLLECTIONS) collectionsPage?.title ?: tab.title else tab.title)
    val contextAccountLabel = stringResource(R.string.open_icloud_account)
    val signedIn = state.accountState as? ICloudAccountState.SignedIn
    val status = when {
        state.accountState == ICloudAccountState.Demo -> stringResource(R.string.library)
        state.accountState == ICloudAccountState.Restoring -> stringResource(R.string.restoring_icloud)
        signedIn?.status == SessionStatus.EXPIRED -> stringResource(R.string.sign_in_again_saved_photos_available)
        signedIn?.status == SessionStatus.PHOTOS_NOT_ENABLED -> stringResource(R.string.icloud_photos_is_not_active)
        state.network?.online == false || signedIn?.status == SessionStatus.OFFLINE -> stringResource(R.string.offline_saved_photos_available)
        state.isRefreshing -> stringResource(R.string.updating_library)
        signedIn?.status == SessionStatus.VERIFIED -> stringResource(R.string.icloud_photos)
        else -> stringResource(R.string.connect_to_icloud)
    }
    val hero = inCollection && collection != null && !collection.isFolder && !collection.id.startsWith("smart:") && !collection.id.startsWith("device-folder:")
    val visibleDensity = if (hero) albumDensity else density
    val changeDensity: (Int) -> Unit = { if (hero) albumDensity = it else density = it }
    // Reserve the leading grid item before the catalog arrives, so lazy-key anchoring cannot hide it.
    val showSharedStrip = tab == GalleryTab.LIBRARY && state.query.collectionId == null &&
        state.query.fromDate == null && state.query.throughDate == null && !state.query.offlineOnly && !state.query.trashOnly
    val leadingItems = if (hero || showSharedStrip) 1 else 0
    fun openShared(id: String) {
        libraryQuery = state.query
        tab = GalleryTab.COLLECTIONS
        collectionsPage = CollectionsPage.SHARED
        clear()
        collectionGrid.requestScrollToItem(0)
        change(GalleryQuery(collectionId = id))
    }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        val wide = maxWidth >= 600.dp
        val uiDensity = LocalDensity.current
        val phonePhotos = !wide && tab == GalleryTab.LIBRARY
        val collapseHeader = phonePhotos && state.selection.isEmpty()
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
        val monthOffsets = remember(state.sections, state.deviceMediaAccess, permissionDismissed, leadingItems, visibleDensity, state.query.date) {
            galleryMonthOffsets(state.sections, !state.deviceMediaAccess && !permissionDismissed, visibleDensity >= 160, state.query.date, leadingItems)
        }
        val visibleMonth by remember(libraryGrid, state.sections, monthOffsets) {
            derivedStateOf {
                val first = libraryGrid.layoutInfo.visibleItemsInfo.firstOrNull {
                    it.offset.y + it.size.height > 0
                }
                // A month farther down the viewport must not label the opening featured section.
                if (!galleryScrolled || first == null || first.index < (monthOffsets.firstOrNull() ?: Int.MAX_VALUE)) null
                else state.sections.getOrNull(monthOffsets.indexOfLast { it <= first.index })?.month
            }
        }
        LaunchedEffect(collapseHeader) {
            if (!collapseHeader) { headerState.heightOffset = 0f; headerState.contentOffset = 0f }
        }
        var floatingBarHeight by remember { mutableStateOf(80.dp) }
        val bottomSpace = if (wide) 24.dp else floatingBarHeight + 28.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LaunchedEffect(bottomSpace) { onBottomSpaceChanged(bottomSpace) }
        Row(Modifier.fillMaxSize()) {
            if (wide) NavigationRail(Modifier.statusBarsPadding().testTag("navigation-rail"), containerColor = MaterialTheme.colorScheme.surface) {
                Spacer(Modifier.height(24.dp))
                GalleryTab.entries.forEach { destination -> NavigationRailItem(selected = tab == destination,
                    onClick = { navigate(destination) }, icon = { TabIcon(destination) }, label = { Text(stringResource(destination.title)) },
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
                                Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.alba_logo_mark), stringResource(R.string.brand_name), Modifier.size(44.dp))
                                }
                                if (!wide && tab == GalleryTab.LIBRARY) Spacer(Modifier.weight(1f))
                                else Row(Modifier.weight(1f).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (state.accountState == ICloudAccountState.Demo) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                        Text(stringResource(R.string.demo_badge), Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    }
                                }
                            }
                            if (hero) {
                                if (collection?.shared != null) IconButton(onClick = { sharedManage = true }, enabled = state.canEditLibrary()) {
                                    Icon(Icons.Outlined.AccountCircle, stringResource(R.string.shared_manage))
                                }
                                IconButton(onClick = { filters = true }, modifier = Modifier.testTag("filter-button")) { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_filter), stringResource(R.string.filter_and_sort)) }
                                Box {
                                    IconButton(onClick = { albumMenu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.album_options)) }
                                    DropdownMenu(expanded = albumMenu, onDismissRequest = { albumMenu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.browse_by_date)) }, onClick = { albumMenu = false; jump = true }, enabled = state.sections.isNotEmpty())
                                        if (collection?.shared != null) {
                                            DropdownMenuItem(text = { Text(stringResource(R.string.shared_details)) }, onClick = { albumMenu = false; sharedInfo = true })
                                            if (collection.shared?.canContribute == true) DropdownMenuItem(text = { Text(stringResource(R.string.shared_add_photos)) }, onClick = { albumMenu = false; sharedPicker = true }, enabled = state.canEditLibrary())
                                        } else {
                                            DropdownMenuItem(text = { Text(stringResource(R.string.rename_album)) }, onClick = { albumMenu = false; editAlbum = collection?.id }, enabled = state.canEditLibrary())
                                            DropdownMenuItem(text = { Text(stringResource(R.string.delete_album)) }, onClick = { albumMenu = false; deleteAlbum = collection?.id }, enabled = state.canEditLibrary())
                                        }
                                    }
                                }
                                TextButton(onClick = { if (state.selection.isEmpty()) state.items.firstOrNull()?.let { toggle(it.id) } else clear() }, enabled = state.items.isNotEmpty()) {
                                    Text(stringResource(if (state.selection.isEmpty()) R.string.select_items else R.string.cancel))
                                }
                            } else if (!(tab == GalleryTab.COLLECTIONS && (inCollection || folder != null || collectionsPage != null))) {
                                if (phonePhotos && state.selection.isEmpty()) IconButton(onClick = { filters = true },
                                    modifier = Modifier.testTag("gallery-menu-button")) {
                                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.filter_and_sort))
                                }
                                IconButton(onClick = if (signedIn?.status == SessionStatus.VERIFIED) upload else account) { Icon(Icons.Outlined.Add, stringResource(R.string.upload_photos)) }
                                IconButton(onClick = { activity = true }, modifier = Modifier.testTag("activity-button")) {
                                    BadgedBox(badge = { if (state.transferQueue.any { it.state != TransferViewState.VERIFIED } || state.batches.any { it.state != "DONE" }) Badge() }) {
                                        Icon(Icons.Outlined.Notifications, stringResource(R.string.uploads_and_activity))
                                    }
                                }
                                IconButton(onClick = account, modifier = Modifier.testTag("account-button").semantics { contentDescription = contextAccountLabel }) {
                                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(36.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            if (signedIn != null) Text(signedIn.appleId.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
                                            else Icon(Icons.Outlined.AccountCircle, null, Modifier.size(28.dp))
                                        }
                                    }
                                }
                            }
                        }
                        if (!hero && state.accountState != ICloudAccountState.Demo && (tab == GalleryTab.LIBRARY || state.accountState == ICloudAccountState.Restoring || signedIn?.status == SessionStatus.EXPIRED)) {
                            Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp).clickable(onClick = account))
                        }
                        if (state.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                        if (wide && state.selection.isNotEmpty()) SelectionActions(state, selectAll, clear, batch, actions)
                        else if (!hero && state.selection.isEmpty() && (wide || tab != GalleryTab.LIBRARY) && (tab != GalleryTab.COLLECTIONS || inCollection)) {
                            if (tab == GalleryTab.SEARCH) {
                                OutlinedTextField(state.query.filename, { change(state.query.copy(filename = it)) },
                                    label = { Text(stringResource(R.string.search_filenames)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                    trailingIcon = { if (state.query.filename.isNotEmpty()) IconButton(onClick = { change(state.query.copy(filename = "")) }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear_search)) } },
                                    singleLine = true, shape = RoundedCornerShape(28.dp),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("search-field"))
                            }
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                val filterName = state.query.collectionId?.let { id -> if (id == GalleryQuery.FAVORITES) stringResource(R.string.favorites) else SmartCollection.entries.firstOrNull { it.id == id }?.localizedTitle() }
                                Text(listOfNotNull(state.query.origin?.let { if (it == MediaOrigin.DEVICE) stringResource(R.string.this_phone) else stringResource(R.string.icloud) } ?: stringResource(R.string.all_photos), filterName,
                                    if (state.query.fromDate != null || state.query.throughDate != null) stringResource(R.string.date_range) else null,
                                    if (state.query.date == GalleryDate.ADDED) stringResource(R.string.date_added) else null).joinToString(" · "),
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)

                                if (inCollection && collection != null && collection.shared == null && !collection.id.startsWith("smart:") && !collection.id.startsWith("device-folder:")) {
                                    IconButton(enabled = state.canEditLibrary(), onClick = { editAlbum = collection.id }) { Icon(Icons.Outlined.Edit, stringResource(R.string.rename_album)) }
                                    IconButton(enabled = state.canEditLibrary(), onClick = { deleteAlbum = collection.id }) { Icon(MelaIcons.DeleteOutline, stringResource(R.string.delete_album)) }
                                }
                                IconButton(enabled = state.sections.isNotEmpty(), onClick = { jump = true }) { Icon(Icons.Outlined.DateRange, stringResource(R.string.browse_by_date)) }
                                IconButton(onClick = { filters = true }, modifier = Modifier.testTag("filter-button")) { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_filter), stringResource(R.string.filter_and_sort)) }
                            }
                        }
                    }
                }) { padding ->
                val statusOverlap = (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() -
                    padding.calculateTopPadding()).coerceAtLeast(0.dp)
                Box(Modifier.fillMaxSize().padding(padding)) {
                    if (tab == GalleryTab.COLLECTIONS && !inCollection) PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = refresh) { CollectionsScreen(state, folder, collectionsPage, summaries, if (collectionsPage == null) collectionScroll else collectionPageScroll, preview,
                        openPage = { collectionsPage = it; scope.launch { collectionPageScroll.scrollToItem(0) } },
                        openFolder = { folder = it }, openCollection = { collectionGrid.requestScrollToItem(0); change(GalleryQuery(collectionId = it)) },
                        openOffline = { change(GalleryQuery(offlineOnly = true)) }, openTrash = { change(GalleryQuery(origin = MediaOrigin.DEVICE, trashOnly = true)) },
                        openCloudTrash = { change(GalleryQuery(origin = MediaOrigin.ICLOUD, trashOnly = true)) }, createAlbum = { editAlbum = "new" }, createSharedAlbum = { editAlbum = "new-shared" }, openSharedInvitations = { sharedInvitations = true }, requestPhotos = requestPhotos, bottomSpace = bottomSpace) }
                    else if (tab == GalleryTab.SEARCH && state.query.filename.isBlank() && state.query.fromDate == null && state.query.throughDate == null && state.query.collectionId == null) {
                        SearchLanding(bottomSpace = bottomSpace, onDates = { filters = true }, choose = { change(state.query.copy(collectionId = it)) })
                    } else PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = refresh) {
                        PhotoGrid(state, when (tab) { GalleryTab.LIBRARY -> libraryGrid; GalleryTab.COLLECTIONS -> collectionGrid; GalleryTab.SEARCH -> searchGrid },
                            visibleDensity, changeDensity, actions, open, toggle, preview, bottomSpace = bottomSpace, permission = tab == GalleryTab.LIBRARY && !state.deviceMediaAccess && !permissionDismissed,
                            dismissPermission = { permissionDismissed = true }, requestPhotos = requestPhotos, clearFilters = { change(GalleryQuery()) },
                            flat = hero,
                            addMedia = if (hero && collection?.shared?.canContribute == true && state.canEditLibrary()) ({ sharedPicker = true }) else null,
                            leading = if (hero) ({
                                AlbumHero(requireNotNull(collection), summaries[collection.id], state.previewRetryVersion, preview,
                                    activity = if (collection.shared?.generation == SharedAlbumGeneration.MODERN) ({ sharedActivity = true }) else null,
                                    view = state.items.firstOrNull()?.let { first -> { open(first.id) } },
                                    info = if (collection.shared != null) ({ sharedInfo = true }) else null)
                            }) else if (showSharedStrip) ({
                                SharedAlbumsStrip(sharedAlbums, summaries, state.previewRetryVersion, preview, ::openShared,
                                    seeAll = { libraryQuery = state.query; tab = GalleryTab.COLLECTIONS; collectionsPage = CollectionsPage.SHARED; clear(); change(GalleryQuery()) })
                            }) else null)
                    }
                    if (collapseHeader) FloatingGridDate(visibleMonth,
                        dates = { jump = true }, modifier = Modifier.align(Alignment.TopCenter).padding(top = statusOverlap).zIndex(1f))
                    if (!wide && state.selection.isNotEmpty()) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)
                        .onSizeChanged { floatingBarHeight = with(uiDensity) { it.height.toDp() } }, shape = RoundedCornerShape(28.dp), tonalElevation = 8.dp, shadowElevation = 8.dp) {
                        SelectionActions(state, selectAll, clear, batch, actions)
                    }
                    else if (!wide) FloatingGalleryNavigation(tab, { navigate(it) },
                        Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(bottom = 16.dp)
                            .onSizeChanged { floatingBarHeight = with(uiDensity) { it.height.toDp() } })
                }
            }
        }
        if (phonePhotos) GalleryStatusBarScrim(Modifier.align(Alignment.TopCenter).zIndex(2f))
    }
    val currentGrid = when (tab) { GalleryTab.LIBRARY -> libraryGrid; GalleryTab.COLLECTIONS -> collectionGrid; GalleryTab.SEARCH -> searchGrid }
    if (jump) {
        val initialIndex = remember { currentGrid.firstVisibleItemIndex }
        GalleryDateSheet(state.sections, initialIndex, days = visibleDensity >= 160, date = state.query.date,
            permissionHeader = tab == GalleryTab.LIBRARY && !state.deviceMediaAccess && !permissionDismissed, leadingItems = leadingItems, showDateHeaders = !hero,
            dismiss = { jump = false }, jump = { index -> scope.launch { currentGrid.scrollToItem(index) } })
    }
    if (filters) GalleryFilterSheet(state, change, requestPhotos, refresh, visibleDensity, changeDensity, dismiss = { filters = false })
    deleteAlbum?.let { id ->
        AlertDialog(onDismissRequest = { deleteAlbum = null }, title = { Text(stringResource(R.string.delete_album_title)) },
            text = { Text(stringResource(R.string.delete_album_body, state.collections.firstOrNull { it.id == id }?.name ?: stringResource(R.string.this_album))) },
            confirmButton = { TextButton(enabled = state.canEditLibrary(), onClick = { actions.deleteAlbum(id); deleteAlbum = null }) { Text(stringResource(R.string.delete_album)) } },
            dismissButton = { TextButton(onClick = { deleteAlbum = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (activity) MelaBottomSheet(onDismissRequest = { activity = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MelaSheetHeading(stringResource(R.string.activity), stringResource(R.string.uploads_downloads_and_backup), dismiss = { activity = false })
            Button(onClick = if (signedIn?.status == SessionStatus.VERIFIED) upload else { { activity = false; account() } },
                modifier = Modifier.fillMaxWidth().testTag("upload-picker-button")) { Icon(MelaIcons.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.upload_photos)) }
            OutlinedButton(onClick = { activity = false; account() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_storage_and_backup)) }
            if (state.transferQueue.isEmpty() && state.batches.isEmpty()) Text(stringResource(R.string.no_transfers_yet), Modifier.padding(vertical = 16.dp))
            if (state.transferQueue.isNotEmpty()) TransferQueueCard(state.transferQueue, continueUpload)
            BatchProgress(state, retry, actions.cancelDownloadBatch)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (sharedPicker && collection?.shared != null) SharedContributionPicker(state.catalogItems.filter {
        !it.isShared && !it.isTrashed &&
            (if (collection.shared?.generation == SharedAlbumGeneration.LEGACY || it.origin == MediaOrigin.DEVICE) supportsLegacySharedUpload(it.kind, it.mimeType) else true) },
        preview = preview, dismiss = { sharedPicker = false }, contribute = { ids -> actions.contributeToSharedAlbum(collection.id, ids); sharedPicker = false },
        legacy = collection.shared?.generation == SharedAlbumGeneration.LEGACY, requestPhotos = requestPhotos)
    if (sharedInvitations) SharedInvitations(actions, dismiss = { sharedInvitations = false })
    if (sharedActivity && collection?.shared?.generation == SharedAlbumGeneration.MODERN) SharedAlbumActivity(collection, state.catalogItems, actions, preview, open = { sharedActivity = false; open(it) }, dismiss = { sharedActivity = false })
    if (sharedManage && collection?.shared != null) SharedAlbumControls(collection, actions) { sharedManage = false }
    if (sharedInfo && collection?.shared != null) SharedAlbumDetails(collection) { sharedInfo = false }
    if (editAlbum == "new-shared") CreateSharedAlbumDialog(dismiss = { editAlbum = null }) { name, generation ->
        actions.createSharedAlbum(name, generation); editAlbum = null
    }
    editAlbum?.takeUnless { it == "new-shared" }?.let { id -> AlbumNameDialog(if (id == "new-shared") stringResource(R.string.shared_create) else if (id == "new") stringResource(R.string.new_album) else stringResource(R.string.rename_album),
        state.collections.firstOrNull { it.id == id }?.name.orEmpty(), { editAlbum = null }) { name ->
        if (id == "new") actions.createAlbum(name) else actions.renameAlbum(id, name)
        editAlbum = null
    } }
}

@Composable
private fun TabIcon(tab: GalleryTab) {
    when (tab) {
        GalleryTab.LIBRARY -> Icon(androidx.compose.ui.res.painterResource(R.drawable.icloud_library), null, Modifier.size(24.dp))
        GalleryTab.COLLECTIONS -> Icon(androidx.compose.ui.res.painterResource(R.drawable.icloud_albums), null, Modifier.size(24.dp))
        GalleryTab.SEARCH -> Icon(Icons.Outlined.Search, null)
    }
}

@Composable
private fun PhotoGrid(state: GalleryUiState, grid: LazyGridState, density: Int, changeDensity: (Int) -> Unit, actions: LibraryActions, open: (String) -> Unit, toggle: (String) -> Unit,
    preview: (String) -> Unit, bottomSpace: androidx.compose.ui.unit.Dp, permission: Boolean, dismissPermission: () -> Unit, requestPhotos: () -> Unit, clearFilters: () -> Unit, leading: (@Composable () -> Unit)? = null, flat: Boolean = false, addMedia: (() -> Unit)? = null) {
    val leadingItems = if (leading != null) 1 else 0
    val mediaIds = state.items.map { it.id }
    val positions = remember(mediaIds) { mediaIds.withIndex().associate { it.value to it.index } }
    val prefetch by rememberUpdatedState(actions.prefetchPreviews)
    LaunchedEffect(grid, positions, state.previewRetryVersion) {
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
    val returningId = LocalPhotoTransition.current?.returningMediaId
    var visibleWhenOpened by rememberSaveable { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(returningId) {
        if (returningId != null && returningId !in visibleWhenOpened) {
            grid.requestScrollToItem(galleryPosition(state.sections, permission, density >= 160, state.query.date, returningId, leadingItems, showDateHeaders = !flat))
        }
    }
    Box(Modifier.fillMaxSize()) {
    LookaheadScope {
    LazyVerticalGrid(columns = GridCells.Adaptive(density.dp), state = grid, modifier = Modifier.fillMaxSize()
        .galleryGestures(grid, state.items, state.selection, density, changeDensity, { id, size -> galleryPosition(state.sections, permission, size >= 160, state.query.date, id, leadingItems, showDateHeaders = !flat) }, actions.selectItems).testTag("gallery-grid"),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = bottomSpace)) {
        if (permission) item(key = "permission", span = { GridItemSpan(maxLineSpan) }) {
            Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("phone-photo-prompt"),
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(MelaIcons.PhoneAndroid, null, Modifier.padding(top = 2.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.include_photos_from_this_phone), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.phone_photo_prompt_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton(onClick = dismissPermission) { Text(stringResource(R.string.not_now)) }
                            Button(onClick = requestPhotos) { Text(stringResource(R.string.phone_photo_prompt_action)) }
                        }
                    }
                }
            }
        }
        if (leading != null) item(key = "gallery-leading", span = { GridItemSpan(maxLineSpan) }) { Column { leading() } }
        if (state.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.isRefreshing) { CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.loading_photos)) }
                else if (state.query.collectionId?.startsWith("shared:") == true) Text(stringResource(R.string.shared_album_empty))
                else if (state.query.trashOnly) Text(stringResource(R.string.trash_empty))
                else { EmptyLibrary(); TextButton(onClick = clearFilters) { Text(stringResource(R.string.clear_filters)) } }
            }
        }
        state.sections.forEach { (month, items) ->
            if (!flat) item(key = "month-$month", span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { MonthHeader(month, items.size) }
                    val selectionLabel = "${stringResource(R.string.select_all_shown)} · ${monthLabel(month)}"
                    if (state.selection.isNotEmpty()) Checkbox(checked = items.all { it.id in state.selection },
                        onCheckedChange = { actions.selectItems(items.map { it.id }.toSet(), it) }, modifier = Modifier.padding(end = 12.dp).semantics { contentDescription = selectionLabel })
                }
            }
            val groups = if (!flat && density >= 160) items.groupBy { galleryDay(it, state.query.date) } else mapOf(null to items)
            groups.forEach { (day, photos) ->
            if (day != null) item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dateLabel(day), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    val selectionLabel = "${stringResource(R.string.select_all_shown)} · ${dateLabel(day)}"
                    if (state.selection.isNotEmpty()) Checkbox(photos.all { it.id in state.selection }, { checked -> actions.selectItems(photos.map { it.id }.toSet(), checked) },
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
                    MediaTile(state.previewRetryVersion, media.id in state.selection,
                    null, media, {
                        if (state.selection.isEmpty()) {
                            visibleWhenOpened = grid.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }
                            open(media.id)
                        } else toggle(media.id)
                    }, { preview(media.id) }, selectionAction = { toggle(media.id) })
                }
            }
            }
        }
        if (addMedia != null) item(key = "add-media") {
            Surface(onClick = addMedia, modifier = Modifier.aspectRatio(1f).testTag("album-add-media"),
                shape = RoundedCornerShape(2.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, stringResource(R.string.shared_add_photos), Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary) }
            }
        }
        item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(itemCount(state.items.size), style = MaterialTheme.typography.bodyMedium)
                if (state.query.collectionId?.startsWith(SharedMediaIdentity.PREFIX) != true) Text(stringResource(R.string.cloud_phone_counts, androidx.compose.ui.res.pluralStringResource(R.plurals.cloud_photo_count, state.cloudCount, state.cloudCount), stringResource(R.string.phone_photo_count, state.deviceCount)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    }
    val offsets = galleryMonthOffsets(state.sections, permission, density >= 160, state.query.date, leadingItems, showDateHeaders = !flat)
    val pixelDensity = LocalDensity.current
    val fullSpans = remember(state.sections, permission, leadingItems, density, state.query.date, flat, addMedia != null, pixelDensity) {
        with(pixelDensity) {
            buildMap<Int, Float> {
                var index = 0
                if (permission) put(index++, 190.dp.toPx())
                if (leadingItems > 0) put(index++, (if (flat) 420.dp else 280.dp).toPx())
                state.sections.forEach { section ->
                    if (!flat) put(index++, 56.dp.toPx())
                    val groups = if (!flat && density >= 160) section.items.groupBy { galleryDay(it, state.query.date) } else mapOf(null to section.items)
                    groups.forEach { (day, photos) ->
                        if (day != null) put(index++, 32.dp.toPx())
                        index += photos.size
                    }
                }
                if (addMedia != null) index++
                put(index, 80.dp.toPx())
            }
        }
    }
    GalleryFastScroll(grid, state.sections.mapIndexed { i, s -> GalleryScrollLabel(offsets[i], monthLabel(s.month, abbreviated = true), s.month.year) },
        fullSpans, density, Modifier.align(Alignment.CenterEnd).fillMaxHeight(), bottomSpace)
    }
}

@Composable
private fun SearchLanding(bottomSpace: androidx.compose.ui.unit.Dp, onDates: () -> Unit, choose: (String) -> Unit) {
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
