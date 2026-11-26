package me.lvca.alba.app.ui.gallery

import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.gallery.GalleryState

/** What the library is loading, for the header. */
internal enum class LibraryLoad {
    IDLE,

    /** The first load of a library Alba stores nothing of yet: the header says how far it is. */
    INITIAL_LOAD,

    /** A refresh or rescan of a library Alba already shows: it runs quietly, as iCloud.com's does. */
    REFRESHING,
}

/**
 * What a running refresh is for the library [gallery] shows. Until the stored library is read its size is unknown, and
 * a refresh then is not taken for a first load: that showed "Updating library…" over a stored library at every start.
 */
internal fun SessionState.libraryLoad(gallery: GalleryState): LibraryLoad =
    libraryLoad(storedLibrary = gallery.cloudCount > 0 || !gallery.libraryLoaded)

/** What a running refresh is, given whether Alba already shows a [storedLibrary] of iCloud photos. */
internal fun SessionState.libraryLoad(storedLibrary: Boolean): LibraryLoad = when {
    !isRefreshing -> LibraryLoad.IDLE
    // A first load's progress is shown from its first page on, although its pages are stored by then.
    catalogScan?.initialLoad != true && storedLibrary -> LibraryLoad.REFRESHING
    else -> LibraryLoad.INITIAL_LOAD
}
