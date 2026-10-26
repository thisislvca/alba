package me.lvca.alba.app.ui.intake

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import me.lvca.alba.app.R
import me.lvca.alba.app.intake.IncomingItem
import me.lvca.alba.app.intake.IncomingRequest
import me.lvca.alba.app.intake.inspectIncoming
import me.lvca.alba.app.messages.UiMessage
import me.lvca.alba.app.ui.collections.AlbumChooser
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.shared.SharedPostSheet
import me.lvca.alba.app.ui.gallery.MediaThumbnail
import me.lvca.alba.app.ui.localized
import me.lvca.alba.app.ui.viewer.VideoPlayer
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.MediaKind
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.supportsLegacySharedUpload
import me.lvca.alba.engine.source.UploadMediaFormat
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus

/** Where adding incoming items to a shared album stands. */
internal enum class IncomingSharedResult { SENDING, ADDED, FAILED }

/** The incoming items [album] accepts: legacy albums take single JPEG, PNG, HEIC, MP4 or MOV files only. */
internal fun incomingForSharedAlbum(items: List<IncomingItem>, album: GalleryCollection): List<IncomingItem> =
    if (album.shared?.generation != SharedAlbumGeneration.LEGACY) items
    else items.filter { item ->
        val mime = item.mime.orEmpty()
        supportsLegacySharedUpload(if (mime.startsWith("video/")) MediaKind.VIDEO else MediaKind.PHOTO, mime)
    }

@Composable
internal fun IncomingMediaScreen(request: IncomingRequest.Media, accountState: ICloudAccountState, message: UiMessage?,
    close: () -> Unit, connect: () -> Unit, upload: (List<Uri>) -> Unit,
    sharedAlbums: List<GalleryCollection> = emptyList(),
    addToShared: (albumId: String, items: List<IncomingItem>, caption: String, done: (Boolean) -> Unit) -> Unit = { _, _, _, _ -> }) {
    val context = LocalContext.current
    val shareLabel = stringResource(R.string.share)
    val demo = accountState == ICloudAccountState.Demo
    val items by produceState<List<IncomingItem>?>(null, request) {
        value = request.uris.map { inspectIncoming(context, it) }
    }
    var confirm by rememberSaveable(request) { mutableStateOf(false) }
    var sent by rememberSaveable(request) { mutableStateOf(false) }
    val pager = rememberPagerState { items?.size ?: 0 }
    var zoomed by remember(request, pager.currentPage) { mutableStateOf(false) }
    var shareFailed by remember(request) { mutableStateOf(false) }
    // Where the items go: the iCloud library by default, or a shared album the person can add to.
    var toShared by rememberSaveable(request) { mutableStateOf(false) }
    var target by rememberSaveable(request) { mutableStateOf<String?>(null) }
    var choosing by rememberSaveable(request) { mutableStateOf(false) }
    var posting by rememberSaveable(request) { mutableStateOf(false) }
    var sharedResult by rememberSaveable(request) { mutableStateOf<IncomingSharedResult?>(null) }
    val targetAlbum = sharedAlbums.firstOrNull { it.id == target }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
                }
            }
            onDispose { }
        }
        AlbaTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize().testTag("incoming-media"), color = Color.Black, contentColor = Color.White) {
                Column(Modifier.fillMaxSize().statusBarsPadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick = close, modifier = Modifier.testTag("incoming-back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back_action)) }
                        Text(if (request.savedCopy) stringResource(R.string.edited_copy_saved) else items?.getOrNull(pager.currentPage)?.name.orEmpty(),
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 2)
                        items?.getOrNull(pager.currentPage)?.takeIf { it.failure == null &&
                            (it.mime?.startsWith("image/") == true || it.mime?.startsWith("video/") == true) }?.let { item -> IconButton(onClick = {
                            shareFailed = false
                            runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = item.mime; putExtra(Intent.EXTRA_STREAM, item.uri)
                                clipData = ClipData.newRawUri(item.name, item.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, shareLabel)) }.onFailure { shareFailed = true }
                        }) { Icon(Icons.Outlined.Share, stringResource(R.string.share)) } }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (items == null) CircularProgressIndicator()
                        else if (items!!.isEmpty()) Text(stringResource(R.string.incoming_unsupported), Modifier.padding(24.dp))
                        else HorizontalPager(pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize().testTag("incoming-pager")) { index ->
                            val item = items!![index]
                            when {
                                item.mime?.startsWith("video/") == true -> if (index == pager.settledPage) VideoPlayer(item.uri.toString(),
                                    reader = { _, _, _ -> error("Local playback only") }, localReference = item.uri.toString(), modifier = Modifier.fillMaxSize())
                                item.mime?.startsWith("image/") == true -> MediaThumbnail(item.uri.toString(), 0xFF171717, 0xFF171717, item.name,
                                    Modifier.fillMaxSize().testTag("incoming-image-$index"), contentScale = ContentScale.Fit, zoomable = true,
                                    onZoomChanged = { if (index == pager.currentPage) zoomed = it })
                                else -> Text(stringResource(item.failure ?: R.string.incoming_unsupported), Modifier.padding(24.dp))
                            }
                        }
                    }
                    if (shareFailed) Text(stringResource(R.string.message_no_app_could_open_the_share_sheet), Modifier.padding(16.dp))
                    items?.takeIf { it.isNotEmpty() }?.let { loaded ->
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large.copy(bottomStart = CornerSize(0), bottomEnd = CornerSize(0))).navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (loaded.size > 1) Text(stringResource(R.string.incoming_position, pager.currentPage + 1, loaded.size))
                            val eligible = loaded.filter { UploadMediaFormat.isCandidate(it.mime) }
                            if (!request.savedCopy && loaded.any { it.failure == null && it.mime != null }) {
                                Text(stringResource(if (demo) R.string.demo_upload_disclosure else R.string.incoming_upload_explanation), style = MaterialTheme.typography.bodySmall)
                                val signedIn = (accountState as? ICloudAccountState.SignedIn)?.status == SessionStatus.VERIFIED
                                val canChoose = (signedIn || demo) && eligible.isNotEmpty() && sharedAlbums.isNotEmpty() && !sent && sharedResult == null
                                if (canChoose) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.incoming_destination), style = MaterialTheme.typography.labelLarge)
                                    FilterChip(!toShared, { toShared = false }, label = { Text(stringResource(R.string.incoming_destination_library)) },
                                        modifier = Modifier.testTag("incoming-destination-library"))
                                    FilterChip(toShared, { toShared = true }, label = { Text(stringResource(R.string.incoming_destination_shared)) },
                                        modifier = Modifier.testTag("incoming-destination-shared"))
                                }
                                if (toShared && (canChoose || sharedResult != null)) {
                                    val sharedItems = targetAlbum?.let { incomingForSharedAlbum(eligible, it) }.orEmpty()
                                    if (sharedResult == null) {
                                        OutlinedButton(onClick = { choosing = true }, modifier = Modifier.testTag("incoming-choose-shared")) {
                                            Text(targetAlbum?.name ?: stringResource(R.string.incoming_choose_shared))
                                        }
                                        if (targetAlbum != null) Button(onClick = { posting = true }, enabled = sharedItems.isNotEmpty(),
                                            modifier = Modifier.testTag("incoming-add-to-shared")) { Text(stringResource(R.string.incoming_add_to_shared, targetAlbum.name)) }
                                    }
                                    sharedResult?.let { result ->
                                        val albumName = targetAlbum?.name.orEmpty()
                                        Text(stringResource(when (result) {
                                            IncomingSharedResult.SENDING -> R.string.incoming_adding_to_shared
                                            IncomingSharedResult.ADDED -> R.string.incoming_sent_shared
                                            IncomingSharedResult.FAILED -> R.string.incoming_shared_failed
                                        }, albumName), Modifier.testTag("incoming-shared-status"))
                                        if (result != IncomingSharedResult.SENDING) TextButton(onClick = close) { Text(stringResource(R.string.incoming_view_library)) }
                                    }
                                    if (posting && targetAlbum != null) SharedPostSheet(sharedItems.size, targetAlbum.shared?.generation == SharedAlbumGeneration.LEGACY,
                                        dismiss = { posting = false }) { caption ->
                                        posting = false; sharedResult = IncomingSharedResult.SENDING
                                        addToShared(targetAlbum.id, sharedItems, caption) { added -> sharedResult = if (added) IncomingSharedResult.ADDED else IncomingSharedResult.FAILED }
                                    }
                                    if (choosing) AlbumChooser(sharedAlbums, dismiss = { choosing = false }, create = {}, title = stringResource(R.string.incoming_choose_shared),
                                        canCreate = false, shared = true) { id -> target = id; choosing = false }
                                } else {
                                    if (eligible.isNotEmpty()) Button(modifier = Modifier.testTag("queue-incoming-upload"), enabled = !sent, onClick = { if (signedIn || demo) confirm = true else connect() }) {
                                        Text(stringResource(if (sent && demo) R.string.demo_import_requested else if (sent) R.string.incoming_upload_queued else if (demo) R.string.demo_upload_title else if (signedIn) R.string.upload_phone_jpegs else R.string.incoming_connect))
                                    }
                                    if (sent && !demo) Text(stringResource(R.string.incoming_sent_library), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("incoming-library-status"))
                                    if (sent) TextButton(onClick = close) { Text(stringResource(R.string.incoming_view_library)) }
                                }
                                if (canChoose) Text(stringResource(R.string.incoming_album_note), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                message?.let { Text(it.localized(), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
            if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(if (demo) R.string.demo_upload_title else R.string.upload_phone_jpegs)) },
                text = { Text(if (demo) stringResource(R.string.demo_upload_disclosure) else pluralStringResource(R.plurals.incoming_upload_confirm, items.orEmpty().count { UploadMediaFormat.isCandidate(it.mime) }, items.orEmpty().count { UploadMediaFormat.isCandidate(it.mime) })) },
                confirmButton = { TextButton(modifier = Modifier.testTag("confirm-incoming-upload"), onClick = {
                    confirm = false; sent = true
                    upload(items.orEmpty().filter { UploadMediaFormat.isCandidate(it.mime) }.map { it.uri })
                }) { Text(stringResource(if (demo) R.string.demo_upload_title else R.string.upload_phone_jpegs)) } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
        }
    }
}
