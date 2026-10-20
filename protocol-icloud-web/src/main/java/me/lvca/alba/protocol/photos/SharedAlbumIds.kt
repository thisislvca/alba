package me.lvca.alba.protocol.photos

import me.lvca.alba.protocol.account.AppleSessionSnapshot
import java.util.Base64

/**
 * The account-scoped IDs the engine stores for shared albums, photos, posts and invitations. They are
 * persisted, so their format must not change. The personal library, legacy streams and each CloudKit
 * owner's zones are separate namespaces.
 */
internal class SharedAlbumIds(private val account: () -> String) {
    fun encoded(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decoded(value: String) = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    fun album(scope: String, owner: String, zone: String) =
        "shared:${account()}:${encoded(scope)}:${encoded(owner)}:${encoded(zone)}"

    /** Whether [albumId] names a legacy shared-streams album, read from the ID alone. */
    fun isLegacyAlbum(albumId: String) = albumId.split(':').getOrNull(2) == encoded(SharedAlbumRecord.LEGACY_SCOPE)

    fun requireOwnAlbum(albumId: String) {
        require(albumId.startsWith("shared:${account()}:")) { "Shared album belongs to another account." }
    }

    fun photo(album: SharedAlbumRecord, recordName: String) = "${album.collection.id}:${encoded(recordName)}"

    /** The album ID of the shared photo [mediaId]. */
    fun albumOfPhoto(mediaId: String): String {
        require(mediaId.startsWith("shared:${account()}:")) { "Shared photo belongs to another account." }
        require(mediaId.split(':').size == 6) { "Invalid shared photo identity." }
        return mediaId.substringBeforeLast(':')
    }

    /** The record name of the shared photo [mediaId], which must belong to [album]. */
    fun recordName(album: SharedAlbumRecord, mediaId: String): String {
        require(albumOfPhoto(mediaId) == album.collection.id) { "Photo belongs to a different shared album." }
        return decoded(mediaId.substringAfterLast(':'))
    }

    fun post(album: SharedAlbumRecord, recordName: String) = "post:" + photo(album, recordName)

    fun albumOfPost(postId: String): String {
        require(postId.startsWith("post:")) { "Invalid post identity" }
        return albumOfPhoto(postId.removePrefix("post:"))
    }

    fun postRecordName(album: SharedAlbumRecord, postId: String): String {
        require(albumOfPost(postId) == album.collection.id)
        return recordName(album, postId.removePrefix("post:"))
    }

    fun invitation(kind: String, value: String) = "invitation:${account()}:$kind:${encoded(value)}"

    /** The kind, "legacy" or "modern", and the album GUID or share token of [invitationId]. */
    fun invitationValue(invitationId: String): Pair<String, String> {
        require(invitationId.startsWith("invitation:${account()}:")) { "Invitation belongs to another account." }
        val parts = invitationId.split(':')
        require(parts.size == 4 && parts[2] in setOf("legacy", "modern"))
        return parts[2] to decoded(parts[3])
    }
}

/** iCloud.com's page for a shared album. */
internal fun sharedAlbumWebUrl(session: AppleSessionSnapshot, route: String, identifier: String): String {
    require(identifier.matches(Regex("[A-Za-z0-9_-]+")))
    val domain = if (session.usesChinaMainlandDomain) "www.icloud.com.cn" else "www.icloud.com"
    return "https://$domain/photos/#/$route$identifier/"
}

/** The public link that opens a modern shared album or invitation. */
internal fun sharedAlbumLink(session: AppleSessionSnapshot, token: String): String {
    require(token.matches(Regex("[A-Za-z0-9_-]{1,4096}")))
    val domain = if (session.usesChinaMainlandDomain) "photos.icloud.com.cn" else "photos.icloud.com"
    return "https://$domain/shared/album/$token"
}

private val AppleSessionSnapshot.usesChinaMainlandDomain: Boolean
    get() = webservices["ckdatabasews"].orEmpty().contains(".icloud.com.cn")
