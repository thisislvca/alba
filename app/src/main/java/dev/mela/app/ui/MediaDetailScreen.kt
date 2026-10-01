package dev.mela.app.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp
import dev.mela.app.R
import dev.mela.app.ui.theme.MelaTheme
import dev.mela.engine.model.*
import dev.mela.protocol.account.ICloudAccountState
import dev.mela.protocol.account.SessionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

@Composable
internal fun MediaDetailScreen(
    gallerySnapshot: androidx.compose.ui.graphics.layer.GraphicsLayer? = null,
    playback: PlaybackReader? = null,
    libraryActions: LibraryActions,
    collections: List<GalleryCollection>,
    media: GalleryMedia,
    items: List<GalleryMedia>, select: (String) -> Unit, preview: (String) -> Unit,
    isWorking: Boolean,
    isDownloadingOriginal: Boolean = false,
    onBack: () -> Unit,
    onKeepOriginalOffline: () -> Unit,
    onRemoveCachedOriginal: () -> Unit,
    onRequestUpload: () -> Unit,
    onContinuePastUnresolved: () -> Unit,
    onMoveVerifiedPhotoToTrash: () -> Unit,
    onOpenAccount: () -> Unit,
    accountState: ICloudAccountState,
    uploadTransfer: TransferView?,
    currentAlbumId: String? = null,
    onViewerLightBars: (Boolean) -> Unit = {},
) {
    // MainActivity owns system-bar contrast for the active destination. Restoring an
    // outgoing viewer's captured values here races the gallery after its exit animation.
    val latestItems by rememberUpdatedState(items)
    val pager = remember { PagerState(currentPage = items.indexOfFirst { it.id == media.id }.coerceAtLeast(0), pageCount = { latestItems.size }) }
    val latestSelect by rememberUpdatedState(select)
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
    if (sharedControls && media.isShared) SharedPhotoControls(media, libraryActions) { sharedControls = false }
    var choosingAlbum by rememberSaveable(media.id) { mutableStateOf(false) }
    val activeScreen = LocalPhotoTransition.current?.active != false
    val canEdit = !isWorking && (media.origin == MediaOrigin.DEVICE || accountState == ICloudAccountState.Demo ||
        (accountState as? ICloudAccountState.SignedIn)?.status == SessionStatus.VERIFIED)
    val scope = rememberCoroutineScope()
    if (choosingAlbum) AlbumChooser(collections, dismiss = { choosingAlbum = false }, create = { name ->
        libraryActions.createAlbumWithPhotos(name, listOf(media.id)); choosingAlbum = false
    }) { id ->
        libraryActions.addToAlbum(id, listOf(media.id)); choosingAlbum = false
    }
    val darkViewer = isSystemInDarkTheme()
    val colors = viewerColors()
    MelaTheme(darkTheme = darkViewer) {
        BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            val density = LocalDensity.current
            val heightPx = with(density) { maxHeight.toPx() }
            val detailsHeightPx = heightPx * if (maxHeight < 480.dp) .55f else .69f
            val motion = remember(media.id, detailsHeightPx) { ViewerMotion(if (details) -detailsHeightPx else 0f) }
            val panelHeight = with(density) { (-motion.offset).coerceIn(0f, detailsHeightPx).toDp() }
            val expanded = panelHeight > 0.dp
            val chromeOn = controls && (media.kind != MediaKind.VIDEO || !videoPlaying)
            val canvasColor = if (chromeOn || expanded) colors.canvas else Color.Black
            SideEffect { onViewerLightBars(!darkViewer && (chromeOn || expanded)) }
            gallerySnapshot?.let { layer -> Box(Modifier.fillMaxSize().drawWithContent { drawLayer(layer) }) }
            Box(Modifier.fillMaxSize().background(canvasColor.copy(alpha = (1f - motion.offset.coerceAtLeast(0f) / (heightPx * .6f)).coerceIn(0f, 1f))))
            var startedExpanded by remember { mutableStateOf(false) }
            fun showDetails(show: Boolean) {
                details = show
                controls = true
                motion.settle(scope, if (show) -detailsHeightPx else 0f)
            }
            val gesture = Modifier.draggable(
                state = rememberDraggableState { motion.drag(it, detailsHeightPx, heightPx) },
                orientation = Orientation.Vertical, enabled = !zoomed && !pager.isScrollInProgress && activeScreen,
                onDragStarted = { startedExpanded = details; motion.begin() },
                onDragStopped = { velocity ->
                    val speed = with(density) { 900.dp.toPx() }
                    if (startedExpanded) {
                        showDetails(!(velocity > speed || motion.offset > -detailsHeightPx * .8f))
                    } else if (motion.offset > heightPx * .18f || (motion.offset > with(density) { 32.dp.toPx() } && velocity > speed)) {
                        motion.dragging = false
                        onBack()
                    } else {
                        showDetails(motion.offset < -detailsHeightPx * .22f || (motion.offset < -with(density) { 24.dp.toPx() } && velocity < -speed))
                    }
                },
            )
            PredictiveBackHandler(enabled = activeScreen) { progress ->
                val wasExpanded = details || expanded
                motion.begin()
                try {
                    progress.collect { event -> motion.moveTo(if (wasExpanded) -detailsHeightPx * (1f - event.progress) else heightPx * .4f * event.progress) }
                    if (wasExpanded) showDetails(false) else onBack()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    motion.settle(scope, if (wasExpanded) -detailsHeightPx else 0f)
                }
            }
            val detailsScroll = remember(media.id, motion) { object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y < 0 && motion.offset > -detailsHeightPx) {
                        motion.begin(); val before = motion.offset; motion.drag(available.y, detailsHeightPx, heightPx)
                        return Offset(0f, motion.offset - before)
                    }
                    return Offset.Zero
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y > 0) {
                        motion.begin(); val before = motion.offset; motion.moveTo((motion.offset + available.y).coerceIn(-detailsHeightPx, 0f))
                        return Offset(0f, motion.offset - before)
                    }
                    return Offset.Zero
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (motion.offset > -detailsHeightPx) { showDetails(motion.offset < -detailsHeightPx * .8f && available.y < 900f); return available }
                    return Velocity.Zero
                }
            } }
            val detailsLabel = stringResource(R.string.details)
            val backLabel = stringResource(R.string.back_to_gallery)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f).testTag("viewer-photo-viewport")) {
                    HorizontalPager(state = pager, key = { latestItems.getOrNull(it)?.id ?: "removed:$it" }, userScrollEnabled = !zoomed && !expanded && !motion.dragging,
                        modifier = Modifier.fillMaxSize().offset { IntOffset(0, motion.offset.coerceAtLeast(0f).roundToInt()) }
                            .graphicsLayer { val scale = 1f - .12f * (motion.offset.coerceAtLeast(0f) / (heightPx * .65f)); scaleX = scale; scaleY = scale }
                            .then(gesture).semantics { customActions = listOf(
                                CustomAccessibilityAction(detailsLabel) { showDetails(true); true },
                                CustomAccessibilityAction(backLabel) { onBack(); true },
                            ) }.testTag("media-pager")) { page ->
                        val item = latestItems.getOrNull(page) ?: return@HorizontalPager
                        val active = page == pager.settledPage
                        var loading by remember(item.id) { mutableStateOf(false) }
                        var failed by remember(item.id) { mutableStateOf(false) }
                        var retry by remember(item.id) { mutableIntStateOf(0) }
                        LaunchedEffect(item.id) { preview(item.id) }
                        LaunchedEffect(item.id, active, retry) {
                            if (active && item.origin == MediaOrigin.ICLOUD && item.kind != MediaKind.VIDEO) {
                                loading = true; failed = false
                                try { libraryActions.loadViewer(item.id) }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { failed = true }
                                finally { loading = false }
                            }
                        }
                        LaunchedEffect(active) { if (active) {
                            items.getOrNull(page - 1)?.let { preview(it.id) }; items.getOrNull(page + 1)?.let { preview(it.id) }
                        } }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (active && (item.kind == MediaKind.VIDEO || (item.id == media.id && playingLive)) && playback != null) {
                                VideoPlayer(item.id, playback, Modifier.fillMaxSize(), autoPlay = true,
                                    localReference = item.originalReference?.takeIf { item.origin == MediaOrigin.DEVICE },
                                    onPlayingChanged = { videoPlaying = it }, optionsOpen = videoSpeedSheet,
                                    onDismissOptions = { videoSpeedSheet = false }, loopEnabled = videoLoop,
                                    showPlaybackControls = !expanded)
                            } else MediaThumbnail(reference = item.linkedDeviceReference ?: item.originalReference ?: item.viewerReference ?: item.previewReference, sourceRevision = item.sourceRevision,
                                fallbackReference = item.previewReference, sharedMediaId = if (active) item.id else null,
                                accentStartArgb = item.accentStartArgb, accentEndArgb = item.accentEndArgb, contentDescription = item.fileName,
                                contentScale = ContentScale.Fit, zoomable = active && !expanded, onTap = { if (!expanded) controls = !controls },
                                onZoomChanged = { if (active) zoomed = it }, modifier = Modifier.fillMaxSize().testTag("viewer-image-${item.id}"))
                            if (active && loading) CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 76.dp).size(22.dp), strokeWidth = 2.dp)
                            if (active && failed) FilledTonalButton(onClick = { retry++; preview(item.id) }, modifier = Modifier.align(Alignment.Center).testTag("retry-viewer")) {
                                Text(stringResource(R.string.retry_photo))
                            }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility((chromeOn || expanded) && !motion.dragging,
                        modifier = Modifier.align(Alignment.TopCenter).photoChrome(),
                        enter = fadeIn(tween(140)), exit = fadeOut(tween(100))) {
                        if (expanded) ViewerDetailsBackButton(backLabel, { showDetails(false) })
                        else ViewerTopBar(media, backLabel, onBack,
                            favoriteEnabled = canEdit && !media.isTrashed && !media.isShared && !pager.isScrollInProgress,
                            onFavorite = { libraryActions.favorite(media.id, !media.isFavorite) },
                            onMore = { if (media.kind == MediaKind.VIDEO) videoOptions = true else showDetails(true) },
                            videoOptionsOpen = videoOptions, onDismissVideoOptions = { videoOptions = false },
                            videoLoop = videoLoop, onToggleLoop = { videoLoop = !videoLoop; videoOptions = false },
                            onPlaybackSpeed = { videoOptions = false; videoSpeedSheet = true })
                    }
                    androidx.compose.animation.AnimatedVisibility(chromeOn && !expanded && !motion.dragging,
                        modifier = Modifier.align(Alignment.BottomCenter).photoChrome(),
                        enter = fadeIn(tween(140)), exit = fadeOut(tween(100))) {
                        ViewerActionBar(
                            media = media, enabled = !isWorking && !pager.isScrollInProgress,
                            canEdit = canEdit,
                            onShare = { libraryActions.share(listOf(media)) },
                            onEdit = { libraryActions.edit(media) },
                            onDetails = { showDetails(true) },
                            onTrash = {
                                if (media.origin == MediaOrigin.DEVICE) libraryActions.trash(listOf(media), media.isTrashed)
                                else libraryActions.cloudTrash(listOf(media.id), !media.isTrashed)
                            },
                            onLive = { playingLive = !playingLive }, playingLive = playingLive,
                        )
                    }
                }
                if (expanded) Surface(Modifier.fillMaxWidth().height(panelHeight).clipToBounds().testTag("photo-details-panel"),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = colors.canvas, contentColor = colors.foreground) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(32.dp).then(gesture).testTag("details-drag-handle"), contentAlignment = Alignment.Center) {
                            Surface(Modifier.size(32.dp, 4.dp), shape = CircleShape, color = Color(0xFFDADCE6)) {}
                        }
                        Column(Modifier.fillMaxWidth().weight(1f).nestedScroll(detailsScroll).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(timestampLabel(Instant.ofEpochMilli(media.capturedAtEpochMillis)), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
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
                                    { libraryActions.trash(listOf(media), media.isTrashed) }, { Icon(MelaIcons.DeleteOutline, null) })
                                Text(stringResource(if (media.isTrashed) R.string.trash_restore_explanation else R.string.trash_phone_explanation), style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(24.dp))
                            if (media.origin == MediaOrigin.ICLOUD) {
                                Surface(shape = RoundedCornerShape(20.dp), color = colors.card, contentColor = colors.foreground) {
                                    Column {
                                        if (!media.isShared) {
                                        DetailAction(stringResource(if (media.isFavorite) R.string.unfavorite else R.string.favorite), canEdit && !media.isTrashed,
                                            { libraryActions.favorite(media.id, !media.isFavorite) }, { ICloudIcon(R.drawable.icloud_favorites) })
                                        DetailAction(stringResource(R.string.add_to_album), canEdit && !media.isTrashed, { choosingAlbum = true }, { ICloudIcon(R.drawable.icloud_albums) })
                                        collections.firstOrNull { it.id == currentAlbumId && !it.isFolder && it.shared == null && !it.id.startsWith("smart:") }?.let { album ->
                                            if (!media.isTrashed) DetailAction(stringResource(R.string.remove_from_album), canEdit,
                                                { libraryActions.removeFromAlbum(album.id, listOf(media.id)) }, { ICloudIcon(R.drawable.icloud_albums) })
                                        }
                                        DetailAction(stringResource(if (media.isTrashed) R.string.restore_from_icloud else R.string.trash_in_icloud), canEdit,
                                            { libraryActions.cloudTrash(listOf(media.id), !media.isTrashed) }, { Icon(MelaIcons.DeleteOutline, null) })
                                        }
                                        if (media.isShared) {
                                            DetailAction(stringResource(R.string.shared_discussion), canEdit, { sharedControls = true }, { Icon(Icons.Outlined.Info, null) })
                                            Text(stringResource(R.string.shared_copy_notice), Modifier.padding(18.dp))
                                        }
                                        if (Build.VERSION.SDK_INT >= 29) DetailAction(stringResource(if (media.kind == MediaKind.LIVE_PHOTO) R.string.save_photo_and_video_to_gallery else R.string.save_to_phone_gallery),
                                            !isWorking, { libraryActions.saveToGallery(media) }, { Icon(MelaIcons.PhoneAndroid, null) })
                                        DetailAction(stringResource(R.string.export_original), !isWorking, { libraryActions.exportOriginal(media) }, { Icon(Icons.Outlined.Share, null) })
                                        if (media.kind == MediaKind.LIVE_PHOTO) DetailAction(stringResource(R.string.export_live_photo_originals_zip), !isWorking,
                                            { libraryActions.exportLivePhoto(media) }, { ICloudIcon(R.drawable.icloud_live_photos) })
                                        HorizontalDivider(color = colors.canvas, thickness = 2.dp)
                                        if (media.availability == MediaAvailability.ORIGINAL_CACHED) {
                                            DetailAction(stringResource(R.string.remove_offline_copy), !isWorking, onRemoveCachedOriginal, { Icon(MelaIcons.DeleteOutline, null) })
                                        } else {
                                            DetailAction(stringResource(if (isWorking) R.string.downloading_original else R.string.keep_original_offline), !isWorking,
                                                onKeepOriginalOffline, {
                                                    if (isWorking) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                                    else Icon(MelaIcons.DownloadForOffline, null)
                                                })
                                        }
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                                if (isDownloadingOriginal) TextButton(onClick = libraryActions.cancelOfflineDownload) { Text(stringResource(R.string.cancel_download)) }
                                Text(stringResource(R.string.offline_copies_live_only_in_mela_s_private_storage_this_action_never_removes_a_photo_),
                                    style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                                if (media.kind == MediaKind.LIVE_PHOTO) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(stringResource(R.string.the_zip_preserves_the_original_still_image_and_motion_clip_your_gallery_may_show_them),
                                        style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                                }
                            } else if (!media.isTrashed && media.kind == MediaKind.PHOTO) {
                                DeviceUploadControls(
                                    uploadTransfer = uploadTransfer,
                                    isSignedIn = (accountState as? ICloudAccountState.SignedIn)?.status == SessionStatus.VERIFIED,
                                    isWorking = isWorking,
                                    onRequestUpload = onRequestUpload,
                                    onContinuePastUnresolved = onContinuePastUnresolved,
                                    onMoveVerifiedPhotoToTrash = onMoveVerifiedPhotoToTrash,
                                    onOpenAccount = onOpenAccount,
                                )
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerDetailsBackButton(label: String, click: () -> Unit) {
    val colors = viewerColors()
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, top = 12.dp)) {
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
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(media.capturedAtEpochMillis, locale) {
        Instant.ofEpochMilli(media.capturedAtEpochMillis).atZone(ZoneId.systemDefault())
    }
    val colors = viewerColors()
    val ink = colors.secondary
    Box(Modifier.fillMaxWidth().background(colors.canvas).statusBarsPadding().height(64.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp).size(48.dp)
            .testTag("floating-viewer-back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, backLabel, tint = ink)
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date.format(DateTimeFormatter.ofPattern("MMM d", locale)), color = colors.foreground,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium))
            Text(date.format(DateTimeFormatter.ofPattern("h:mm a", locale)), color = ink,
                style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
            if (!media.isShared && !media.isTrashed) IconButton(onClick = onFavorite, enabled = favoriteEnabled,
                modifier = Modifier.size(48.dp).testTag("viewer-favorite")) {
                Icon(MelaIcons.StarOutline, stringResource(if (media.isFavorite) R.string.unfavorite else R.string.favorite),
                    tint = if (media.isFavorite) MaterialTheme.colorScheme.primary else ink)
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
    Row(Modifier.fillMaxWidth().background(colors.canvas).navigationBarsPadding().offset(y = 5.dp).height(76.dp),
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
                Icon(MelaIcons.DeleteOutline, null)
            }
        } else ViewerAction(stringResource(R.string.details), true, onDetails, Modifier.weight(1f)) {
            Icon(Icons.Outlined.Info, null)
        }
        if (media.kind == MediaKind.LIVE_PHOTO) ViewerAction(
            stringResource(if (playingLive) R.string.show_still_photo else R.string.play_live_photo), true, onLive, Modifier.weight(1f)) {
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
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = click)
            .heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(18.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
    }
}
