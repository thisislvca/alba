package me.lvca.alba.protocol.photos

import me.lvca.alba.engine.model.SharedDiscussion

/** The comment threads of shared photos and posts, and who may remove a photo. */
internal class SharedAlbumDiscussions(private val backend: (SharedAlbumRecord) -> SharedAlbumBackend) {
    /** The thread of [asset], or of the post it names when [post] is set. */
    suspend fun discussion(album: SharedAlbumRecord, asset: String, post: Boolean = false): SharedDiscussion {
        val info = requireNotNull(album.collection.shared)
        val comments = backend(album).comments(album, asset, post, info.canManage)
        return SharedDiscussion(comments.sortedBy { it.timestamp }, info.canComment, info.generation,
            canRemovePhoto = !post && canRemove(album, asset))
    }

    /** Managers may remove any photo; everyone else only their own contributions. */
    suspend fun canRemove(album: SharedAlbumRecord, asset: String): Boolean =
        album.collection.shared?.canManage == true || backend(album).isOwnContribution(album, asset)
}
