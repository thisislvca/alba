package me.lvca.alba.app.sharing

import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.SharedAlbumCount

/**
 * How far a shared album has grown: how many items it holds and when its newest one was added. A later mark with more
 * items or a newer item is new activity; removals alone are not.
 */
data class SharedAlbumMark(val count: Int, val latestAddedAt: Long) {
    fun isNewerThan(other: SharedAlbumMark?): Boolean =
        other != null && (count > other.count || latestAddedAt > other.latestAddedAt)

    companion object {
        /** The marks of albums counted directly from Apple. */
        fun of(counts: List<SharedAlbumCount>): Map<String, SharedAlbumMark> = counts.associate { it.albumId to SharedAlbumMark(it.count, it.latestAddedAt) }

        /** The marks of every shared album in [collections], from the catalog's [media]. */
        fun of(collections: List<GalleryCollection>, media: List<GalleryMedia>): Map<String, SharedAlbumMark> {
            val shared = collections.filter { it.shared != null }.map { it.id }.toSet()
            val members = HashMap<String, MutableList<GalleryMedia>>()
            for (item in media) for (id in item.collectionIds) if (id in shared) members.getOrPut(id) { mutableListOf() } += item
            return shared.associateWith { id ->
                val items = members[id].orEmpty()
                SharedAlbumMark(items.size, items.maxOfOrNull { it.addedAtEpochMillis ?: it.capturedAtEpochMillis } ?: 0)
            }
        }
    }
}

/** New photos or videos in a shared album since it was last opened or announced. */
data class SharedAlbumNews(val albumId: String, val name: String, val newItems: Int)

/**
 * What the person has seen of their shared albums and invitations, and what Alba has already announced. An album Alba
 * sees for the first time becomes the baseline, so joining, creating or signing in never reads as news.
 */
data class SharedActivityLedger(
    val seen: Map<String, SharedAlbumMark> = emptyMap(),
    val announced: Map<String, SharedAlbumMark> = emptyMap(),
    val seenInvitations: Set<String> = emptySet(),
    val announcedInvitations: Set<String> = emptySet(),
    /** The pending invitations as last read, for the badge. */
    val pendingInvitations: Set<String> = emptySet(),
    val notificationsEnabled: Boolean = true,
    /** Whether Alba already asked Android for notification permission on the person's behalf. */
    val notificationsAsked: Boolean = false,
) {
    /**
     * Takes albums seen for the first time as read. An album counts once it holds photos: the catalog can list an album
     * a moment before its photos, and an empty baseline would mark every photo as new. Albums that are gone are kept,
     * since a partial catalog must not erase what was read; signing out clears everything.
     */
    fun observe(current: Map<String, SharedAlbumMark>): SharedActivityLedger =
        copy(seen = seen + current.filter { (id, mark) -> id !in seen && mark.count > 0 })

    /** Albums with photos added since the person last opened them. */
    fun unread(current: Map<String, SharedAlbumMark>): Set<String> =
        current.filter { (id, mark) -> mark.isNewerThan(seen[id]) }.keys

    fun markRead(albumId: String, mark: SharedAlbumMark): SharedActivityLedger =
        copy(seen = seen + (albumId to mark), announced = announced + (albumId to mark))

    /** Albums with activity that has not been opened or announced yet. */
    fun news(current: Map<String, SharedAlbumMark>, names: Map<String, String>): List<SharedAlbumNews> =
        current.mapNotNull { (id, mark) ->
            val opened = seen[id]
            val told = announced[id]
            if (!mark.isNewerThan(opened) || (told != null && !mark.isNewerThan(told))) null
            else SharedAlbumNews(id, names[id].orEmpty(), (mark.count - maxOf(opened!!.count, told?.count ?: 0)).coerceAtLeast(1))
        }.sortedBy { it.name }

    fun announce(news: List<SharedAlbumNews>, current: Map<String, SharedAlbumMark>): SharedActivityLedger =
        copy(announced = announced + news.mapNotNull { item -> current[item.albumId]?.let { item.albumId to it } })

    /** Records the pending invitations as they are now. */
    fun observeInvitations(pending: Set<String>): SharedActivityLedger = copy(
        pendingInvitations = pending,
        seenInvitations = seenInvitations intersect pending,
        announcedInvitations = announcedInvitations intersect pending,
    )

    /** Pending invitations the person has not looked at yet. */
    val unreadInvitations: Set<String> get() = pendingInvitations - seenInvitations

    fun markInvitationsRead(): SharedActivityLedger = copy(seenInvitations = pendingInvitations, announcedInvitations = pendingInvitations)

    /** Invitations to announce: pending, unseen and not announced before. */
    val newInvitations: Set<String> get() = unreadInvitations - announcedInvitations

    fun announceInvitations(ids: Set<String>): SharedActivityLedger = copy(announcedInvitations = announcedInvitations + ids)
}
