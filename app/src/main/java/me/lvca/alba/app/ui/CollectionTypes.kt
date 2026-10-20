package me.lvca.alba.app.ui

import me.lvca.alba.engine.model.*
import me.lvca.alba.app.R

/** A personal iCloud album or folder: not shared, not a smart collection and not a phone folder. */
internal val GalleryCollection.isPersonal: Boolean
    get() = shared == null && !id.startsWith("smart:") && !id.startsWith("device-folder:")

/** A personal album that holds photos, as opposed to a folder of albums. */
internal val GalleryCollection.isUserAlbum: Boolean get() = isPersonal && !isFolder

internal fun SmartCollection.icon(): Int = when (this) {
    SmartCollection.VIDEOS -> R.drawable.icloud_videos
    SmartCollection.LIVE_PHOTOS -> R.drawable.icloud_live_photos
    SmartCollection.SCREENSHOTS -> R.drawable.icloud_screenshots
    SmartCollection.PANORAMAS -> R.drawable.icloud_panoramas
    SmartCollection.BURSTS -> R.drawable.icloud_bursts
    SmartCollection.SLO_MO -> R.drawable.icloud_slo_mo
    SmartCollection.TIME_LAPSE -> R.drawable.icloud_time_lapse
}
