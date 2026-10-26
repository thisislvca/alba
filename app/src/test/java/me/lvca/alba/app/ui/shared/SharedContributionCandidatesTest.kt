package me.lvca.alba.app.ui.shared

import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.SharedAlbumGeneration
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedContributionCandidatesTest {
    private fun photo(id: String, hidden: Boolean = false) =
        GalleryMedia("icloud:$id", "$id.jpg", 0, 1, 1, MediaOrigin.ICLOUD, MediaAvailability.CLOUD_ONLY, null, null, 0, 0, isHidden = hidden)

    @Test
    fun hiddenPhotosAreNeverOfferedForPosting() {
        val (candidates, _) = sharedContributionCandidates(listOf(photo("a"), photo("b", hidden = true)), SharedAlbumGeneration.MODERN)
        assertEquals(listOf("icloud:a"), candidates.map { it.id })
    }
}
