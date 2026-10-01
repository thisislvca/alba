package dev.mela.app.ui

import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import kotlinx.coroutines.launch
import android.os.Build
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.selected
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import dev.mela.app.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.mela.app.GalleryFilter
import dev.mela.app.GalleryUiState
import dev.mela.engine.model.GalleryMedia
import dev.mela.engine.model.MediaAvailability
import dev.mela.engine.model.MediaOrigin
import dev.mela.engine.model.TransferId
import dev.mela.engine.model.TransferView
import dev.mela.engine.model.TransferViewState
import java.time.YearMonth
import androidx.compose.foundation.combinedClickable
import dev.mela.engine.model.GalleryQuery
import dev.mela.engine.model.MediaKind
import dev.mela.engine.companion.BatchAction

private data class GalleryDestination(val screen: String, val media: GalleryMedia?, val items: List<GalleryMedia>)

@Composable
fun MelaApp(
    state: GalleryUiState,
    onRefresh: () -> Unit,
    onSelectFilter: (GalleryFilter) -> Unit,
    onSelectMedia: (String) -> Unit,
    onCloseDetail: () -> Unit,
    onRequestPreview: (String) -> Unit,
    onRequestDevicePhotos: () -> Unit,
    onPickUpload: () -> Unit,
    onKeepOriginalOffline: (String) -> Unit,
    onRemoveCachedOriginal: (String) -> Unit,
    onRequestUpload: (String) -> Unit,
    onContinuePastUnresolved: (TransferId) -> Unit,
    onMoveVerifiedPhotoToTrash: (String) -> Unit,
    onDismissMessage: () -> Unit,
    onOpenAccount: () -> Unit,
    onCloseAccount: () -> Unit,
    onSignIn: (String, String) -> Unit,
    onSubmitTwoFactor: (String) -> Unit,
    onResendTwoFactor: () -> Unit,
    onSignOut: () -> Unit,
    onEnableAutomaticBackup: () -> Unit,
    onDisableAutomaticBackup: () -> Unit,
    onQueryChange: (GalleryQuery) -> Unit = {},
    onToggleSelection: (String) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onBatch: (BatchAction) -> Unit = {},
    onRetryBatch: (String) -> Unit = {},
    playback: PlaybackReader? = null,
    libraryActions: LibraryActions = LibraryActions(),
    onViewerLightBars: (Boolean) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val screenState = rememberSaveableStateHolder()
    val galleryLayer = rememberGraphicsLayer()

    val localizedMessage = state.message?.localized()
    LaunchedEffect(localizedMessage) {
        val message = localizedMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onDismissMessage()
    }

    if (state.activeDownloadId == "share") androidx.compose.material3.AlertDialog(
        onDismissRequest = libraryActions.cancelShare, title = { Text(stringResource(R.string.preparing_to_share)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.downloading_original_files_when_needed_live_photos_share_their_still_image_use_zip_ex))
        } }, confirmButton = { TextButton(onClick = libraryActions.cancelShare) { Text(stringResource(R.string.cancel)) } })

    BackHandler(enabled = state.isAccountOpen || state.selectedMedia != null || state.selection.isNotEmpty()) {
        if (state.selection.isNotEmpty()) onClearSelection() else if (state.isAccountOpen) onCloseAccount() else onCloseDetail()
    }

    val destination = when {
        state.isAccountOpen -> "account"
        state.selectedMedia != null -> "media"
        else -> "gallery"
    }

    var viewerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(destination) {
        if (destination == "media") viewerWasOpen = true
        // Discard only after an actual exit, not while the initial selection is restoring.
        if (destination == "gallery" && viewerWasOpen) {
            screenState.removeState("media")
            viewerWasOpen = false
        }
    }
    val navigation = updateTransition(GalleryDestination(destination, state.selectedMedia, state.items), label = "photo-navigation")
    var galleryBottomSpace by remember { mutableStateOf(100.dp) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            // Floating controls are inside the gallery rather than Scaffold.bottomBar.
            // Keep status messages above their measured height, including large text/selection.
            SnackbarHost(snackbarHostState, Modifier.imePadding().padding(bottom =
                if (destination == "gallery") galleryBottomSpace else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
                Snackbar(it, modifier = Modifier.testTag("status-snackbar"))
            }
        },
    ) { padding ->
        SharedTransitionLayout {
            navigation.AnimatedContent(
                contentKey = { it.screen },
                transitionSpec = {
                    if (initialState.screen != "account" && targetState.screen != "account") {
                        // Keep the gallery opaque underneath the viewer. Fading both screens exposes
                        // the scaffold between them and produces a pale flash.
                        if (targetState.screen == "media") {
                            fadeIn(tween(PHOTO_TRANSITION_MILLIS)).togetherWith(ExitTransition.None)
                                .apply { targetContentZIndex = 1f }
                        } else {
                            EnterTransition.None.togetherWith(fadeOut(tween(PHOTO_TRANSITION_MILLIS)))
                                .apply { targetContentZIndex = 0f }
                        }
                    } else {
                        val entering = if (targetState.screen == "gallery") {
                            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(260))
                        } else {
                            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(260))
                        }
                        val leaving = if (targetState.screen == "gallery") {
                            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(260))
                        } else {
                            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(260))
                        }
                        (entering + fadeIn(tween(180))).togetherWith(leaving + fadeOut(tween(140)))
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) { target ->
                CompositionLocalProvider(LocalPhotoTransition provides PhotoTransitionScope(
                    this@SharedTransitionLayout, this@AnimatedContent, target.screen == destination,
                    returningMediaId = navigation.currentState.media?.id.takeIf { destination == "gallery" },
                )) {
                    screenState.SaveableStateProvider(target.screen) {
                        if (target.screen == "gallery") {
                            Box(Modifier.fillMaxSize().drawWithContent {
                                // Keep the settled gallery for interactive dismissal, but let shared
                                // elements draw directly during navigation so their return bounds survive.
                                if (!navigation.isRunning && destination == "gallery") {
                                    galleryLayer.record { this@drawWithContent.drawContent() }
                                }
                                drawContent()
                            }) {
                            GalleryScreen(
                                libraryActions = libraryActions,
                                onQueryChange = onQueryChange, onToggleSelection = onToggleSelection, onSelectAll = onSelectAll,
                                onClearSelection = onClearSelection, onBatch = onBatch, onRetryBatch = onRetryBatch,
                                state = state,
                                onRefresh = onRefresh,
                                onSelectFilter = onSelectFilter,
                                onSelectMedia = onSelectMedia,
                                onRequestPreview = onRequestPreview,
                                onRequestDevicePhotos = onRequestDevicePhotos,
                                onPickUpload = onPickUpload,
                                onContinuePastUnresolved = onContinuePastUnresolved,
                                onOpenAccount = onOpenAccount,
                                onBottomSpaceChanged = { galleryBottomSpace = it },
                            )
                            }
                        } else if (target.screen == "account") {
                            ICloudAccountScreen(
                                busy = state.accountBusy,
                                diagnostics = state, onCheckConnection = libraryActions.checkConnection,
                                libraryActions = libraryActions,
                                restoreFailed = !state.isRefreshing,
                                onRetryRestore = onRefresh,
                                state = state.accountState,
                                onBack = onCloseAccount,
                                onSignIn = onSignIn,
                                onSubmitTwoFactor = onSubmitTwoFactor,
                                onResendTwoFactor = onResendTwoFactor,
                                onSignOut = onSignOut,
                                backup = state.backup,
                                onEnableAutomaticBackup = onEnableAutomaticBackup,
                                onDisableAutomaticBackup = onDisableAutomaticBackup,
                            )
                        } else {
                            // AnimatedContent retains the outgoing snapshot until the image reaches its tile.
                            val selectedMedia = target.media ?: return@SaveableStateProvider
                            val viewerItems = if (target.items.any { it.id == selectedMedia.id }) target.items else listOf(selectedMedia) + target.items
                            val downloadingOriginal = state.batches.any { it.mediaId == selectedMedia.id &&
                                it.action == dev.mela.engine.companion.BatchAction.KEEP_OFFLINE.name && it.state in setOf("WAITING", "RUNNING") }
                            val stagingUpload = state.batches.any { it.mediaId == selectedMedia.id &&
                                it.action == dev.mela.engine.companion.BatchAction.UPLOAD.name && it.state in setOf("WAITING", "RUNNING") }
                            MediaDetailScreen(
                                currentAlbumId = state.query.collectionId,
                                gallerySnapshot = galleryLayer,
                                playback = playback,
                                onViewerLightBars = onViewerLightBars,
                                libraryActions = libraryActions, collections = state.collections,
                                media = selectedMedia, items = viewerItems,
                                select = { if (destination == "media") onSelectMedia(it) }, preview = onRequestPreview,
                                isWorking = state.activeDownloadId != null || state.accountBusy || downloadingOriginal || stagingUpload,
                                isDownloadingOriginal = downloadingOriginal,
                                onBack = onCloseDetail,
                                onKeepOriginalOffline = { onKeepOriginalOffline(selectedMedia.id) },
                                onRemoveCachedOriginal = { onRemoveCachedOriginal(selectedMedia.id) },
                                onRequestUpload = { onRequestUpload(selectedMedia.id) },
                                onContinuePastUnresolved = {
                                    state.uploadTransfersByMediaId[selectedMedia.id]?.let { transfer ->
                                        onContinuePastUnresolved(transfer.id)
                                    }
                                },
                                onMoveVerifiedPhotoToTrash = { onMoveVerifiedPhotoToTrash(selectedMedia.id) },
                                onOpenAccount = onOpenAccount,
                                accountState = state.accountState,
                                uploadTransfer = state.uploadTransfersByMediaId[selectedMedia.id],
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryScreen(
    libraryActions: LibraryActions,
    onQueryChange: (GalleryQuery) -> Unit, onToggleSelection: (String) -> Unit, onSelectAll: () -> Unit,
    onClearSelection: () -> Unit, onBatch: (BatchAction) -> Unit, onRetryBatch: (String) -> Unit,
    state: GalleryUiState,
    onRefresh: () -> Unit,
    onSelectFilter: (GalleryFilter) -> Unit,
    onSelectMedia: (String) -> Unit,
    onRequestPreview: (String) -> Unit,
    onRequestDevicePhotos: () -> Unit,
    onPickUpload: () -> Unit,
    onContinuePastUnresolved: (TransferId) -> Unit,
    onOpenAccount: () -> Unit,
    onBottomSpaceChanged: (Dp) -> Unit,
) {
    GalleryHome(state, libraryActions, onQueryChange, onToggleSelection, onSelectAll, onClearSelection,
        onBatch, onRetryBatch, onRefresh, onSelectMedia, onRequestPreview, onRequestDevicePhotos,
        onPickUpload, onContinuePastUnresolved, onOpenAccount, onBottomSpaceChanged)
}

@Composable
internal fun TransferQueueCard(
    transfers: List<TransferView>,
    onContinuePastUnresolved: (TransferId) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .testTag("transfer-queue"),
        colors = CardDefaults.cardColors(containerColor = melaGroupColor()),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(stringResource(R.string.uploads), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            transfers.forEachIndexed { index, transfer ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = transfer.state.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = transfer.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = transfer.message?.let { dev.mela.app.localizedStoredMessage(it).localized() } ?: transfer.state.label(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (transfer.state == TransferViewState.UNRESOLVED) {
                    Spacer(Modifier.height(9.dp))
                    OutlinedButton(
                        onClick = { onContinuePastUnresolved(transfer.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.keep_this_item_blocked_continue_others))
                    }
                }
                if (index != transfers.lastIndex) {
                    Spacer(Modifier.height(9.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(9.dp))
                }
            }
        }
    }
}

@Composable
internal fun MonthHeader(month: YearMonth, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 10.dp, start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = monthLabel(month),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = itemCount(count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun MediaTile(
    retryVersion: Int = 0,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    media: GalleryMedia,
    onClick: () -> Unit,
    onVisible: () -> Unit,
    selectionAction: () -> Unit = onLongClick ?: {},
) {
    LaunchedEffect(media.id, media.previewReference, retryVersion) {
        if (media.origin == MediaOrigin.ICLOUD && media.previewReference == null) {
            onVisible()
        }
    }

    val description = stringResource(R.string.media_description, media.fileName, media.availability.label())
    val selectLabel = stringResource(R.string.select_photo)
    Surface(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .aspectRatio(1f)
            .testTag("media-${media.id}")
            .semantics {
                onLongClick(label = selectLabel) { selectionAction(); true }
                this.selected = selected
                contentDescription = description
            },
        shape = RoundedCornerShape(2.dp),
        tonalElevation = 1.dp,
    ) {
        Box {
            MediaThumbnail(
                retryVersion = retryVersion,
                sharedMediaId = media.id,
                reference = media.coverReference(),
                sourceRevision = media.sourceRevision,
                accentStartArgb = media.accentStartArgb,
                accentEndArgb = media.accentEndArgb,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
            if (media.kind == MediaKind.VIDEO || media.availability == MediaAvailability.ORIGINAL_CACHED) Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(44.dp).background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .6f)))))
            if (media.availability == MediaAvailability.ORIGINAL_CACHED) Icon(
                MelaIcons.DownloadForOffline, contentDescription = stringResource(R.string.available_offline), tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(18.dp))
            if (media.kind == MediaKind.VIDEO) {
                val duration = mediaDuration(media.durationMillis)
                if (duration != null) Text(duration, color = Color.White, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 7.dp, vertical = 5.dp))
                else Icon(androidx.compose.material.icons.Icons.Filled.PlayArrow, stringResource(R.string.videos), tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(18.dp))
            }
            if (media.kind == MediaKind.LIVE_PHOTO) Row(
                Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha = .65f), CircleShape).padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.icloud_live_photos), null, Modifier.size(13.dp), tint = Color.White)
                Text(stringResource(R.string.live_badge), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            if (media.isFavorite && !selected) Icon(Icons.Filled.Favorite,
                contentDescription = stringResource(R.string.favorite), tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp))
            if (selected) Text("✓", color = Color.White, modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.primary).padding(8.dp))
        }
    }
}

@Composable
private fun AvailabilityBadge(
    availability: MediaAvailability,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.62f),
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = availability.icon(),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = availability.shortLabel(),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
internal fun EmptyLibrary() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 72.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            MelaIcons.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(42.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.no_photos_in_this_view), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun DeviceUploadControls(
    uploadTransfer: TransferView?,
    isSignedIn: Boolean,
    isWorking: Boolean,
    onRequestUpload: () -> Unit,
    onContinuePastUnresolved: () -> Unit,
    onMoveVerifiedPhotoToTrash: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    if (uploadTransfer == null) {
        if (isSignedIn) {
            Button(
                onClick = onRequestUpload,
                enabled = !isWorking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(MelaIcons.CloudUpload, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (isWorking) stringResource(R.string.staging_original) else stringResource(R.string.upload_to_icloud))
            }
        } else {
            OutlinedButton(
                onClick = onOpenAccount,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sign_in_to_upload))
            }
        }
        return
    }

    Column {
        // Verified status is already shown in the metadata card above these actions.
        if (uploadTransfer.state != TransferViewState.VERIFIED) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = uploadTransfer.state.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uploadTransfer.state.label(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = uploadTransfer.message?.let { dev.mela.app.localizedStoredMessage(it).localized() } ?: stringResource(R.string.staged_bytes, android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, uploadTransfer.byteCount)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (uploadTransfer.state == TransferViewState.NEEDS_SIGN_IN) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onOpenAccount,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Lock, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.open_icloud_account))
                        }
                    }
                }
            }
        }
        if (uploadTransfer.state == TransferViewState.VERIFIED) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onMoveVerifiedPhotoToTrash,
                enabled = !isWorking && Build.VERSION.SDK_INT >= 30,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("move-phone-original-to-trash"),
            ) {
                Icon(MelaIcons.DeleteSweep, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isWorking) stringResource(R.string.rechecking_exact_originals) else stringResource(R.string.move_phone_original_to_trash))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (Build.VERSION.SDK_INT < 30) stringResource(R.string.android_11_or_later_is_required_for_verified_trash) else stringResource(R.string.mela_rechecks_the_current_phone_and_icloud_bytes_first_android_then_asks_you_to_confi),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (uploadTransfer.state == TransferViewState.UNRESOLVED) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onContinuePastUnresolved,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.keep_this_item_blocked_continue_others))
            }
        }
    }
}

private fun MediaAvailability.icon(): ImageVector = when (this) {
    MediaAvailability.CLOUD_ONLY -> MelaIcons.CloudQueue
    MediaAvailability.PREVIEW_CACHED -> MelaIcons.CloudDone
    MediaAvailability.ORIGINAL_CACHED -> MelaIcons.DownloadForOffline
    MediaAvailability.DEVICE_ORIGINAL -> MelaIcons.PhoneAndroid
}

@Composable
private fun MediaAvailability.shortLabel(): String = when (this) {
    MediaAvailability.CLOUD_ONLY -> stringResource(R.string.cloud)
    MediaAvailability.PREVIEW_CACHED -> stringResource(R.string.preview)
    MediaAvailability.ORIGINAL_CACHED -> stringResource(R.string.offline)
    MediaAvailability.DEVICE_ORIGINAL -> stringResource(R.string.phone)
}

@Composable
internal fun MediaAvailability.label(): String = when (this) {
    MediaAvailability.CLOUD_ONLY -> stringResource(R.string.cloud_only)
    MediaAvailability.PREVIEW_CACHED -> stringResource(R.string.preview_cached)
    MediaAvailability.ORIGINAL_CACHED -> stringResource(R.string.original_kept_offline)
    MediaAvailability.DEVICE_ORIGINAL -> stringResource(R.string.original_on_this_phone)
}

private fun TransferViewState.icon(): ImageVector = when (this) {
    TransferViewState.WAITING -> MelaIcons.CloudUpload
    TransferViewState.UPLOADING -> MelaIcons.CloudUpload
    TransferViewState.CHECKING_ICLOUD -> MelaIcons.CloudQueue
    TransferViewState.VERIFIED -> MelaIcons.CloudDone
    TransferViewState.UNRESOLVED -> MelaIcons.ErrorOutline
    TransferViewState.SKIPPED -> MelaIcons.ErrorOutline
    TransferViewState.NEEDS_SIGN_IN -> Icons.Outlined.Lock
    TransferViewState.NEEDS_ATTENTION -> MelaIcons.ErrorOutline
    TransferViewState.UNSUPPORTED -> MelaIcons.ErrorOutline
}

@Composable
private fun TransferViewState.label(): String = when (this) {
    TransferViewState.WAITING -> stringResource(R.string.waiting_to_upload)
    TransferViewState.UPLOADING -> stringResource(R.string.uploading)
    TransferViewState.CHECKING_ICLOUD -> stringResource(R.string.checking_icloud)
    TransferViewState.VERIFIED -> stringResource(R.string.verified_in_icloud)
    TransferViewState.UNRESOLVED -> stringResource(R.string.upload_result_unresolved)
    TransferViewState.SKIPPED -> stringResource(R.string.uncertain_item_kept_blocked)
    TransferViewState.NEEDS_SIGN_IN -> stringResource(R.string.needs_icloud_sign_in)
    TransferViewState.NEEDS_ATTENTION -> stringResource(R.string.needs_attention)
    TransferViewState.UNSUPPORTED -> stringResource(R.string.unsupported_original)
}
