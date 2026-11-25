package me.lvca.alba.app.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.collections.coverReference
import me.lvca.alba.app.ui.collections.icon
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.MediaPill
import me.lvca.alba.app.ui.designsystem.MediaScrim
import me.lvca.alba.app.ui.designsystem.theme.AlbaIconSize
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.label
import me.lvca.alba.app.ui.localizedTitle
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaKind
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.SmartCollection
import me.lvca.alba.engine.model.isRawFormat

@Composable
internal fun MediaTile(
    retryVersion: Int = 0,
    selected: Boolean = false,
    /** The grid is choosing photos: every tile shows a selection circle, filled when [selected]. */
    selecting: Boolean = selected,
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
    // Tiles are flat and show no ripple, like Google Photos: a tap opens the photo, and the open transition is the feedback.
    Box(
        modifier = Modifier
            .combinedClickable(interactionSource = null, indication = null, onClick = onClick, onLongClick = onLongClick)
            .aspectRatio(1f)
            .testTag("media-${media.id}")
            .semantics {
                onLongClick(label = selectLabel) { selectionAction(); true }
                this.selected = selected
                contentDescription = description
            },
    ) {
        val reducedMotion = reduceGalleryMotion()
        Box(Modifier.selectedBacking(selected, reducedMotion)) {
            Box(Modifier.selectedInset(selected, selecting, reducedMotion)) {
                val reveal = remember(media.id) { TileReveal() }
                MediaThumbnail(
                    reveal = reveal,
                    retryVersion = retryVersion,
                    sharedMediaId = media.id,
                    reference = media.coverReference(),
                    sourceRevision = media.sourceRevision,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
                // Badges come from catalog metadata, so they show before the photo arrives. On the bare placeholder they are
                // quiet ink with no scrim; the scrim and white badges fade in with the photo, on the photo's own fade.
                val ink = tileBadgeInk(reveal)
                val photoOnly = Modifier.graphicsLayer { alpha = reveal.progress }
                val status = tileStatus(media)
                if (media.kind == MediaKind.VIDEO || status != null)
                    MediaScrim(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(GalleryFeel.TILE_SCRIM_HEIGHT).then(photoOnly))
                status?.let { TileStatusBadge(it, media.id, ink, Modifier.align(Alignment.BottomStart).padding(GalleryFeel.TILE_OVERLAY_INSET)) }
                if (media.kind == MediaKind.VIDEO) {
                    val duration = mediaDuration(media.durationMillis)
                    if (duration != null) BasicText(duration, style = MaterialTheme.typography.labelMedium, color = ink,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(GalleryFeel.TILE_OVERLAY_INSET).testTag("tile-duration-${media.id}"))
                    else TileGlyph(AlbaIcons.PlayArrow, stringResource(R.string.videos), ink,
                        Modifier.align(Alignment.BottomEnd).padding(GalleryFeel.TILE_OVERLAY_INSET).size(GalleryFeel.TILE_TYPE_ICON))
                }
                // The selection circle takes the top-start corner while choosing, as in Photos.
                if (!selecting) TileKindBadge(media, Modifier.align(Alignment.TopStart).padding(GalleryFeel.TILE_OVERLAY_INSET))
                if (media.isFavorite && !selected) TileGlyph(AlbaIcons.Favorite, stringResource(R.string.favorite), ink,
                    Modifier.align(Alignment.TopEnd).padding(GalleryFeel.TILE_OVERLAY_INSET).size(16.dp))
                // Backup state, phone items only; a separate slot so other badges can change independently.
                if (media.origin == MediaOrigin.DEVICE) BackupBadgeOverlay(media.id)
            }
            AnimatedVisibility(selecting, Modifier.align(Alignment.TopStart).padding(GalleryFeel.SELECTION_BADGE_INSET),
                enter = if (reducedMotion) EnterTransition.None
                else fadeIn(tween(GalleryFeel.CHECK_FADE_IN_MILLIS, delayMillis = GalleryFeel.CHECK_FADE_IN_DELAY_MILLIS)),
                exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(GalleryFeel.CHECK_FADE_OUT_MILLIS))) {
                SelectionCircle(selected)
            }
        }
    }
}

/**
 * A selected photo shrinks and rounds its corners, leaving the page showing around it as in Google Photos, so the
 * selection reads at a glance even without its circle. Toggling one photo is quick; entering or leaving selection mode is slower. It scales rather than
 * pads, so selecting never re-lays out the tile.
 */
@Composable
private fun Modifier.selectedInset(selected: Boolean, selecting: Boolean, reducedMotion: Boolean): Modifier {
    var wasSelecting by remember { mutableStateOf(selecting) }
    val modeChange = wasSelecting != selecting
    SideEffect { wasSelecting = selecting }
    val insetSpec: AnimationSpec<Dp> = when {
        reducedMotion -> snap()
        modeChange -> tween(GalleryFeel.SELECTION_MODE_MILLIS, easing = FastOutSlowInEasing)
        else -> tween(GalleryFeel.SELECT_TOGGLE_MILLIS, easing = LinearOutSlowInEasing)
    }
    val inset = animateDpAsState(if (selected) GalleryFeel.SELECTED_INSET else 0.dp, insetSpec, label = "selected-inset")
    val corner = animateDpAsState(if (selected) GalleryFeel.SELECTED_CORNER else TileCorner,
        if (reducedMotion) snap() else tween(GalleryFeel.SELECT_TOGGLE_MILLIS, easing = LinearOutSlowInEasing), label = "selected-corner")
    return graphicsLayer {
        val scale = if (size.width > 0f) (1f - 2f * inset.value.toPx() / size.width).coerceIn(MIN_SCALE, 1f) else 1f
        scaleX = scale; scaleY = scale
        shape = RoundedCornerShape(corner.value / scale)
        clip = true
    }
}

private const val MIN_SCALE = .01f

/** Photos backs a checked photo with a quiet grey square, so the shrunken photo reads as picked, not missing. */
@Composable
private fun Modifier.selectedBacking(selected: Boolean, reducedMotion: Boolean): Modifier {
    val backing = MaterialTheme.colorScheme.surfaceVariant
    val alpha by animateFloatAsState(if (selected) 1f else 0f,
        if (reducedMotion) snap() else tween(GalleryFeel.SELECT_TOGGLE_MILLIS, easing = LinearOutSlowInEasing), label = "selected-backing")
    return drawBehind { if (alpha > 0f) drawRect(backing.copy(alpha = alpha)) }
}

/** What a tile says in its bottom-start corner: time left in a trash, not backed up, or kept offline. */
private sealed interface TileStatus {
    data class DaysLeft(val label: String) : TileStatus
    data object NotBackedUp : TileStatus
    data object Offline : TileStatus
}

/**
 * [media]'s corner status. In a trash the time left takes the offline badge's corner, as in Google Photos' trash, and a
 * phone item not in iCloud yet is marked as Google Photos marks items that aren't backed up.
 */
@Composable
private fun tileStatus(media: GalleryMedia): TileStatus? {
    val daysLeft = trashTileDaysLabel(media)
    val phoneOnly = media.origin == MediaOrigin.DEVICE && notBackedUp(media.id)
    return when {
        daysLeft != null -> TileStatus.DaysLeft(daysLeft)
        phoneOnly -> TileStatus.NotBackedUp
        media.availability == MediaAvailability.ORIGINAL_CACHED -> TileStatus.Offline
        else -> null
    }
}

@Composable
private fun TileStatusBadge(status: TileStatus, mediaId: String, ink: ColorProducer, modifier: Modifier) = when (status) {
    is TileStatus.DaysLeft -> BasicText(status.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = ink,
        modifier = modifier.testTag("deleted-days-left-$mediaId"))
    TileStatus.NotBackedUp -> TileGlyph(AlbaIcons.CloudOff, stringResource(R.string.badge_not_backed_up), ink,
        modifier.size(GalleryFeel.TILE_TYPE_ICON).testTag("not-backed-up-$mediaId"))
    TileStatus.Offline -> TileGlyph(AlbaIcons.DownloadForOffline, stringResource(R.string.available_offline), ink,
        modifier.size(GalleryFeel.TILE_TYPE_ICON))
}

/** The one photo type a tile names in its corner, as Google Photos marks panoramas, slow motion and bursts. */
internal enum class TileBadge { BURST, PANORAMA, SLO_MO, TIME_LAPSE, RAW }

/** The type badge for [media], or null for an ordinary photo or video. Live Photos have their own Live pill. */
internal fun tileBadge(media: GalleryMedia): TileBadge? = when {
    media.burstCount > 1 -> TileBadge.BURST
    SmartCollection.PANORAMAS.id in media.collectionIds -> TileBadge.PANORAMA
    SmartCollection.SLO_MO.id in media.collectionIds -> TileBadge.SLO_MO
    SmartCollection.TIME_LAPSE.id in media.collectionIds -> TileBadge.TIME_LAPSE
    isRawFormat(media.mimeType) -> TileBadge.RAW
    else -> null
}

/** A Live Photo's Live pill, or the photo type's badge. */
@Composable
private fun TileKindBadge(media: GalleryMedia, modifier: Modifier) {
    if (media.kind == MediaKind.LIVE_PHOTO) MediaPill(modifier) {
        Icon(painterResource(R.drawable.icloud_live_photos), null, Modifier.size(AlbaIconSize.badge))
        Text(stringResource(R.string.live_badge), style = MaterialTheme.typography.labelSmall)
    } else tileBadge(media)?.let { badge -> TypeBadge(badge, modifier) }
}

@Composable
private fun TypeBadge(badge: TileBadge, modifier: Modifier) {
    val smart = when (badge) {
        TileBadge.BURST -> SmartCollection.BURSTS
        TileBadge.PANORAMA -> SmartCollection.PANORAMAS
        TileBadge.SLO_MO -> SmartCollection.SLO_MO
        TileBadge.TIME_LAPSE -> SmartCollection.TIME_LAPSE
        TileBadge.RAW -> null
    }
    if (smart == null) MediaPill(modifier.testTag("tile-badge-raw")) {
        Text(stringResource(R.string.raw_badge), style = MaterialTheme.typography.labelSmall)
    } else MediaPill(modifier.testTag("tile-badge-${badge.name.lowercase()}"), contentPadding = PaddingValues(3.dp)) {
        Icon(painterResource(smart.icon()), smart.localizedTitle(), Modifier.size(GalleryFeel.TILE_TYPE_ICON))
    }
}

/**
 * The colour of a tile's badges: quiet ink on the bare placeholder, white over the photo, blended by how far the photo
 * has faded in. Read while drawing, so the fade never recomposes the tile.
 */
@Composable
private fun tileBadgeInk(reveal: TileReveal): ColorProducer {
    val waiting = MaterialTheme.colorScheme.onSurfaceVariant
    val onPhoto = AlbaTheme.colors.onMedia
    return remember(reveal, waiting, onPhoto) { ColorProducer { lerp(waiting, onPhoto, reveal.progress) } }
}

/** A badge icon tinted by [ink] at draw time, announced as [description]. */
@Composable
private fun TileGlyph(icon: ImageVector, description: String, ink: ColorProducer, modifier: Modifier) {
    val painter = rememberVectorPainter(icon)
    Box(modifier
        .semantics { contentDescription = description; role = Role.Image }
        .drawBehind { with(painter) { draw(size, colorFilter = ColorFilter.tint(ink())) } })
}

/** An unselected tile's corner radius, matching the tile shape. */
private val TileCorner = 2.dp

/**
 * A check badge filled in the primary colour when selected, an empty ring otherwise. Photos and date headers share it,
 * as in Google Photos; the ring is light over a photo ([onPhoto]) and quiet ink on the page. Neither shows until
 * selection starts.
 */
@Composable
internal fun SelectionCircle(selected: Boolean, modifier: Modifier = Modifier, onPhoto: Boolean = true) {
    val colors = AlbaTheme.colors
    if (selected) Icon(AlbaIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(4.dp)
            .then(if (onPhoto) Modifier.testTag("tile-selected") else Modifier))
    else if (onPhoto) Box(modifier.size(24.dp).alpha(GalleryFeel.UNCHECKED_RING_ALPHA)
        .background(colors.mediaScrim.copy(alpha = .2f), CircleShape).border(2.dp, colors.onMedia, CircleShape)
        .testTag("tile-selection-ring"))
    else Box(modifier.size(24.dp).border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
}
