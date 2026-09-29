package dev.mela.app

import dev.mela.engine.source.UploadMediaFormat
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.mela.app.ui.localized
import dev.mela.app.ui.MediaThumbnail
import dev.mela.app.ui.VideoPlayer
import dev.mela.app.ui.theme.MelaTheme
import dev.mela.protocol.account.ICloudAccountState
import dev.mela.protocol.account.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class IncomingMediaRequest(val uris: List<String>, val external: Boolean = true, val savedCopy: Boolean = false)

@Suppress("DEPRECATION")
internal fun incomingMedia(intent: Intent): IncomingMediaRequest? {
    if (intent.action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
    val candidates: List<Uri> = when (intent.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }.ifEmpty { intent.clipData?.let { clip -> (0 until minOf(clip.itemCount, 51)).mapNotNull { clip.getItemAt(it).uri } }.orEmpty() }
    val values = candidates.distinct().map(Uri::toString)
    // Never turn a file path, network URL, or an unbounded incoming payload into a read.
    val safe = values.size in 1..50 && values.sumOf { it.length } <= 32_000 &&
        candidates.all { it.scheme == "content" && !it.authority.isNullOrBlank() }
    return IncomingMediaRequest(if (safe) values else emptyList())
}

private data class IncomingItem(val uri: Uri, val name: String, val mime: String?, val failure: Int? = null)
private suspend fun inspectIncoming(context: Context, value: String): IncomingItem = withContext(Dispatchers.IO) {
    val uri = value.toUri()
    try {
    val mime = context.contentResolver.getType(uri)
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0)?.take(255) else null
    } ?: context.getString(R.string.photos)
    if (mime == null) context.contentResolver.openInputStream(uri)?.use { /* distinguish lost access from an unsupported type */ }
    IncomingItem(uri, name, mime)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: SecurityException) { IncomingItem(uri, context.getString(R.string.photos), null, R.string.incoming_access_lost) }
    catch (_: java.io.FileNotFoundException) { IncomingItem(uri, context.getString(R.string.photos), null, R.string.incoming_file_missing) }
    catch (_: Exception) { IncomingItem(uri, context.getString(R.string.photos), null, R.string.incoming_unsupported) }
}

@Composable
internal fun IncomingMediaScreen(request: IncomingMediaRequest, state: GalleryUiState, close: () -> Unit,
    connect: () -> Unit, upload: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    val shareLabel = stringResource(R.string.share)
    val items by produceState<List<IncomingItem>?>(null, request) {
        value = request.uris.map { inspectIncoming(context, it) }
    }
    var confirm by rememberSaveable(request) { mutableStateOf(false) }
    var sent by rememberSaveable(request) { mutableStateOf(false) }
    val pager = rememberPagerState { items?.size ?: 0 }
    var zoomed by remember(request, pager.currentPage) { mutableStateOf(false) }
    var shareFailed by remember(request) { mutableStateOf(false) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(view) {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
                }
            }
            onDispose { }
        }
        MelaTheme(darkTheme = true) {
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
                                clipData = android.content.ClipData.newRawUri(item.name, item.uri)
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
                        Column(Modifier.fillMaxWidth().background(Color(0xFF1C1C1E), androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (loaded.size > 1) Text(stringResource(R.string.incoming_position, pager.currentPage + 1, loaded.size))
                            val eligible = loaded.filter { UploadMediaFormat.isCandidate(it.mime) }
                            if (!request.savedCopy && loaded.any { it.failure == null && it.mime != null }) {
                                Text(stringResource(R.string.incoming_upload_explanation), style = MaterialTheme.typography.bodySmall)
                                val signedIn = (state.accountState as? ICloudAccountState.SignedIn)?.status == SessionStatus.VERIFIED
                                if (eligible.isNotEmpty()) Button(modifier = Modifier.testTag("queue-incoming-upload"), enabled = !sent, onClick = { if (signedIn) confirm = true else connect() }) {
                                    Text(stringResource(if (sent) R.string.incoming_upload_queued else if (signedIn) R.string.upload_phone_jpegs else R.string.incoming_connect))
                                }
                                if (sent) TextButton(onClick = close) { Text(stringResource(R.string.incoming_view_library)) }
                                state.message?.let { Text(it.localized(), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
            if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.upload_phone_jpegs)) },
                text = { Text(androidx.compose.ui.res.pluralStringResource(R.plurals.incoming_upload_confirm, items.orEmpty().count { UploadMediaFormat.isCandidate(it.mime) }, items.orEmpty().count { UploadMediaFormat.isCandidate(it.mime) })) },
                confirmButton = { TextButton(modifier = Modifier.testTag("confirm-incoming-upload"), onClick = {
                    confirm = false; sent = true
                    upload(items.orEmpty().filter { UploadMediaFormat.isCandidate(it.mime) }.map { it.uri })
                }) { Text(stringResource(R.string.upload_phone_jpegs)) } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
        }
    }
}
