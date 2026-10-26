package me.lvca.alba.app.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.*
import me.lvca.alba.app.R
import me.lvca.alba.app.editing.Adjustment
import me.lvca.alba.app.editing.Adjustments
import me.lvca.alba.app.platform.decodeEditPreview
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion
import me.lvca.alba.engine.model.GalleryMedia
import java.io.File
import kotlin.math.hypot

private const val PREVIEW_SIDE = 1600

/**
 * Edits a photo into a new JPEG copy on the phone: Suggestions, Crop, Adjust and Filters, as in Google Photos. The
 * edit is kept as parameters, so undo, redo and the current tab survive rotation and process death. [canUpload] adds
 * "Save copy and upload to iCloud"; [saved] receives the copy and whether to upload it. Saving runs in [saves], so it
 * carries on through rotation and the recreated screen picks up its progress.
 */
@Composable
internal fun PhotoEditorScreen(
    media: GalleryMedia,
    source: suspend (GalleryMedia) -> File,
    close: () -> Unit,
    saved: (Uri, Boolean) -> Unit,
    canUpload: Boolean = false,
    saves: PhotoEditSaves = PhotoEditSaves.app(LocalContext.current),
) {
    var file by remember { mutableStateOf<File?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var saveId by rememberSaveable { mutableLongStateOf(0L) }
    var pendingUpload by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(EditorTab.SUGGESTIONS) }
    var tool by rememberSaveable { mutableStateOf(Adjustment.BRIGHTNESS) }
    var comparing by remember { mutableStateOf(false) }
    val edit = rememberSaveable(media.id, saver = PhotoEditState.Saver) { PhotoEditState() }
    val save = saves.state.collectAsState().value[saveId]
    val saving = save is EditSave.Running
    val busy = loading || saving
    val reducedMotion = reduceGalleryMotion()
    val requestClose: () -> Unit = { if (!saving) { if (edit.dirty) discard = true else close() } }
    LaunchedEffect(media.id, retry) {
        loading = true; loadFailed = false
        var owned: File? = null
        try {
            owned = source(media); file = owned
            preview = withContext(Dispatchers.IO) { decodeEditPreview(owned, PREVIEW_SIDE) }
        } catch (e: CancellationException) { owned?.delete(); throw e }
        catch (_: Exception) { owned?.delete(); file = null; loadFailed = true }
        finally { loading = false }
    }
    // A save in progress keeps reading the photo after this screen goes away; it deletes the file when it ends.
    DisposableEffect(Unit) { onDispose { file?.let(saves::release) } }
    DeliverSave(save, saves) { saveId = 0; close(); saved(it.uri, it.upload) }
    val toned = rememberTonedPreview(preview, edit.current.recipe.adjustments)
    val enhanced = rememberEnhancement(preview)
    val thumbnail = rememberFilterThumbnail(toned)
    fun startSave(allowSmaller: Boolean = false, upload: Boolean = pendingUpload) {
        val input = file ?: return
        if (saveId != 0L) saves.acknowledge(saveId)
        pendingUpload = upload
        saveId = saves.newId()
        saves.start(saveId, EditSaveRequest(media, input, edit.current, allowSmaller, upload))
    }
    fun dismissSave() { saves.acknowledge(saveId); saveId = 0 }
    Dialog(onDismissRequest = requestClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        LightSystemBarIcons()
        AlbaTheme(darkTheme = true) {
        EditorDialogs(discard, save as? EditSave.NeedsSmallerCopy, dismiss = { discard = false; if (save is EditSave.NeedsSmallerCopy) dismissSave() },
            discardEdits = { discard = false; close() }, saveSmaller = { startSave(allowSmaller = true) })
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorTopBar(edit, busy, saving, canSave = !busy && file != null, canUpload, requestClose,
                    save = { startSave(upload = false) }, saveAndUpload = { startSave(upload = true) })
                if (save is EditSave.Running) SaveProgress(save.progress)
                Text(stringResource(R.string.edit_copy_explanation), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    val original = preview
                    val shown = toned
                    if (original != null && shown != null) {
                        EditorImage(original, shown, edit.current, cropMode = tab == EditorTab.CROP, comparing = comparing,
                            onCompare = { comparing = it }, modifier = Modifier.fillMaxSize(), reducedMotion = reducedMotion) {
                            CropOverlay(edit, busy)
                        }
                    }
                    if (loading) CircularProgressIndicator()
                }
                Crossfade(tab, Modifier.fillMaxWidth().heightIn(min = 120.dp), animationSpec = if (reducedMotion) snap() else tween(150),
                    label = "editor-panel") { current ->
                    EditorPanel(current, edit, preview, thumbnail, enhanced, tool, onTool = { tool = it }, enabled = !busy)
                }
                EditFailedRetry(loadFailed || save is EditSave.Failed, enabled = !busy) { if (file == null) retry++ else startSave() }
                EditorTabs(tab, enabled = !saving) { edit.endDrag(); tab = it }
            }
        }
        }
    }
}

/** The editor's bottom tabs, in Google Photos' order. */
internal enum class EditorTab(val label: Int) {
    SUGGESTIONS(R.string.editor_tab_suggestions),
    CROP(R.string.editor_tab_crop),
    ADJUST(R.string.editor_tab_adjust),
    FILTERS(R.string.editor_tab_filters),
}

/** The editor is always dark, so the system bar icons are light. */
@Composable
private fun LightSystemBarIcons() {
    val dialogView = LocalView.current
    DisposableEffect(dialogView) {
        (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, dialogView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        onDispose { }
    }
}

@Composable
private fun EditorTabs(tab: EditorTab, enabled: Boolean, select: (EditorTab) -> Unit) {
    PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Black, contentColor = Color.White) {
        EditorTab.entries.forEach { entry ->
            Tab(selected = tab == entry, onClick = { select(entry) }, enabled = enabled,
                text = { Text(stringResource(entry.label), maxLines = 1) },
                modifier = Modifier.testTag("editor-tab-${entry.name.lowercase()}"))
        }
    }
}

@Composable
private fun EditorPanel(
    tab: EditorTab,
    edit: PhotoEditState,
    preview: Bitmap?,
    thumbnail: Bitmap?,
    enhanced: Adjustments?,
    tool: Adjustment,
    onTool: (Adjustment) -> Unit,
    enabled: Boolean,
) = when (tab) {
    EditorTab.SUGGESTIONS -> SuggestionsPanel(edit, enhanced, enabled)
    EditorTab.CROP -> CropPanel(edit, preview, enabled, edges = cropEdgeLabels(), adjustmentLabels = cropAdjustmentLabels())
    EditorTab.ADJUST -> AdjustPanel(edit, tool, onSelect = onTool, enabled = enabled)
    EditorTab.FILTERS -> FiltersPanel(edit, thumbnail, enabled)
}

@Composable
private fun EditorDialogs(discard: Boolean, smallerCopy: EditSave.NeedsSmallerCopy?, dismiss: () -> Unit, discardEdits: () -> Unit, saveSmaller: () -> Unit) {
    if (discard) AlertDialog(onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.discard_edits_title)) },
        text = { Text(stringResource(R.string.discard_edits_explanation)) },
        confirmButton = { TextButton(onClick = discardEdits) { Text(stringResource(R.string.discard_edits)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.keep_editing)) } })
    smallerCopy?.let { size -> AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.save_smaller_copy)) },
        text = { Text(stringResource(R.string.smaller_copy_explanation, size.width, size.height)) },
        confirmButton = { TextButton(onClick = saveSmaller) { Text(stringResource(R.string.save_copy)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    ) }
}

/** Hands a finished save to the screen once, whichever screen instance is showing when it ends. */
@Composable
private fun DeliverSave(save: EditSave?, saves: PhotoEditSaves, deliver: (EditSave.Saved) -> Unit) {
    LaunchedEffect(save) {
        if (save is EditSave.Saved) {
            saves.acknowledge(save.id)
            deliver(save)
        }
    }
}

@Composable
private fun EditFailedRetry(show: Boolean, enabled: Boolean, retry: () -> Unit) {
    if (show) TextButton(onClick = retry, enabled = enabled, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(stringResource(R.string.edit_failed_retry))
    }
}

/** Saving decodes first, then reports the full-resolution render row by row. */
@Composable
private fun SaveProgress(progress: Float) {
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("save-progress")
    if (progress > 0f) LinearProgressIndicator({ progress }, modifier) else LinearProgressIndicator(modifier)
}

@Composable
private fun EditorTopBar(
    edit: PhotoEditState,
    busy: Boolean,
    saving: Boolean,
    canSave: Boolean,
    canUpload: Boolean,
    requestClose: () -> Unit,
    save: () -> Unit,
    saveAndUpload: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = requestClose, enabled = !saving) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel)) }
        // Plain glyphs: the core icon set has no undo or redo arrows.
        val undo = stringResource(R.string.undo_edit)
        val redo = stringResource(R.string.redo_edit)
        IconButton(onClick = edit::undo, enabled = !busy && edit.canUndo, modifier = Modifier.testTag("undo-edit").semantics { contentDescription = undo }) {
            Text("↶", Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.titleLarge)
        }
        IconButton(onClick = edit::redo, enabled = !busy && edit.canRedo, modifier = Modifier.testTag("redo-edit").semantics { contentDescription = redo }) {
            Text("↷", Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.weight(1f))
        Button(enabled = canSave, onClick = save, modifier = Modifier.testTag("save-edited-copy")) { Text(stringResource(R.string.save_copy)) }
        if (canUpload) Box {
            IconButton(onClick = { menu = true }, enabled = canSave, modifier = Modifier.testTag("save-options")) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.save_options))
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.save_copy_and_upload)) }, onClick = { menu = false; saveAndUpload() },
                    modifier = Modifier.testTag("save-edited-copy-upload"))
            }
        }
    }
}

@Composable
private fun cropEdgeLabels(): List<String> =
    listOf(R.string.crop_left_edge, R.string.crop_top_edge, R.string.crop_right_edge, R.string.crop_bottom_edge).map { stringResource(it) }

@Composable
private fun cropAdjustmentLabels(): List<Pair<String, String>> =
    cropEdgeLabels().map { stringResource(R.string.crop_move_outward, it) to stringResource(R.string.crop_move_inward, it) }

/** Corner handles over the whole photo, with accessibility actions that nudge each edge. */
@Composable
private fun CropOverlay(edit: PhotoEditState, busy: Boolean) {
    val crop = edit.current.crop
    val (left, top, right, bottom) = crop
    val cropDescription = stringResource(R.string.crop_bounds, (left * 100).toInt(), (top * 100).toInt(), (right * 100).toInt(), (bottom * 100).toInt())
    val cropActions = cropAdjustmentLabels().flatMapIndexed { edge, (outward, inward) ->
        val direction = if (edge < 2) 1f else -1f
        listOf(CustomAccessibilityAction(outward) { edit.adjustEdge(edge, -CROP_NUDGE * direction); true },
            CustomAccessibilityAction(inward) { edit.adjustEdge(edge, CROP_NUDGE * direction); true })
    }
    val label = stringResource(R.string.crop_photo)
    val latestCrop by rememberUpdatedState(crop)
    Canvas(Modifier.fillMaxSize().testTag("photo-crop").semantics {
        contentDescription = label
        stateDescription = cropDescription
        customActions = if (busy) emptyList() else cropActions
    }.pointerInput(busy) {
        if (busy) return@pointerInput
        var corner = 0
        detectDragGestures(onDragStart = { point ->
            edit.beginDrag()
            val c = latestCrop
            val corners = listOf(Offset(c.left, c.top), Offset(c.right, c.top), Offset(c.right, c.bottom), Offset(c.left, c.bottom))
            corner = corners.indices.minBy { i -> hypot(point.x - corners[i].x * size.width, point.y - corners[i].y * size.height) }
        }, onDragEnd = edit::endDrag, onDragCancel = edit::cancelDrag) { change, _ ->
            change.consume()
            edit.dragCorner(corner, (change.position.x / size.width).coerceIn(0f, 1f), (change.position.y / size.height).coerceIn(0f, 1f))
        }
    }) {
        val l = left * size.width; val t = top * size.height; val r = right * size.width; val b = bottom * size.height
        val shade = Color.Black.copy(alpha = .58f)
        drawRect(shade, size = Size(size.width, t)); drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
        drawRect(shade, Offset(0f, t), Size(l, b - t)); drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
        drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
        listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b)).forEach { drawCircle(Color.White, 6.dp.toPx(), it) }
    }
}
