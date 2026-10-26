package me.lvca.alba.app.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.MediaScrim
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState

/** A phone item's backup state on its grid tile. Google Photos' rule: a backed-up item has no badge. */
enum class BackupBadge { QUEUED, FAILED, DONE }

/**
 * Each tile's backup badge, read through a [State] so a tile recomposes only when its own badge changes, never the
 * whole grid on each progress tick. Empty when backup is off.
 */
internal val LocalBackupBadges = staticCompositionLocalOf<State<Map<String, BackupBadge>>> { mutableStateOf(emptyMap()) }

/** Badges for phone items while backup is on: waiting or uploading, failed, and verified (shown only briefly). */
internal fun backupBadges(backupEnabled: Boolean, uploadsByMediaId: Map<String, TransferView>): Map<String, BackupBadge> {
    if (!backupEnabled) return emptyMap()
    return uploadsByMediaId.mapNotNull { (mediaId, transfer) ->
        when (transfer.state) {
            TransferViewState.WAITING, TransferViewState.UPLOADING, TransferViewState.CHECKING_ICLOUD -> mediaId to BackupBadge.QUEUED
            TransferViewState.NEEDS_ATTENTION, TransferViewState.UNSUPPORTED, TransferViewState.UNRESOLVED -> mediaId to BackupBadge.FAILED
            TransferViewState.VERIFIED -> mediaId to BackupBadge.DONE
            else -> null
        }
    }.toMap()
}

/** How long a just-finished upload shows its cloud-done glyph before the tile goes back to no badge. */
private const val DONE_BADGE_MILLIS = 3_000L

/**
 * The tile's backup badge slot, bottom start like the other corner overlays: a white cloud-up while waiting or
 * uploading, a red cloud-off on white when the item can't be backed up, and a brief cloud-done when an upload finishes
 * while the tile is on screen.
 */
@Composable
internal fun BoxScope.BackupBadgeOverlay(mediaId: String) {
    val badges = LocalBackupBadges.current
    val badge by remember(mediaId, badges) { derivedStateOf { badges.value[mediaId] } }
    var previous by remember(mediaId) { mutableStateOf(badge) }
    var showDone by remember(mediaId) { mutableStateOf(false) }
    LaunchedEffect(badge) {
        if (badge == BackupBadge.DONE && previous == BackupBadge.QUEUED) {
            showDone = true
            previous = badge
            delay(DONE_BADGE_MILLIS)
            showDone = false
        } else {
            previous = badge
            showDone = false
        }
    }
    val onMedia = AlbaTheme.colors.onMedia
    when (badge) {
        BackupBadge.QUEUED -> {
            MediaScrim(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(GalleryFeel.TILE_SCRIM_HEIGHT))
            Icon(AlbaIcons.CloudUpload, stringResource(R.string.badge_backup_waiting), tint = onMedia,
                modifier = Modifier.align(Alignment.BottomStart).padding(GalleryFeel.TILE_OVERLAY_INSET).size(18.dp)
                    .testTag("backup-badge-queued"))
        }
        BackupBadge.FAILED -> Box(
            Modifier.align(Alignment.BottomStart).padding(GalleryFeel.TILE_OVERLAY_INSET).size(20.dp)
                .background(Color.White, CircleShape).testTag("backup-badge-failed"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AlbaIcons.CloudOff, stringResource(R.string.badge_backup_failed), tint = FailedRed, modifier = Modifier.size(14.dp))
        }
        else -> Unit
    }
    // Reduced motion removes the glyph at once rather than fading it.
    val reducedMotion = reduceGalleryMotion()
    AnimatedVisibility(showDone && badge == BackupBadge.DONE, Modifier.align(Alignment.BottomStart).padding(GalleryFeel.TILE_OVERLAY_INSET),
        enter = EnterTransition.None, exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(DONE_FADE_MILLIS))) {
        Icon(AlbaIcons.CloudDone, stringResource(R.string.badge_backup_done), tint = onMedia,
            modifier = Modifier.size(18.dp).testTag("backup-badge-done"))
    }
}

private const val DONE_FADE_MILLIS = 300

/** Google Photos' cloud-off red, legible on the white disc in light and dark themes alike. */
private val FailedRed = Color(0xFFD93025)
