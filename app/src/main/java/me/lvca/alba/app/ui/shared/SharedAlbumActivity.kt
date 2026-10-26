package me.lvca.alba.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.SharedActions
import me.lvca.alba.app.ui.designsystem.AlbaBottomSheet
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.AlbaLoadState
import me.lvca.alba.app.ui.designsystem.AlbaSheetHeading
import me.lvca.alba.app.ui.designsystem.attempt
import me.lvca.alba.app.ui.designsystem.rememberLoadable
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.gallery.MediaThumbnail
import me.lvca.alba.app.ui.itemCount
import me.lvca.alba.app.ui.timestampLabel
import me.lvca.alba.engine.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedAlbumActivity(album: GalleryCollection, media: List<GalleryMedia>, actions: SharedActions,
    preview: (String) -> Unit, open: (String) -> Unit, dismiss: () -> Unit) {
    var posts by remember(album.id) { mutableStateOf<List<SharedPost>>(emptyList()) }
    var next by remember(album.id) { mutableStateOf<Int?>(0) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var previewsFailed by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable(album.id) { mutableStateOf<String?>(null) }
    var browsing by rememberSaveable(album.id) { mutableStateOf<String?>(null) }
    val previews = remember(album.id) { mutableStateMapOf<String, SharedPostPreview>() }
    val scope = rememberCoroutineScope()

    /** One bounded read per page of posts, rather than one per post. */
    suspend fun loadPreviews(page: List<SharedPost>) {
        previewsFailed = !attempt {
            page.map { it.id }.chunked(SHARED_POST_PREVIEW_BATCH).forEach { ids ->
                val loaded = actions.sharedPostPreviews(ids)
                previews.putAll(loaded)
                loaded.values.flatMap { it.photoIds }.forEach(preview)
            }
        }
    }
    suspend fun load(reset: Boolean) {
        if (busy) return
        val rank = if (reset) 0 else next ?: return
        busy = true; failed = false
        var page: List<SharedPost> = emptyList()
        try {
            failed = !attempt {
                val result = actions.sharedActivity(album.id, rank)
                if (!reset) check(result.posts.none { row -> posts.any { it.id == row.id } }) { "Activity changed. Refresh again." }
                result.nextRank?.let { check(it > rank && result.posts.isNotEmpty()) }
                if (reset) previews.clear()
                posts = if (reset) result.posts else posts + result.posts
                next = result.nextRank
                page = result.posts
            }
        } finally { busy = false }
        if (page.isNotEmpty()) loadPreviews(page)
    }
    LaunchedEffect(album.id, revision) { load(true) }
    val byId = remember(media) { media.associateBy { it.id } }
    AlbaBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(AlbaSpacing.lg)) {
            item { AlbaSheetHeading(stringResource(R.string.shared_activity), album.name, dismiss) }
            item { TextButton(onClick = { revision++ }, enabled = !busy) { Text(stringResource(R.string.shared_reload)) } }
            items(posts, key = { it.id }) { post ->
                val postPreview = previews[post.id]
                AlbaCard(contentPadding = PaddingValues(AlbaSpacing.lg), modifier = Modifier.testTag("shared-post-${post.id}")) {
                    Column(verticalArrangement = Arrangement.spacedBy(AlbaSpacing.sm)) {
                        Text(if (post.isMine) stringResource(R.string.shared_you) else post.author, style = MaterialTheme.typography.titleSmall)
                        if (post.timestamp > 0) Text(timestampLabel(post.timestamp), style = MaterialTheme.typography.labelMedium)
                        Text(post.caption.ifBlank { stringResource(R.string.shared_post_items) })
                        postPreview?.let { PostThumbnails(it, byId) { browsing = post.id } }
                        postPreview?.takeIf { it.reactions > 0 }?.let {
                            Text(pluralStringResource(R.plurals.shared_reactions, it.reactions, it.reactions), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("shared-post-reactions"))
                        }
                        Row {
                            TextButton(onClick = { selected = post.id }, enabled = postPreview != null) { Text(stringResource(R.string.shared_post_discussion)) }
                            if ((postPreview?.total ?: 0) > 0) TextButton(onClick = { browsing = post.id }) { Text(stringResource(R.string.shared_post_open)) }
                        }
                    }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (failed || previewsFailed) item { Text(stringResource(R.string.shared_action_failed), color = MaterialTheme.colorScheme.error) }
            if (!busy && posts.isEmpty() && !failed) item { Text(stringResource(R.string.shared_activity_empty)) }
            if (next != null && posts.isNotEmpty()) item {
                OutlinedButton(onClick = { scope.launch { load(false) } }, enabled = !busy) { Text(stringResource(R.string.shared_load_more)) }
            }
        }
    }
    posts.firstOrNull { it.id == selected }?.let { post ->
        val preview = previews[post.id]
        val single = preview?.photoIds?.singleOrNull()?.takeIf { preview.total == 1 && preview.totalIsExact }
        if (single != null) SharedAssetDiscussion(single, byId[single]?.fileName ?: stringResource(R.string.shared_post_items), actions) { selected = null }
        else SharedPostControls(post, actions) { selected = null }
    }
    posts.firstOrNull { it.id == browsing }?.let { post ->
        SharedPostPhotos(post, byId, actions, preview, open = { browsing = null; open(it) }) { browsing = null }
    }
}

/** Up to four thumbnails; the last says how many more the post holds. */
@Composable
private fun PostThumbnails(preview: SharedPostPreview, byId: Map<String, GalleryMedia>, open: () -> Unit) {
    val shown = preview.photoIds.take(SHARED_POST_PREVIEW_PHOTOS)
    val more = preview.total - shown.size
    Row(Modifier.clickable(role = Role.Button, onClick = open), horizontalArrangement = Arrangement.spacedBy(AlbaSpacing.xs)) {
        shown.forEachIndexed { index, id ->
            Box(Modifier.weight(1f).aspectRatio(1f).clip(MaterialTheme.shapes.extraSmall)) {
                val item = byId[id]
                if (item != null) MediaThumbnail(item.previewReference, item.accentStartArgb, item.accentEndArgb, item.fileName, Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
                if (index == shown.lastIndex && more > 0) Box(Modifier.fillMaxSize().background(AlbaTheme.colors.mediaScrim.copy(alpha = .55f)),
                    contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.shared_more_photos, more) + if (preview.totalIsExact) "" else "+",
                        color = AlbaTheme.colors.onMedia, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("shared-post-more"))
                }
            }
        }
        repeat(SHARED_POST_PREVIEW_PHOTOS - shown.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** Every photo of one post, read when the post is opened. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedPostPhotos(post: SharedPost, byId: Map<String, GalleryMedia>, actions: SharedActions, preview: (String) -> Unit,
    open: (String) -> Unit, dismiss: () -> Unit) {
    val photos = rememberLoadable(post.id, initial = emptyList<String>()) { actions.sharedPostPhotos(post.id).also { it.forEach(preview) } }
    AlbaBottomSheet(onDismissRequest = dismiss, modifier = Modifier.testTag("shared-post-photos")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = AlbaSpacing.xl)) {
            AlbaSheetHeading(post.caption.ifBlank { stringResource(R.string.shared_post_items) },
                subtitle = itemCount(photos.value.size).takeIf { !photos.loading }, dismiss = dismiss)
            AlbaLoadState(photos.loading, photos.failed, stringResource(R.string.shared_action_failed), onReload = photos::reload)
            LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(photos.value.size, key = { photos.value[it] }) { index ->
                    val id = photos.value[index]
                    val item = byId[id]
                    Box(Modifier.aspectRatio(1f).clip(MaterialTheme.shapes.extraSmall)
                        .then(if (item != null) Modifier.clickable(role = Role.Button) { open(id) } else Modifier)) {
                        if (item != null) MediaThumbnail(item.previewReference, item.accentStartArgb, item.accentEndArgb, item.fileName, Modifier.fillMaxSize())
                        else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
                    }
                }
            }
        }
    }
}
