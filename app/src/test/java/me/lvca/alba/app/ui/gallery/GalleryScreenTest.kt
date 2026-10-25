package me.lvca.alba.app.ui.gallery

import me.lvca.alba.app.ui.collections.CollectionsPage
import me.lvca.alba.app.ui.navigation.NavigationDirection
import me.lvca.alba.engine.model.GalleryQuery
import me.lvca.alba.engine.model.MediaOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryScreenTest {
    private val parents = mapOf("folder:inner" to "folder:outer")
    private val parentOf: (String) -> String? = parents::get
    private val depthOf: (String) -> Int = { folderDepth(it, parentOf) }

    private val library = GalleryScreen(GalleryTab.LIBRARY)
    private val hub = GalleryScreen(GalleryTab.COLLECTIONS)
    private val albums = hub.copy(page = CollectionsPage.ALBUMS)
    private val outer = albums.copy(folder = "folder:outer")
    private val inner = albums.copy(folder = "folder:inner")
    private val album = inner.copy(album = "album:1")

    @Test fun backWalksOutOfTheAlbumThenFoldersThenThePageThenToPhotos() {
        assertEquals(inner, album.parent(parentOf))
        assertEquals(outer, inner.parent(parentOf))
        assertEquals(albums, outer.parent(parentOf))
        assertEquals(hub, albums.parent(parentOf))
        assertEquals(library, hub.parent(parentOf))
        assertEquals(library, GalleryScreen(GalleryTab.SEARCH).parent(parentOf))
        assertNull(library.parent(parentOf))
    }

    @Test fun deeperScreensSlideForwardAndShallowerOnesBack() {
        assertEquals(4, album.depth(depthOf))
        assertEquals(NavigationDirection.FORWARD, galleryDirection(albums, inner, depthOf))
        assertEquals(NavigationDirection.BACK, galleryDirection(album, inner, depthOf))
        assertEquals(NavigationDirection.FORWARD, galleryDirection(hub, hub.copy(album = GalleryQuery.FAVORITES), depthOf))
        assertEquals(NavigationDirection.LATERAL, galleryDirection(album, library, depthOf))
        assertEquals(NavigationDirection.LATERAL, galleryDirection(library, GalleryScreen(GalleryTab.SEARCH), depthOf))
    }

    @Test fun onlyTheCurrentPathCountsAsWithin() {
        assertTrue(album.isWithin(albums, parentOf))
        assertTrue(album.isWithin(album, parentOf))
        assertFalse(albums.isWithin(album, parentOf))
        assertFalse(album.isWithin(albums.copy(folder = "folder:other"), parentOf))
    }

    @Test fun placesAreAlbumsAndFiltersAreNot() {
        assertEquals("a", GalleryScreen.albumOf(GalleryQuery(collectionId = "a")))
        assertEquals("offline", GalleryScreen.albumOf(GalleryQuery(offlineOnly = true)))
        assertEquals("trash:ICLOUD", GalleryScreen.albumOf(GalleryQuery(origin = MediaOrigin.ICLOUD, trashOnly = true)))
        assertNull(GalleryScreen.albumOf(GalleryQuery(origin = MediaOrigin.DEVICE, filename = "sea")))
    }
}
