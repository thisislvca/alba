package me.lvca.alba.app.ui.collections

import me.lvca.alba.app.R
import me.lvca.alba.engine.model.*

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

/** How the Albums page orders albums. Folders always come first, in the same order. */
internal enum class AlbumSort(val label: Int) {
    RECENT(R.string.album_sort_recent),
    NAME(R.string.album_sort_name),
    OLDEST(R.string.album_sort_oldest);

    /** Orders [albums] by name, or by the newest photo each holds ([latest], empty albums last). */
    fun apply(albums: List<GalleryCollection>, latest: (GalleryCollection) -> Long?): List<GalleryCollection> {
        val byName = compareBy<GalleryCollection, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }
        val order = when (this) {
            NAME -> byName
            RECENT -> compareBy<GalleryCollection> { latest(it) == null }.thenByDescending { latest(it) }.then(byName)
            OLDEST -> compareBy<GalleryCollection> { latest(it) == null }.thenBy { latest(it) }.then(byName)
        }
        return albums.sortedWith(compareBy<GalleryCollection> { !it.isFolder }.then(order))
    }
}
