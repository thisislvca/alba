package dev.mela.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.mela.app.*
import dev.mela.app.R
import dev.mela.engine.model.GalleryMedia
import java.io.File
import kotlinx.coroutines.*
import kotlin.math.hypot

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhotoEditorScreen(media: GalleryMedia, source: suspend (GalleryMedia) -> File, close: () -> Unit, saved: (android.net.Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<File?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var adjustments by rememberSaveable { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var smallerCopy by remember { mutableStateOf<SmallerPhotoCopyRequired?>(null) }
    var pendingCopyPath by rememberSaveable { mutableStateOf<String?>(null) }
    val edit = rememberSaveable(media.id, saver = PhotoEditState.Saver) { PhotoEditState() }
    val crop = edit.current.crop
    val (left, top, right, bottom) = crop
    val turns = edit.current.turns
    val requestClose: () -> Unit = { if (!saving) { if (edit.dirty) discard = true else close() } }
    val edges = listOf(R.string.crop_left_edge, R.string.crop_top_edge, R.string.crop_right_edge, R.string.crop_bottom_edge).map { stringResource(it) }
    val cropDescription = stringResource(R.string.crop_bounds, (left * 100).toInt(), (top * 100).toInt(), (right * 100).toInt(), (bottom * 100).toInt())
    val adjustmentLabels = edges.map { stringResource(R.string.crop_move_outward, it) to stringResource(R.string.crop_move_inward, it) }
    val cropActions = adjustmentLabels.flatMapIndexed { edge, (outward, inward) ->
        val direction = if (edge < 2) 1f else -1f
        listOf(CustomAccessibilityAction(outward) { edit.adjustEdge(edge, -.025f * direction); true },
            CustomAccessibilityAction(inward) { edit.adjustEdge(edge, .025f * direction); true })
    }
    LaunchedEffect(media.id, retry) {
        busy = true; failed = false
        var owned: File? = null
        try {
            owned = source(media); file = owned
            preview = withContext(Dispatchers.IO) { loadThumbnail(context, owned.path, 1600, true) }
            check(preview != null)
        } catch (e: CancellationException) { owned?.delete(); throw e }
        catch (_: Exception) { owned?.delete(); file = null; failed = true }
        finally { busy = false }
    }
    DisposableEffect(Unit) { onDispose { file?.delete() } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { document ->
        val prepared = pendingCopyPath?.let(::File)
        pendingCopyPath = null
        if (document == null) { prepared?.delete(); busy = false }
        else scope.launch {
            busy = true; saving = true; failed = false
            try {
                val uri = publishEditedCopy(context, media, requireNotNull(prepared), document)
                close(); saved(uri)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { prepared?.delete(); busy = false; saving = false }
        }
    }
    fun save(allowSmaller: Boolean = false) {
        val input = file ?: return
        scope.launch {
            busy = true; saving = true; failed = false
            try {
                val output = renderPhotoEdit(input, crop, turns, allowSmallerCopy = allowSmaller)
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val uri = try { saveEditedCopy(context, media, output, source = input) } finally { output.recycle() }
                    close(); saved(uri)
                } else {
                    val prepared = try { prepareEditedCopy(context, output, input) } finally { output.recycle() }
                    pendingCopyPath = prepared.path
                    exporter.launch(media.fileName.substringBeforeLast('.') + "-edited.jpg")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: SmallerPhotoCopyRequired) { smallerCopy = e }
            catch (_: Exception) { failed = true }
            finally { busy = false; saving = false }
        }
    }
    Dialog(onDismissRequest = requestClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogView = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(dialogView) {
            (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window, dialogView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
            onDispose { }
        }
        dev.mela.app.ui.theme.MelaTheme(darkTheme = true) {
        if (discard) AlertDialog(onDismissRequest = { discard = false },
            title = { Text(stringResource(R.string.discard_edits_title)) },
            text = { Text(stringResource(R.string.discard_edits_explanation)) },
            confirmButton = { TextButton(onClick = { discard = false; close() }) { Text(stringResource(R.string.discard_edits)) } },
            dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.keep_editing)) } })
        smallerCopy?.let { size -> AlertDialog(
            onDismissRequest = { smallerCopy = null },
            title = { Text(stringResource(R.string.save_smaller_copy)) },
            text = { Text(stringResource(R.string.smaller_copy_explanation, size.width, size.height)) },
            confirmButton = { TextButton(onClick = { smallerCopy = null; save(true) }) { Text(stringResource(R.string.save_copy)) } },
            dismissButton = { TextButton(onClick = { smallerCopy = null }) { Text(stringResource(R.string.cancel)) } },
        ) }
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = requestClose, enabled = !saving) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.weight(1f))
                    Button(enabled = !busy && file != null, onClick = { save() }, modifier = Modifier.testTag("save-edited-copy")) { Text(stringResource(R.string.save_copy)) }
                }
                Text(stringResource(R.string.edit_copy_explanation), style = MaterialTheme.typography.bodySmall)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val bitmap = preview
                    if (bitmap != null) {
                        val aspect = bitmap.width.toFloat() / bitmap.height
                        val width = if (turns % 2 == 0) minOf(maxWidth, maxHeight * aspect) else minOf(maxHeight, maxWidth * aspect)
                        Box(Modifier.requiredSize(width, width / aspect).graphicsLayer { rotationZ = turns * 90f }) {
                            Image(bitmap.asImageBitmap(), stringResource(R.string.crop_photo), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            val latestCrop by rememberUpdatedState(crop)
                            Canvas(Modifier.fillMaxSize().testTag("photo-crop").semantics {
                                stateDescription = cropDescription
                                customActions = if (busy) emptyList() else cropActions
                            }.pointerInput(bitmap, busy) {
                                if (busy) return@pointerInput
                                var corner = 0
                                detectDragGestures(onDragStart = { point ->
                                    edit.beginDrag()
                                    val c = latestCrop
                                    corner = listOf(Offset(c.left, c.top), Offset(c.right, c.top), Offset(c.right, c.bottom), Offset(c.left, c.bottom))
                                        .map { hypot(point.x - it.x * size.width, point.y - it.y * size.height) }.indices.minBy { i ->
                                            val p = listOf(Offset(c.left, c.top), Offset(c.right, c.top), Offset(c.right, c.bottom), Offset(c.left, c.bottom))[i]
                                            hypot(point.x - p.x * size.width, point.y - p.y * size.height)
                                        }
                                }, onDragEnd = edit::endDrag, onDragCancel = edit::cancelDrag) { change, _ ->
                                    change.consume()
                                    val x = (change.position.x / size.width).coerceIn(0f, 1f)
                                    val y = (change.position.y / size.height).coerceIn(0f, 1f)
                                    edit.dragCorner(corner, x, y)
                                }
                            }) {
                                val l = left * size.width; val t = top * size.height; val r = right * size.width; val b = bottom * size.height
                                val shade = Color.Black.copy(alpha = .58f)
                                drawRect(shade, size = Size(size.width, t)); drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
                                drawRect(shade, Offset(0f, t), Size(l, b - t)); drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
                                drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
                                listOf(Offset(l,t),Offset(r,t),Offset(r,b),Offset(l,b)).forEach { drawCircle(Color.White, 6.dp.toPx(), it) }
                            }
                        }
                    }
                    if (busy) CircularProgressIndicator()
                }
                // Explicit presets also make cropping usable without precise drag gestures.
                FlowRow(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF1C1C1E)).padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(enabled = !busy && edit.canUndo, onClick = edit::undo, modifier = Modifier.testTag("undo-edit")) { Text(stringResource(R.string.undo_edit)) }
                    TextButton(enabled = !busy && edit.dirty, onClick = { edit.change(EditStep()) }) { Text(stringResource(R.string.reset)) }
                    TextButton(enabled = !busy && preview != null, onClick = {
                        val aspect = preview!!.width.toFloat() / preview!!.height
                        val inset = if (aspect > 1) (1 - 1 / aspect) / 2 else (1 - aspect) / 2
                        edit.change(edit.current.copy(crop = if (aspect > 1) PhotoCrop(inset, 0f, 1-inset, 1f) else PhotoCrop(0f, inset, 1f, 1-inset)))
                    }) { Text(stringResource(R.string.square_crop)) }
                    TextButton(enabled = !busy, onClick = { edit.change(edit.current.copy(turns = (turns + 1) % 4)) }) { Text(stringResource(R.string.rotate_degrees, turns * 90)) }
                    TextButton(enabled = !busy, onClick = { adjustments = !adjustments }) { Text(stringResource(R.string.adjust_crop)) }
                }
                if (adjustments) Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    edges.forEachIndexed { edge, label ->
                        val direction = if (edge < 2) 1f else -1f
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f))
                            TextButton(enabled = !busy, onClick = { edit.adjustEdge(edge, -.025f * direction) },
                                modifier = Modifier.semantics { contentDescription = adjustmentLabels[edge].first }) { Text("−") }
                            TextButton(enabled = !busy, onClick = { edit.adjustEdge(edge, .025f * direction) },
                                modifier = Modifier.semantics { contentDescription = adjustmentLabels[edge].second }) { Text("+") }
                        }
                    }
                }
                if (turns != 0) Text(stringResource(R.string.rotation_on_save, turns * 90), style = MaterialTheme.typography.bodySmall)
                if (failed) TextButton(onClick = { if (file == null) retry++ else save() }, enabled = !busy) { Text(stringResource(R.string.edit_failed_retry)) }
            }
        }
        }
    }
}
