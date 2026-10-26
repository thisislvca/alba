package me.lvca.alba.app.albums

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.app.intake.IncomingItem
import me.lvca.alba.app.intake.incomingForSharedAlbum
import me.lvca.alba.app.ui.collections.AlbumSort
import me.lvca.alba.app.ui.collections.albumChooserEntries
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedAlbumInfo
import me.lvca.alba.engine.model.SharedAlbumRole
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlbumOrderingTest {
    private val folder = GalleryCollection("folder", "Trips", isFolder = true)
    private val beach = GalleryCollection("beach", "beach", parentId = "folder")
    private val alps = GalleryCollection("alps", "Alps")
    private val zoo = GalleryCollection("zoo", "Zoo")
    private val empty = GalleryCollection("empty", "Empty")
    private val latest = mapOf("folder" to 50L, "alps" to 10L, "zoo" to 30L)

    @Test fun sortsByNewestPhotoNameOrOldestWithFoldersFirstAndEmptyAlbumsLast() {
        val albums = listOf(alps, empty, zoo, folder)
        assertEquals(listOf("folder", "zoo", "alps", "empty"), AlbumSort.RECENT.apply(albums) { latest[it.id] }.map { it.id })
        assertEquals(listOf("folder", "alps", "zoo", "empty"), AlbumSort.OLDEST.apply(albums) { latest[it.id] }.map { it.id })
        assertEquals(listOf("folder", "alps", "empty", "zoo"), AlbumSort.NAME.apply(albums) { latest[it.id] }.map { it.id })
    }

    @Test fun chooserShowsFoldersThatHoldAlbumsAndTheAlbumsInside() {
        val shared = GalleryCollection("shared", "Shared", shared = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.OWNER))
        val emptyFolder = GalleryCollection("nothing", "Nothing", isFolder = true)
        val nested = GalleryCollection("nested", "Nested", isFolder = true, parentId = "nothing")
        val all = listOf(folder, beach, alps, shared, emptyFolder, nested, GalleryCollection("smart:videos", "Videos"))
        assertEquals(listOf("folder", "alps"), albumChooserEntries(all, null).map { it.id })
        assertEquals(listOf("beach"), albumChooserEntries(all, "folder").map { it.id })
    }

    @Test fun legacySharedAlbumsTakeOnlyTheirFormatsFromOtherApps() {
        fun item(name: String, mime: String) = IncomingItem(android.net.Uri.parse("content://test/$name"), name, mime)
        val items = listOf(item("a.jpg", "image/jpeg"), item("b.webp", "image/webp"), item("c.mp4", "video/mp4"))
        val legacy = GalleryCollection("l", "L", shared = SharedAlbumInfo(SharedAlbumGeneration.LEGACY, SharedAlbumRole.CONTRIBUTOR))
        val modern = GalleryCollection("m", "M", shared = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.CONTRIBUTOR))
        assertEquals(listOf("a.jpg", "c.mp4"), incomingForSharedAlbum(items, legacy).map { it.name })
        assertEquals(3, incomingForSharedAlbum(items, modern).size)
    }
}
