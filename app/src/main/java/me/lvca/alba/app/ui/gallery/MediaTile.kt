package me.lvca.alba.app.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaKind
import me.lvca.alba.engine.model.MediaOrigin
import androidx.compose.material.icons.outlined.Check
import androidx.compose.ui.res.painterResource
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.collections.coverReference
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.FavoriteIcon
import me.lvca.alba.app.ui.designsystem.MediaPill
import me.lvca.alba.app.ui.designsystem.MediaScrim
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.designsystem.theme.tile
import me.lvca.alba.app.ui.label

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
