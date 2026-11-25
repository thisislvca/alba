package me.lvca.alba.app.ui.gallery

import me.lvca.alba.app.account.SessionState

/** What the library is loading, for the header. */
internal enum class LibraryLoad {
    IDLE,

    /** The first load of a library Alba stores nothing of yet: the header says how far it is. */
    INITIAL_LOAD,

    /** A refresh or rescan of a library Alba already shows: it runs quietly, as iCloud.com's does. */
    REFRESHING,
}

/** What a running refresh is, given whether Alba already shows a [storedLibrary] of iCloud photos. */
internal fun SessionState.libraryLoad(storedLibrary: Boolean): LibraryLoad = when {
    !isRefreshing -> LibraryLoad.IDLE
    // A first load's progress is shown from its first page on, although its pages are stored by then.
    catalogScan?.initialLoad != true && storedLibrary -> LibraryLoad.REFRESHING
    else -> LibraryLoad.INITIAL_LOAD
}
