@file:OptIn(ExperimentalLayoutApi::class)

package me.lvca.alba.app.ui.viewer

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.MediaScreenActions
import me.lvca.alba.app.ui.collections.AlbumChooser
import me.lvca.alba.app.ui.collections.isUserAlbum
import me.lvca.alba.app.ui.dayMonthLabel
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.AlbaListRow
import me.lvca.alba.app.ui.designsystem.FavoriteIcon
import me.lvca.alba.app.ui.designsystem.ICloudIcon
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.designsystem.theme.sheet
import me.lvca.alba.app.ui.designsystem.theme.viewerTitle
import me.lvca.alba.app.ui.gallery.MediaThumbnail
import me.lvca.alba.app.ui.shared.SharedPhotoControls
import me.lvca.alba.app.ui.timeLabel
import me.lvca.alba.app.ui.timestampLabel
import me.lvca.alba.app.ui.transfers.DeviceUploadControls
import me.lvca.alba.engine.model.*
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus

@Composable
internal fun MediaDetailScreen(
    media: GalleryMedia,
    items: List<GalleryMedia>,
    collections: List<GalleryCollection>,
    currentAlbumId: String?,
    accountState: ICloudAccountState,
    uploadTransfer: TransferView?,
    isWorking: Boolean,
    isDownloadingOriginal: Boolean,
    actions: MediaScreenActions,
    onPageSettled: (String) -> Unit,
    gallerySnapshot: GraphicsLayer? = null,
    onViewerLightBars: (Boolean) -> Unit = {},
) {
    // AlbaRoot owns system-bar contrast for the active destination. Restoring an
    // outgoing viewer's captured values here races the gallery after its exit animation.
    val latestItems by rememberUpdatedState(items)
    val pager = remember { PagerState(currentPage = items.indexOfFirst { it.id == media.id }.coerceAtLeast(0), pageCount = { latestItems.size }) }
    val latestSelect by rememberUpdatedState(onPageSettled)
    val playback: PlaybackReader = remember(actions) { actions::openPlayback }
    var controls by rememberSaveable { mutableStateOf(true) }
    var videoPlaying by remember(media.id) { mutableStateOf(false) }
    var videoOptions by remember(media.id) { mutableStateOf(false) }
    var videoSpeedSheet by remember(media.id) { mutableStateOf(false) }
    var videoLoop by rememberSaveable(media.id) { mutableStateOf(true) }
    var zoomed by remember(media.id) { mutableStateOf(false) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page -> latestItems.getOrNull(page)?.let { latestSelect(it.id) } }
    }
    val itemIds = remember(items) { items.map { it.id } }
    LaunchedEffect(itemIds) {
        val index = items.indexOfFirst { it.id == media.id }
        if (index >= 0 && !pager.isScrollInProgress && pager.currentPage != index) pager.scrollToPage(index)
    }
    var details by rememberSaveable(media.id) { mutableStateOf(false) }
    var playingLive by rememberSaveable(media.id) { mutableStateOf(false) }
    var sharedControls by rememberSaveable(media.id) { mutableStateOf(false) }
    if (sharedControls && media.isShared) SharedPhotoControls(media, actions) { sharedControls = false }
    var choosingAlbum by rememberSaveable(media.id) { mutableStateOf(false) }
    val activeScreen = LocalPhotoTransition.current?.active != false
    val canEdit = !isWorking && (media.origin == MediaOrigin.DEVICE || accountState == ICloudAccountState.Demo ||
        (accountState as? ICloudAccountState.SignedIn)?.status == SessionStatus.VERIFIED)
    val scope = rememberCoroutineScope()
    if (choosingAlbum) AlbumChooser(collections, dismiss = { choosingAlbum = false }, create = { name ->
        actions.createAlbumWithPhotos(name, listOf(media.id)); choosingAlbum = false
    }) { id ->
        actions.addToAlbum(id, listOf(media.id)); choosingAlbum = false
    }
    val darkViewer = AlbaTheme.colors.isDark
    val colors = viewerColors()
    val reduceMotion = reduceGalleryMotion()
    // Keyed by photo only: a resize or rotation must not drop an in-flight drag (B3).
    val motion = remember(media.id) { ViewerMotion(details) }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val statusBarPx = WindowInsets.statusBarsIgnoringVisibility.getTop(density).toFloat()
        val detailsHeightPx = detailsSheetHeight(widthPx, heightPx, statusBarPx)
        val fullEffectPx = with(density) { ViewerSpec.DismissFullEffect.toPx() }
        val pose = { motion.pose(fullEffectPx, widthPx) }
        val panelHeight = with(density) { (motion.details * detailsHeightPx).toDp() }
        val expanded = panelHeight > 0.dp
        val chromeOn = controls && (media.kind != MediaKind.VIDEO || !videoPlaying)
        val chromeFade = tween<Float>(if (reduceMotion) 80 else ViewerSpec.CHROME_FADE_MILLIS, easing = LinearEasing)
        val canvasColor by animateColorAsState(if (chromeOn || expanded) colors.canvas else Color.Black,
            tween(if (reduceMotion) 0 else ViewerSpec.CHROME_FADE_MILLIS, easing = LinearEasing), label = "viewer-canvas")
        SideEffect { onViewerLightBars(!darkViewer && (chromeOn || expanded)) }
        // GP hides the status and navigation bars with the chrome; an edge swipe reveals them briefly.
        // A playing video's overlay is chrome too, so the bars follow `controls` rather than `chromeOn`.
        ImmersiveSystemBars(activeScreen && !controls && !expanded)
        gallerySnapshot?.let { layer -> Box(Modifier.fillMaxSize().drawWithContent { drawLayer(layer) }) }
        Box(Modifier.fillMaxSize().drawBehind { drawRect(canvasColor, alpha = pose().backdrop) })
        var dragMode by remember { mutableStateOf<ViewerDrag?>(null) }
        fun showDetails(show: Boolean) {
            details = show
            controls = true
            motion.settleDetails(scope, show, reduceMotion)
        }
        val dragEnabled = !zoomed && !pager.isScrollInProgress && activeScreen
        val startDrag: (ViewerDrag) -> Unit = { mode -> dragMode = mode; motion.begin() }
        val moveDrag: (ViewerDrag, Offset) -> Unit = { mode, delta ->
            if (mode == ViewerDrag.DISMISS) motion.dragDismiss(delta) else motion.dragDetails(delta.y, detailsHeightPx)
        }
        val stopDrag: (ViewerDrag, Velocity) -> Unit = { mode, velocity ->
            dragMode = null
            if (mode == ViewerDrag.DETAILS) showDetails(detailsOpenAfterRelease(motion.details, velocity.y))
            else if (shouldDismiss(motion.travel, fullEffectPx)) {
                // Keep the pose: the shared element returns to its tile from where the finger left it.
                motion.dragging = false
                actions.closeMedia()
            } else motion.settleDismiss(scope, reduceMotion)
        }
        val pagerDrag = Modifier.viewerDrag(dragEnabled, { details || motion.details > 0f }, startDrag, moveDrag, stopDrag)
        val handleDrag = Modifier.viewerDrag(dragEnabled, { true }, startDrag, moveDrag, stopDrag)
        PredictiveBackHandler(enabled = activeScreen) { progress ->
            val wasExpanded = details || expanded
            motion.begin()
            var startY = Float.NaN
            try {
                progress.collect { event ->
                    if (startY.isNaN()) startY = event.touchY
                    if (wasExpanded) motion.moveDetails(1f - event.progress)
                    else motion.moveBack(event.progress, if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f, event.touchY - startY)
                }
                if (wasExpanded) showDetails(false) else {
                    // Commit runs the same close-to-tile transition as a swipe down.
                    motion.dragging = false
                    actions.closeMedia()
                }
            } catch (cancelled: CancellationException) {
                if (wasExpanded) showDetails(true) else motion.settleBack(scope, reduceMotion)
            }
        }
        val detailsScroll = remember(media.id, motion, detailsHeightPx) { object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0 && motion.details < 1f) {
                    motion.begin(); val before = motion.details; motion.dragDetails(available.y, detailsHeightPx)
                    return Offset(0f, (before - motion.details) * detailsHeightPx)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0) {
                    motion.begin(); val before = motion.details; motion.dragDetails(available.y, detailsHeightPx)
                    return Offset(0f, (before - motion.details) * detailsHeightPx)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (motion.details < 1f) { showDetails(detailsOpenAfterRelease(motion.details, available.y)); return available }
                return Velocity.Zero
            }
        } }
        val handoff = remember(pager, scope, reduceMotion) { object : ZoomPanHandoff {
            private var startPage = -1
            override fun scrollBy(fingerDeltaX: Float): Float {
                if (startPage < 0) startPage = pager.currentPage
                return -pager.dispatchRawDelta(-fingerDeltaX)
            }
            override fun release(velocityX: Float) {
                val from = startPage.takeIf { it >= 0 } ?: pager.currentPage
                startPage = -1
                val target = handoffTargetPage(from, pager.currentPage + pager.currentPageOffsetFraction, velocityX,
                    with(density) { ViewerSpec.PageFlingVelocity.toPx() }, pager.pageCount)
                scope.launch { if (reduceMotion) pager.scrollToPage(target) else pager.animateScrollToPage(target) }
            }
        } }
        val detailsLabel = stringResource(R.string.details)
        val backLabel = stringResource(R.string.back_to_gallery)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f).testTag("viewer-photo-viewport")) {
                HorizontalPager(state = pager, key = { latestItems.getOrNull(it)?.id ?: "removed:$it" },
                    userScrollEnabled = !zoomed && !expanded && !motion.dragging,
                    pageSpacing = ViewerSpec.PageSpacing,
                    // GP keeps one decoded page on either side, so a swipe reveals a sharp neighbour.
                    beyondViewportPageCount = 1,
                    modifier = Modifier.fillMaxSize()
                        .then(pagerDrag).semantics { customActions = listOf(
                            CustomAccessibilityAction(detailsLabel) { showDetails(true); true },
                            CustomAccessibilityAction(backLabel) { actions.closeMedia(); true },
                        ) }.testTag("media-pager")) { page ->
                    val item = latestItems.getOrNull(page) ?: return@HorizontalPager
                    val active = page == pager.settledPage
                    var loading by remember(item.id) { mutableStateOf(false) }
                    var failed by remember(item.id) { mutableStateOf(false) }
                    var retry by remember(item.id) { mutableIntStateOf(0) }
                    LaunchedEffect(item.id) { actions.requestPreview(item.id) }
                    LaunchedEffect(item.id, active, retry) {
                        if (active && item.origin == MediaOrigin.ICLOUD && item.kind != MediaKind.VIDEO) {
                            loading = true; failed = false
                            try { actions.loadViewer(item.id) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { failed = true }
                            finally { loading = false }
                        }
                    }
                    LaunchedEffect(active) { if (active) {
                        items.getOrNull(page - 1)?.let { actions.requestPreview(it.id) }; items.getOrNull(page + 1)?.let { actions.requestPreview(it.id) }
                    } }
                    val playing = active && (item.kind == MediaKind.VIDEO || (item.id == media.id && playingLive))
                    // Only the current page follows a swipe down or back gesture. Photos take the scale as
                    // layout size (see MediaThumbnail) so the return transition starts from it.
                    Box(Modifier.fillMaxSize().then(if (active) Modifier.offset { pose().translation.round() }
                        .then(if (playing) Modifier.graphicsLayer { pose().scale.let { scaleX = it; scaleY = it } } else Modifier)
                        else Modifier), contentAlignment = Alignment.Center) {
                        if (playing) {
                            VideoPlayer(item.id, playback, Modifier.fillMaxSize(), autoPlay = true,
                                localReference = item.originalReference?.takeIf { item.origin == MediaOrigin.DEVICE },
                                onPlayingChanged = { playingNow ->
                                    // GP: pausing (or the end of the clip) brings the full chrome back.
                                    if (videoPlaying && !playingNow) controls = true
                                    videoPlaying = playingNow
                                }, optionsOpen = videoSpeedSheet,
                                onDismissOptions = { videoSpeedSheet = false }, loopEnabled = videoLoop,
                                showPlaybackControls = !expanded,
                                controlsVisible = controls, onControlsVisibleChange = { controls = it })
                        } else MediaThumbnail(reference = item.linkedDeviceReference ?: item.originalReference ?: item.viewerReference ?: item.previewReference, sourceRevision = item.sourceRevision,
                            fallbackReference = item.previewReference, sharedMediaId = if (active) item.id else null,
                            accentStartArgb = item.accentStartArgb, accentEndArgb = item.accentEndArgb, contentDescription = item.fileName,
                            contentScale = ContentScale.Fit, zoomable = active && !expanded, onTap = { if (!expanded) controls = !controls },
                            onZoomChanged = { if (active) zoomed = it }, sourceSize = IntSize(item.width, item.height),
                            zoomHandoff = handoff, presentationScale = if (active) { { pose().scale } } else { { 1f } },
                            modifier = Modifier.fillMaxSize().testTag("viewer-image-${item.id}"))
                        if (active && loading) CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 76.dp).size(22.dp), strokeWidth = 2.dp)
                        if (active && failed) FilledTonalButton(onClick = { retry++; actions.requestPreview(item.id) }, modifier = Modifier.align(Alignment.Center).testTag("retry-viewer")) {
                            Text(stringResource(R.string.retry_photo))
                        }
                    }
                }
                val chromeAlpha = Modifier.graphicsLayer { alpha = pose().backdrop }
                val detailsDragging = dragMode == ViewerDrag.DETAILS
                androidx.compose.animation.AnimatedVisibility((chromeOn || expanded) && !detailsDragging,
                    modifier = Modifier.align(Alignment.TopCenter).photoChrome().then(chromeAlpha),
                    enter = fadeIn(chromeFade), exit = fadeOut(chromeFade)) {
                    if (expanded) ViewerDetailsBackButton(backLabel, { showDetails(false) })
                    else ViewerTopBar(media, backLabel, actions::closeMedia,
                        favoriteEnabled = canEdit && !media.isTrashed && !media.isShared && !pager.isScrollInProgress,
                        onFavorite = { actions.setFavorite(media.id, !media.isFavorite) },
                        onMore = { if (media.kind == MediaKind.VIDEO) videoOptions = true else showDetails(true) },
                        videoOptionsOpen = videoOptions, onDismissVideoOptions = { videoOptions = false },
                        videoLoop = videoLoop, onToggleLoop = { videoLoop = !videoLoop; videoOptions = false },
                        onPlaybackSpeed = { videoOptions = false; videoSpeedSheet = true })
                }
                androidx.compose.animation.AnimatedVisibility(chromeOn && !expanded && !detailsDragging,
                    modifier = Modifier.align(Alignment.BottomCenter).photoChrome().then(chromeAlpha),
                    enter = fadeIn(chromeFade), exit = fadeOut(chromeFade)) {
                    ViewerActionBar(
                        media = media, enabled = !isWorking && !pager.isScrollInProgress,
                        canEdit = canEdit,
                        onShare = { actions.share(listOf(media)) },
                        onEdit = { actions.edit(media) },
                        onDetails = { showDetails(true) },
                        onTrash = {
                            if (media.origin == MediaOrigin.DEVICE) actions.trashOnPhone(listOf(media), media.isTrashed)
                            else actions.confirmCloudTrash(listOf(media.id), !media.isTrashed)
                        },
                        onLive = { playingLive = !playingLive }, playingLive = playingLive,
                    )
                }
            }
            if (expanded) Surface(Modifier.fillMaxWidth().height(panelHeight).clipToBounds().testTag("photo-details-panel"),
                shape = MaterialTheme.shapes.sheet, color = colors.canvas, contentColor = colors.foreground) {
                Column {
                    Box(Modifier.fillMaxWidth().height(32.dp).then(handleDrag).testTag("details-drag-handle"), contentAlignment = Alignment.Center) {
                        Surface(Modifier.size(32.dp, 4.dp), shape = CircleShape, color = AlbaTheme.colors.sheetHandle) {}
                    }
                    Column(Modifier.fillMaxWidth().weight(1f).nestedScroll(detailsScroll).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(timestampLabel(media.capturedAtEpochMillis), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            IconButton(onClick = { showDetails(false) }) { Icon(Icons.Outlined.Close, stringResource(R.string.close_details)) }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(detailsLabel, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))
                        PhotoMetadataCards(media, accountState, uploadTransfer)
                        PhotoExtraMetadata(media)
                        if (Build.VERSION.SDK_INT >= 30 && (media.origin == MediaOrigin.DEVICE || media.linkedDeviceReference != null)) {
                            Spacer(Modifier.height(16.dp))
                            DetailAction(stringResource(if (media.isTrashed) R.string.restore_photo else R.string.trash_phone_copy), !isWorking,
                                { actions.trashOnPhone(listOf(media), media.isTrashed) }, { Icon(AlbaIcons.DeleteOutline, null) })
                            Text(stringResource(if (media.isTrashed) R.string.trash_restore_explanation else R.string.trash_phone_explanation), style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(24.dp))
                        if (media.origin == MediaOrigin.ICLOUD) {
                            AlbaCard(color = colors.card, contentColor = colors.foreground) {
                                Column {
                                    if (!media.isShared) {
                                    DetailAction(stringResource(if (media.isFavorite) R.string.unfavorite else R.string.favorite), canEdit && !media.isTrashed,
                                        { actions.setFavorite(media.id, !media.isFavorite) }, { FavoriteIcon(media.isFavorite, null, tint = if (media.isFavorite) AlbaTheme.colors.favorite else AlbaTheme.colors.accent) })
                                    DetailAction(stringResource(R.string.add_to_album), canEdit && !media.isTrashed, { choosingAlbum = true }, { ICloudIcon(R.drawable.icloud_albums) })
                                    collections.firstOrNull { it.id == currentAlbumId && it.isUserAlbum }?.let { album ->
                                        if (!media.isTrashed) DetailAction(stringResource(R.string.remove_from_album), canEdit,
                                            { actions.confirmRemoveFromAlbum(album.id, listOf(media.id)) }, { ICloudIcon(R.drawable.icloud_albums) })
                                    }
                                    DetailAction(stringResource(if (media.isTrashed) R.string.restore_from_icloud else R.string.trash_in_icloud), canEdit,
                                        { actions.confirmCloudTrash(listOf(media.id), !media.isTrashed) }, { Icon(AlbaIcons.DeleteOutline, null) })
                                    }
                                    if (media.isShared) {
                                        DetailAction(stringResource(R.string.shared_discussion), canEdit, { sharedControls = true }, { Icon(Icons.Outlined.Info, null) })
                                        Text(stringResource(R.string.shared_copy_notice), Modifier.padding(18.dp))
                                    }
                                    if (Build.VERSION.SDK_INT >= 29) DetailAction(stringResource(if (media.kind == MediaKind.LIVE_PHOTO) R.string.save_photo_and_video_to_gallery else R.string.save_to_phone_gallery),
                                        !isWorking, { actions.saveToGallery(media) }, { Icon(AlbaIcons.PhoneAndroid, null) })
                                    DetailAction(stringResource(R.string.export_original), !isWorking, { actions.exportOriginal(media) }, { Icon(Icons.Outlined.Share, null) })
                                    if (media.kind == MediaKind.LIVE_PHOTO) DetailAction(stringResource(R.string.export_live_photo_originals_zip), !isWorking,
                                        { actions.exportLivePhoto(media) }, { ICloudIcon(R.drawable.icloud_live_photos) })
                                    HorizontalDivider(color = colors.canvas, thickness = 2.dp)
                                    if (media.availability == MediaAvailability.ORIGINAL_CACHED) {
                                        DetailAction(stringResource(R.string.remove_offline_copy), !isWorking, { actions.removeCachedOriginal(media.id) }, { Icon(AlbaIcons.DeleteOutline, null) })
                                    } else {
                                        DetailAction(stringResource(if (isWorking) R.string.downloading_original else R.string.keep_original_offline), !isWorking,
                                            { actions.keepOriginalOffline(media.id) }, {
                                                if (isWorking) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                                else Icon(AlbaIcons.DownloadForOffline, null)
                                            })
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            if (isDownloadingOriginal) TextButton(onClick = actions::cancelOfflineDownload) { Text(stringResource(R.string.cancel_download)) }
                            Text(stringResource(R.string.offline_copies_live_only_in_alba_s_private_storage_this_action_never_removes_a_photo_),
                                style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                            if (media.kind == MediaKind.LIVE_PHOTO) {
                                Spacer(Modifier.height(12.dp))
                                Text(stringResource(R.string.the_zip_preserves_the_original_still_image_and_motion_clip_your_gallery_may_show_them),
                                    style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                            }
                        } else if (!media.isTrashed && media.kind == MediaKind.PHOTO) {
                            DeviceUploadControls(
                                uploadTransfer = uploadTransfer,
                                demo = accountState == ICloudAccountState.Demo,
                                isSignedIn = (accountState as? ICloudAccountState.SignedIn)?.status == SessionStatus.VERIFIED,
                                isWorking = isWorking,
                                onRequestUpload = { actions.requestUpload(media.id) },
                                onContinuePastUnresolved = { uploadTransfer?.let { actions.continuePastUnresolved(it.id) } },
                                onMoveVerifiedPhotoToTrash = { actions.moveVerifiedPhotoToTrash(media.id) },
                                onOpenAccount = actions::openAccount,
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

/** Hides the status and navigation bars while [hidden]; an edge swipe shows them transiently. Leaving restores them. */
@Composable
private fun ImmersiveSystemBars(hidden: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(window, view, hidden) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun ViewerDetailsBackButton(label: String, click: () -> Unit) {
    val colors = viewerColors()
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(start = 12.dp, top = 12.dp)) {
        Surface(shape = CircleShape, color = colors.canvas, contentColor = colors.foreground, shadowElevation = 4.dp) {
            IconButton(onClick = click, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, label)
            }
        }
    }
}

@Composable
private fun ViewerTopBar(
    media: GalleryMedia,
    backLabel: String,
    onBack: () -> Unit,
    favoriteEnabled: Boolean,
    onFavorite: () -> Unit,
    onMore: () -> Unit,
    videoOptionsOpen: Boolean,
    onDismissVideoOptions: () -> Unit,
    videoLoop: Boolean,
    onToggleLoop: () -> Unit,
    onPlaybackSpeed: () -> Unit,
) {
    val colors = viewerColors()
    val ink = colors.secondary
    Box(Modifier.fillMaxWidth().background(colors.canvas).windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).height(64.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp).size(48.dp)
            .testTag("floating-viewer-back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, backLabel, tint = ink)
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(dayMonthLabel(media.capturedAtEpochMillis), color = colors.foreground,
                style = MaterialTheme.typography.viewerTitle)
            Text(timeLabel(media.capturedAtEpochMillis), color = ink,
                style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
            if (!media.isShared && !media.isTrashed) IconButton(onClick = onFavorite, enabled = favoriteEnabled,
                modifier = Modifier.size(48.dp).testTag("viewer-favorite")) {
                FavoriteIcon(media.isFavorite, stringResource(if (media.isFavorite) R.string.unfavorite else R.string.favorite),
                    tint = if (media.isFavorite) AlbaTheme.colors.favorite else ink)
            }
            Box {
                IconButton(onClick = onMore, modifier = Modifier.size(48.dp).testTag("viewer-more")) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.more), tint = ink)
                }
                DropdownMenu(expanded = media.kind == MediaKind.VIDEO && videoOptionsOpen,
                    onDismissRequest = onDismissVideoOptions) {
                    DropdownMenuItem(text = { Text(stringResource(if (videoLoop) R.string.loop_video_on else R.string.loop_video_off)) },
                        onClick = onToggleLoop)
                    DropdownMenuItem(text = { Text(stringResource(R.string.playback_speed)) }, onClick = onPlaybackSpeed)
                }
            }
        }
    }
}

@Composable
private fun ViewerActionBar(
    media: GalleryMedia,
    enabled: Boolean,
    canEdit: Boolean,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDetails: () -> Unit,
    onTrash: () -> Unit,
    onLive: () -> Unit,
    playingLive: Boolean,
) {
    val colors = viewerColors()
    Row(Modifier.fillMaxWidth().background(colors.canvas).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility).offset(y = 5.dp).height(76.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        ViewerAction(stringResource(R.string.share), enabled, onShare, Modifier.weight(1f)) {
            Icon(Icons.Outlined.Share, null)
        }
        if (media.kind != MediaKind.VIDEO && !media.isTrashed) ViewerAction(stringResource(R.string.viewer_edit), enabled, onEdit, Modifier.weight(1f)) {
            Icon(Icons.Outlined.Edit, null)
        } else ViewerAction(stringResource(R.string.details), true, onDetails, Modifier.weight(1f)) {
            Icon(Icons.Outlined.Info, null)
        }
        if (!media.isShared && (media.origin == MediaOrigin.ICLOUD || Build.VERSION.SDK_INT >= 30)) {
            ViewerAction(stringResource(if (media.isTrashed) R.string.restore_photo else R.string.viewer_trash),
                enabled && canEdit, onTrash, Modifier.weight(1f)) {
                Icon(AlbaIcons.DeleteOutline, null)
            }
        } else ViewerAction(stringResource(R.string.details), true, onDetails, Modifier.weight(1f)) {
            Icon(Icons.Outlined.Info, null)
        }
        if (media.kind == MediaKind.LIVE_PHOTO) ViewerAction(stringResource(if (playingLive) R.string.show_still_photo else R.string.play_live_photo), true, onLive, Modifier.weight(1f)) {
            Icon(Icons.Filled.PlayArrow, null)
        }
    }
}

@Composable
private fun ViewerAction(label: String, enabled: Boolean, click: () -> Unit, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    val colors = viewerColors()
    Column(modifier.clickable(enabled = enabled, role = Role.Button, onClick = click)
        .height(76.dp).semantics { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) colors.secondary else colors.disabled) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.height(7.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
private fun DetailAction(label: String, enabled: Boolean, click: () -> Unit, icon: @Composable () -> Unit) {
    val colors = viewerColors()
    CompositionLocalProvider(LocalContentColor provides if (enabled) colors.foreground else colors.disabled) {
        AlbaListRow(label, click, enabled = enabled, leading = icon, leadingSize = 28.dp, gap = 18.dp,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp))
    }
}
