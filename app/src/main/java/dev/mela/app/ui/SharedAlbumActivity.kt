package dev.mela.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mela.app.R
import dev.mela.engine.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedAlbumActivity(album: GalleryCollection, media: List<GalleryMedia>, actions: LibraryActions,
    preview: (String) -> Unit, open: (String) -> Unit, dismiss: () -> Unit) {
    var posts by remember(album.id) { mutableStateOf<List<SharedPost>>(emptyList()) }
    var next by remember(album.id) { mutableStateOf<Int?>(0) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable(album.id) { mutableStateOf<String?>(null) }
    val previews = remember(album.id) { mutableStateMapOf<String, List<String>>() }
    val scope = rememberCoroutineScope()
    suspend fun load(reset: Boolean) {
        if (busy) return
        busy = true; failed = false
        try {
            val rank = if (reset) 0 else next ?: return
            val page = actions.sharedActivity(album.id, rank)
            if (!reset) check(page.posts.none { row -> posts.any { it.id == row.id } }) { "Activity changed. Refresh again." }
            page.nextRank?.let { check(it > rank && page.posts.isNotEmpty()) }
            posts = if (reset) page.posts else posts + page.posts
            next = page.nextRank
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        finally { busy = false }
    }
    LaunchedEffect(album.id, revision) { load(true) }
    val byId = remember(media) { media.associateBy { it.id } }
    MelaBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { MelaSheetHeading(stringResource(R.string.shared_activity), album.name, dismiss) }
            item { TextButton(onClick = { revision++ }, enabled = !busy) { Text(stringResource(R.string.shared_reload)) } }
            items(posts, key = { it.id }) { post ->
                var photos by remember(post.id) { mutableStateOf<List<String>>(emptyList()) }
                var previewFailed by remember(post.id) { mutableStateOf(false) }
                LaunchedEffect(post.id, revision) {
                    previewFailed = false
                    try { photos = actions.sharedPostPhotos(post.id); previews[post.id] = photos; photos.forEach(preview) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { previewFailed = true }
                }
                Surface(shape = MaterialTheme.shapes.large, color = melaGroupColor()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (post.isMine) stringResource(R.string.shared_you) else post.author, style = MaterialTheme.typography.titleSmall)
                        if (post.timestamp > 0) Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(post.timestamp)), style = MaterialTheme.typography.labelMedium)
                        Text(post.caption.ifBlank { stringResource(R.string.shared_post_items) })
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            photos.mapNotNull(byId::get).take(4).forEach { item ->
                                MediaThumbnail(item.previewReference, item.accentStartArgb, item.accentEndArgb, item.fileName,
                                    Modifier.weight(1f).aspectRatio(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).clickable { open(item.id) })
                            }
                        }
                        if (previewFailed) Text(stringResource(R.string.shared_action_failed), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { selected = post.id }, enabled = previews.containsKey(post.id)) { Text(stringResource(R.string.shared_post_discussion)) }
                    }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (failed) item { Text(stringResource(R.string.shared_action_failed), color = MaterialTheme.colorScheme.error) }
            if (!busy && posts.isEmpty() && !failed) item { Text(stringResource(R.string.shared_activity_empty)) }
            if (next != null && posts.isNotEmpty()) item {
                OutlinedButton(onClick = { scope.launch { load(false) } }, enabled = !busy) { Text(stringResource(R.string.shared_load_more)) }
            }
        }
    }
    posts.firstOrNull { it.id == selected }?.let { post ->
        val single = previews[post.id]?.singleOrNull()
        if (single != null) SharedAssetDiscussion(single, byId[single]?.fileName ?: stringResource(R.string.shared_post_items), actions) { selected = null }
        else SharedPostControls(post, actions) { selected = null }
    }
}
