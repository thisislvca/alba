package me.lvca.alba.app.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.DiagnosticsConsent
import me.lvca.alba.app.GalleryState
import me.lvca.alba.app.MediaWork
import me.lvca.alba.app.PhoneStorageState
import me.lvca.alba.app.R
import me.lvca.alba.app.SessionState
import me.lvca.alba.app.TransferState
import me.lvca.alba.app.UiMessage
import me.lvca.alba.engine.companion.BatchAction
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaKind
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.TransferId
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState
import java.time.YearMonth
import androidx.compose.material3.AlertDialog
import me.lvca.alba.app.localizedStoredMessage
import android.text.format.Formatter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.Check
import androidx.compose.ui.res.painterResource
import me.lvca.alba.app.ui.theme.AlbaTheme
import me.lvca.alba.app.ui.theme.tile

/**
 * Where the app is. Derived from the account screen flag and the open photo, which the ViewModel keeps in saved
 * state, so the destination survives process death without saving it separately.
 */
internal sealed interface Destination {
    /** Names the destination's saved screen state and animated content. */
    val key: String

    data object Gallery : Destination { override val key = "gallery" }
    data object Account : Destination { override val key = "account" }

    /** The viewer, with the gallery's items at the time, so an outgoing viewer keeps its snapshot while it animates. */
    data class Media(val media: GalleryMedia, val items: List<GalleryMedia>) : Destination { override val key = MEDIA_KEY }

    companion object {
        fun of(session: SessionState, gallery: GalleryState): Destination = when {
            session.isAccountOpen -> Account
            gallery.selectedMedia != null -> Media(gallery.selectedMedia, gallery.items)
            else -> Gallery
        }
    }
}

private const val MEDIA_KEY = "media"

/** What the system back gesture does at the top level, before a screen's own back handling. */
internal enum class RootBack { CLEAR_SELECTION, CLOSE_ACCOUNT, CLOSE_MEDIA }

internal fun rootBack(destination: Destination, hasSelection: Boolean): RootBack? = when {
    hasSelection -> RootBack.CLEAR_SELECTION
    destination is Destination.Account -> RootBack.CLOSE_ACCOUNT
    destination is Destination.Media -> RootBack.CLOSE_MEDIA
    else -> null
}

@Composable
fun AlbaApp(
    gallery: GalleryState,
    session: SessionState,
    transfers: TransferState,
    phoneStorage: PhoneStorageState,
    mediaWork: MediaWork,
    previewRetryVersion: Int,
    message: UiMessage?,
    diagnostics: DiagnosticsConsent,
    actions: AlbaActions,
    onViewerLightBars: (Boolean) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val screenState = rememberSaveableStateHolder()
    val galleryLayer = rememberGraphicsLayer()

    val localizedMessage = message?.localized()
    LaunchedEffect(localizedMessage) {
        val text = localizedMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        actions.dismissMessage()
    }

    if (mediaWork.sharing) AlertDialog(
        onDismissRequest = actions::cancelShare, title = { Text(stringResource(R.string.preparing_to_share)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.downloading_original_files_when_needed_live_photos_share_their_still_image_use_zip_ex))
        } }, confirmButton = { TextButton(onClick = actions::cancelShare) { Text(stringResource(R.string.cancel)) } })

    val destination = Destination.of(session, gallery)
    val back = rootBack(destination, gallery.selection.isNotEmpty())
    BackHandler(enabled = back != null) {
        when (back) {
            RootBack.CLEAR_SELECTION -> actions.clearSelection()
            RootBack.CLOSE_ACCOUNT -> actions.closeAccount()
            RootBack.CLOSE_MEDIA -> actions.closeMedia()
            null -> Unit
        }
    }

    var viewerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(destination.key) {
        if (destination is Destination.Media) viewerWasOpen = true
        // Discard only after an actual exit, not while the initial selection is restoring.
        if (destination is Destination.Gallery && viewerWasOpen) {
            screenState.removeState(MEDIA_KEY)
            viewerWasOpen = false
        }
    }
    val navigation = updateTransition(destination, label = "photo-navigation")
    var galleryBottomSpace by remember { mutableStateOf(100.dp) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            // Floating controls are inside the gallery rather than Scaffold.bottomBar.
            // Keep status messages above their measured height, including large text/selection.
            SnackbarHost(snackbarHostState, Modifier.imePadding().padding(bottom =
                if (destination is Destination.Gallery) galleryBottomSpace else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
                Snackbar(it, modifier = Modifier.testTag("status-snackbar"))
            }
        },
    ) { padding ->
        SharedTransitionLayout {
            navigation.AnimatedContent(
                contentKey = { it.key },
                transitionSpec = {
                    if (initialState !is Destination.Account && targetState !is Destination.Account) {
                        // Keep the gallery opaque underneath the viewer. Fading both screens exposes
                        // the scaffold between them and produces a pale flash.
                        if (targetState is Destination.Media) {
                            fadeIn(tween(PHOTO_TRANSITION_MILLIS)).togetherWith(ExitTransition.None)
                                .apply { targetContentZIndex = 1f }
                        } else {
                            EnterTransition.None.togetherWith(fadeOut(tween(PHOTO_TRANSITION_MILLIS)))
                                .apply { targetContentZIndex = 0f }
                        }
                    } else {
                        val direction = if (targetState is Destination.Gallery) AnimatedContentTransitionScope.SlideDirection.Right
                            else AnimatedContentTransitionScope.SlideDirection.Left
                        (slideIntoContainer(direction, tween(260)) + fadeIn(tween(180)))
                            .togetherWith(slideOutOfContainer(direction, tween(260)) + fadeOut(tween(140)))
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) { target ->
                CompositionLocalProvider(LocalPhotoTransition provides PhotoTransitionScope(
                    this@SharedTransitionLayout, this@AnimatedContent, target.key == destination.key,
                    returningMediaId = (navigation.currentState as? Destination.Media)?.media?.id
                        .takeIf { destination is Destination.Gallery },
                )) {
                    screenState.SaveableStateProvider(target.key) {
                        when (target) {
                            Destination.Gallery -> Box(Modifier.fillMaxSize().drawWithContent {
                                // Keep the settled gallery for interactive dismissal, but let shared
                                // elements draw directly during navigation so their return bounds survive.
                                if (!navigation.isRunning && destination is Destination.Gallery) {
                                    galleryLayer.record { this@drawWithContent.drawContent() }
                                }
                                drawContent()
                            }) {
                                GalleryHome(
                                    gallery = gallery,
                                    session = session,
                                    transfers = transfers,
                                    mediaBusy = mediaWork.busy,
                                    previewRetryVersion = previewRetryVersion,
                                    actions = actions,
                                    onBottomSpaceChanged = { galleryBottomSpace = it },
                                )
                            }
                            Destination.Account -> ICloudAccountScreen(
                                session = session,
                                transfers = transfers,
                                phoneStorage = phoneStorage,
                                mediaBusy = mediaWork.busy,
                                diagnostics = diagnostics,
                                actions = actions,
                            )
                            is Destination.Media -> {
                                // AnimatedContent retains the outgoing snapshot until the image reaches its tile.
                                val media = target.media
                                val viewerItems = if (target.items.any { it.id == media.id }) target.items else listOf(media) + target.items
                                val downloadingOriginal = transfers.batches.any { it.mediaId == media.id &&
                                    it.action == BatchAction.KEEP_OFFLINE.name && it.status.isPending }
                                val stagingUpload = transfers.batches.any { it.mediaId == media.id &&
                                    it.action == BatchAction.UPLOAD.name && it.status.isPending }
                                MediaDetailScreen(
                                    media = media,
                                    items = viewerItems,
                                    collections = gallery.collections,
                                    currentAlbumId = gallery.query.collectionId,
                                    accountState = session.accountState,
                                    uploadTransfer = transfers.uploadsByMediaId[media.id],
                                    isWorking = mediaWork.busy || session.accountBusy || downloadingOriginal || stagingUpload,
                                    isDownloadingOriginal = downloadingOriginal,
                                    actions = actions,
                                    onPageSettled = { if (destination is Destination.Media) actions.openMedia(it) },
                                    gallerySnapshot = galleryLayer,
                                    onViewerLightBars = onViewerLightBars,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TransferQueueCard(
    transfers: List<TransferView>,
    onContinuePastUnresolved: (TransferId) -> Unit,
    demo: Boolean = false,
) {
    AlbaCard(
        modifier = Modifier
            .padding(bottom = 12.dp)
            .testTag("transfer-queue"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(stringResource(if (demo) R.string.demo_backup_title else R.string.uploads), style = MaterialTheme.typography.titleMedium)
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
                            text = if (demo) stringResource(when {
                                transfer.message == "demo-wifi" -> R.string.demo_backup_wifi
                                transfer.message == "demo-cleaned" -> R.string.demo_cleanup_complete
                                transfer.state == TransferViewState.NEEDS_ATTENTION -> R.string.demo_backup_interrupted
                                else -> when (transfer.state) {
                                TransferViewState.WAITING -> R.string.demo_backup_waiting
                                TransferViewState.UPLOADING -> R.string.demo_backup_copying
                                TransferViewState.CHECKING_ICLOUD -> R.string.demo_backup_checking
                                TransferViewState.VERIFIED -> R.string.demo_backup_complete
                                else -> R.string.demo_backup_off
                            } }) else transfer.message?.let { localizedStoredMessage(it).localized() } ?: transfer.state.label(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (transfer.state == TransferViewState.UNRESOLVED || (demo && transfer.message in setOf("demo-interrupted", "demo-wifi"))) {
                    Spacer(Modifier.height(9.dp))
                    OutlinedButton(
                        onClick = { onContinuePastUnresolved(transfer.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(if (demo) R.string.demo_backup_retry else R.string.keep_this_item_blocked_continue_others))
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
        shape = MaterialTheme.shapes.tile,
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
            val onMedia = AlbaTheme.colors.onMedia
            if (media.kind == MediaKind.VIDEO || media.availability == MediaAvailability.ORIGINAL_CACHED)
                MediaScrim(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(44.dp))
            if (media.availability == MediaAvailability.ORIGINAL_CACHED) Icon(
                AlbaIcons.DownloadForOffline, contentDescription = stringResource(R.string.available_offline), tint = onMedia,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(18.dp))
            if (media.kind == MediaKind.VIDEO) {
                val duration = mediaDuration(media.durationMillis)
                if (duration != null) Text(duration, color = onMedia, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 7.dp, vertical = 5.dp))
                else Icon(Icons.Filled.PlayArrow, stringResource(R.string.videos), tint = onMedia,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(18.dp))
            }
            if (media.kind == MediaKind.LIVE_PHOTO) MediaPill(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                Icon(painterResource(R.drawable.icloud_live_photos), null, Modifier.size(13.dp))
                Text(stringResource(R.string.live_badge), style = MaterialTheme.typography.labelSmall)
            }
            if (media.isFavorite && !selected) FavoriteIcon(true,
                contentDescription = stringResource(R.string.favorite), tint = onMedia,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp))
            if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.primary).padding(6.dp).size(18.dp))
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
            AlbaIcons.PhotoLibrary,
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
    demo: Boolean = false,
    isWorking: Boolean,
    onRequestUpload: () -> Unit,
    onContinuePastUnresolved: () -> Unit,
    onMoveVerifiedPhotoToTrash: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    if (uploadTransfer == null) {
        if (isSignedIn || demo) {
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
                    Icon(AlbaIcons.CloudUpload, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (isWorking) stringResource(R.string.staging_original) else stringResource(if (demo) R.string.demo_upload_title else R.string.upload_to_icloud))
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
            AlbaCard(
                shape = MaterialTheme.shapes.medium,
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
                                text = uploadTransfer.message?.let { localizedStoredMessage(it).localized() } ?: stringResource(R.string.staged_bytes, Formatter.formatShortFileSize(LocalContext.current, uploadTransfer.byteCount)),
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
                Icon(AlbaIcons.DeleteSweep, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isWorking) stringResource(R.string.rechecking_exact_originals) else stringResource(R.string.move_phone_original_to_trash))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (Build.VERSION.SDK_INT < 30) stringResource(R.string.android_11_or_later_is_required_for_verified_trash) else stringResource(R.string.alba_rechecks_the_current_phone_and_icloud_bytes_first_android_then_asks_you_to_confi),
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

@Composable
internal fun MediaAvailability.label(): String = when (this) {
    MediaAvailability.CLOUD_ONLY -> stringResource(R.string.cloud_only)
    MediaAvailability.PREVIEW_CACHED -> stringResource(R.string.preview_cached)
    MediaAvailability.ORIGINAL_CACHED -> stringResource(R.string.original_kept_offline)
    MediaAvailability.DEVICE_ORIGINAL -> stringResource(R.string.original_on_this_phone)
}

private fun TransferViewState.icon(): ImageVector = when (this) {
    TransferViewState.WAITING -> AlbaIcons.CloudUpload
    TransferViewState.UPLOADING -> AlbaIcons.CloudUpload
    TransferViewState.CHECKING_ICLOUD -> AlbaIcons.CloudQueue
    TransferViewState.VERIFIED -> AlbaIcons.CloudDone
    TransferViewState.UNRESOLVED -> AlbaIcons.ErrorOutline
    TransferViewState.SKIPPED -> AlbaIcons.ErrorOutline
    TransferViewState.NEEDS_SIGN_IN -> Icons.Outlined.Lock
    TransferViewState.NEEDS_ATTENTION -> AlbaIcons.ErrorOutline
    TransferViewState.UNSUPPORTED -> AlbaIcons.ErrorOutline
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
