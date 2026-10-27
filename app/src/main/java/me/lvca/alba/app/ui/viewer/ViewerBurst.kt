package me.lvca.alba.app.ui.viewer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import me.lvca.alba.app.R
import me.lvca.alba.app.platform.HIDE_EDITS_ENABLED
import me.lvca.alba.app.ui.MediaScreenActions
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.ICloudIcon
import me.lvca.alba.engine.model.GalleryMedia

/** The open photo's burst: how many photos it stacks, and whether the user opened it. */
internal data class ViewerBurst(val size: Int = 0, val expanded: Boolean = false)

/** The open photo's [ViewerBurst], provided where the viewer is shown. */
internal val LocalViewerBurst = staticCompositionLocalOf { ViewerBurst() }

/** Moves the photo into Hidden, or back to the library when it is hidden. */
@Composable
internal fun HideAction(media: GalleryMedia, enabled: Boolean, actions: MediaScreenActions) {
    if (!HIDE_EDITS_ENABLED) return
    DetailAction(stringResource(if (media.isHidden) R.string.unhide else R.string.hide), enabled,
        { actions.setHidden(listOf(media.id), !media.isHidden) },
        { Icon(if (media.isHidden) AlbaIcons.Visibility else AlbaIcons.VisibilityOff, null) })
}

/**
 * "View N photos in burst" for a stacked burst's key photo, as in Photos: the burst's photos join the grid and the
 * viewer, and [opened] closes the details so they can be swiped through. Once open, it offers to stack them again.
 */
@Composable
internal fun BurstAction(media: GalleryMedia, actions: MediaScreenActions, opened: () -> Unit) {
    val burst = media.details.burstId ?: return
    val state = LocalViewerBurst.current
    if (state.expanded) DetailAction(stringResource(R.string.burst_stack), true,
        { actions.expandBurst(burst, false) }, { ICloudIcon(R.drawable.icloud_bursts) })
    else if (state.size > 1) DetailAction(pluralStringResource(R.plurals.burst_view_photos, state.size, state.size), true,
        { actions.expandBurst(burst, true); opened() }, { ICloudIcon(R.drawable.icloud_bursts) })
}

/** A Live Photo's motion clip on its own, as a video: shared, or saved to the phone gallery. */
@Composable
internal fun LiveVideoActions(media: GalleryMedia, enabled: Boolean, actions: MediaScreenActions) {
    DetailAction(stringResource(R.string.share_as_video), enabled, { actions.shareLiveVideo(media) }, { Icon(Icons.Outlined.Share, null) })
    DetailAction(stringResource(R.string.save_as_video), enabled, { actions.saveLiveVideo(media) }, { Icon(AlbaIcons.PhoneAndroid, null) })
}
