package me.lvca.alba.engine.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class GallerySearchTest {
    @Test fun monthsAndYearsAreRecognisedInEnglishAndItalian() {
        GallerySearch.parse("June 2023").let { assertEquals(setOf(6), it.months); assertEquals(setOf(2023), it.years); assertTrue(it.words.isEmpty()) }
        assertEquals(setOf(6), GallerySearch.parse("giugno").months)
        assertEquals(setOf(6, 7), GallerySearch.parse("jun, luglio").months)
        assertEquals(setOf(2023), GallerySearch.parse("2023").years)
        assertEquals(setOf(12), GallerySearch.parse("Dicembre").months)
        // Numbers that are not plausible years stay text, so file numbers still match.
        GallerySearch.parse("8421").let { assertTrue(it.years.isEmpty()); assertEquals(listOf("8421"), it.words) }
        // Italian abbreviations that are English words stay text.
        GallerySearch.parse("ago").let { assertTrue(it.months.isEmpty()); assertEquals(listOf("ago"), it.words) }
    }

    @Test fun mediaTypeWordsMapToSmartCollectionsInBothLanguages() {
        val cases = mapOf(
            "videos" to SmartCollection.VIDEOS.id, "video" to SmartCollection.VIDEOS.id, "filmati" to SmartCollection.VIDEOS.id,
            "screenshots" to SmartCollection.SCREENSHOTS.id, "schermate" to SmartCollection.SCREENSHOTS.id,
            "live" to SmartCollection.LIVE_PHOTOS.id, "Live Photos" to SmartCollection.LIVE_PHOTOS.id, "foto live" to SmartCollection.LIVE_PHOTOS.id,
            "panoramiche" to SmartCollection.PANORAMAS.id, "bursts" to SmartCollection.BURSTS.id, "raffiche" to SmartCollection.BURSTS.id,
            "slo-mo" to SmartCollection.SLO_MO.id, "slow motion" to SmartCollection.SLO_MO.id, "rallentatore" to SmartCollection.SLO_MO.id,
            "time lapse" to SmartCollection.TIME_LAPSE.id, "timelapse" to SmartCollection.TIME_LAPSE.id,
            "preferiti" to GalleryQuery.FAVORITES, "favourites" to GalleryQuery.FAVORITES,
        )
        for ((text, collection) in cases) {
            val search = GallerySearch.parse(text)
            assertEquals(text, setOf(collection), search.collections)
            assertTrue(text, search.words.isEmpty())
        }
        GallerySearch.parse("video mare 2024").let {
            assertEquals(setOf(SmartCollection.VIDEOS.id), it.collections); assertEquals(setOf(2024), it.years); assertEquals(listOf("mare"), it.words)
        }
        assertTrue(GallerySearch.parse("RAW").raw)
        assertTrue(GallerySearch.parse("  ").isEmpty)
    }

    @Test fun fillerWordsAreIgnored() {
        assertEquals(listOf("mare"), GallerySearch.parse("foto del mare").words)
        assertEquals(listOf("beach"), GallerySearch.parse("photos of the beach").words)
        assertTrue(GallerySearch.parse("pictures").isEmpty)
    }

    @Test fun wordsMatchFileNamesTitlesAndCaptionsIgnoringCaseAndAccents() {
        val photo = media("IMG_8421.HEIC", "2023-06-14T10:00:00Z").copy(details = MediaDetails(caption = "Gita in città",
            description = "Sunset at the harbour"))
        fun matches(text: String) = GallerySearch.parse(text).matches(photo, photo.capturedAtEpochMillis, ZoneOffset.UTC)
        assertTrue(matches("img_8421"))
        assertTrue(matches("citta"))
        assertTrue(matches("CITTÀ"))
        assertTrue(matches("harbour sunset"))
        assertTrue(matches("giugno 2023 harbour"))
        assertFalse(matches("harbour 2022"))
        assertFalse(matches("luglio"))
        assertFalse(matches("beach"))
        assertFalse(matches("videos"))
    }

    @Test fun typeAndMonthWordsAlsoMatchAsText() {
        val concert = media("IMG_1.JPG", "2024-01-01T00:00:00Z").copy(details = MediaDetails(caption = "Live music in March"))
        fun matches(text: String) = GallerySearch.parse(text).matches(concert, concert.capturedAtEpochMillis, ZoneOffset.UTC)
        assertTrue(matches("live music"))
        assertTrue(matches("march"))
        assertFalse(matches("may"))
        assertFalse(matches("raw"))
    }

    @Test fun typeWordsMatchByIcloudMembershipOrKind() {
        val video = media("clip.mov", "2024-01-01T00:00:00Z").copy(kind = MediaKind.VIDEO)
        val screenshot = media("shot.png", "2024-01-01T00:00:00Z").copy(collectionIds = setOf(SmartCollection.SCREENSHOTS.id))
        val raw = media("DSC_1.DNG", "2024-01-01T00:00:00Z").copy(mimeType = "image/x-adobe-dng")
        val items = listOf(video, screenshot, raw)
        fun search(text: String) = GalleryQuery(filename = text).apply(items, ZoneOffset.UTC).map { it.fileName }
        assertEquals(listOf("clip.mov"), search("videos"))
        assertEquals(listOf("shot.png"), search("schermate"))
        assertEquals(listOf("DSC_1.DNG"), search("raw"))
        assertEquals(3, search("2024").size)
        assertTrue(search("2025").isEmpty())
    }

    @Test fun albumsMatchOnTheirNameAlone() {
        assertTrue(GallerySearch.parse("vacanze").matchesAlbum("Vacanze al mare"))
        assertTrue(GallerySearch.parse("Città").matchesAlbum("citta vecchia"))
        assertTrue(GallerySearch.parse("2023").matchesAlbum("Summer 2023"))
        assertFalse(GallerySearch.parse("beach").matchesAlbum("Mountains"))
        assertFalse(GallerySearch.parse("").matchesAlbum("Mountains"))
    }

    @Test fun hiddenPhotosAppearOnlyInHiddenAndOnceDeletedInRecentlyDeleted() {
        val visible = media("visible.jpg", "2024-01-01T00:00:00Z").copy(collectionIds = setOf("album"), isFavorite = true)
        val hidden = media("hidden.jpg", "2024-01-02T00:00:00Z").copy(id = "hidden", collectionIds = setOf("album"),
            isFavorite = true, isHidden = true)
        val deletedHidden = hidden.copy(id = "deleted-hidden", fileName = "deleted.jpg", isTrashed = true)
        val items = listOf(visible, hidden, deletedHidden)
        for (query in listOf(GalleryQuery(), GalleryQuery(collectionId = "album"), GalleryQuery(collectionId = GalleryQuery.FAVORITES),
            GalleryQuery(filename = "jpg"), GalleryQuery(origin = MediaOrigin.ICLOUD))) {
            assertEquals(query.toString(), listOf("visible.jpg"), query.apply(items, ZoneOffset.UTC).map { it.fileName })
        }
        assertEquals(listOf("hidden.jpg"), GalleryQuery(collectionId = GalleryQuery.HIDDEN).apply(items, ZoneOffset.UTC).map { it.fileName })
        assertEquals(listOf("deleted.jpg"), GalleryQuery(trashOnly = true).apply(items, ZoneOffset.UTC).map { it.fileName })
    }

    @Test fun formatLabelsNameRawOriginals() {
        assertEquals("DNG (RAW)", mediaFormatLabel("image/x-adobe-dng"))
        assertEquals("CR3 (RAW)", mediaFormatLabel("image/x-canon-cr3"))
        assertEquals("HEIC", mediaFormatLabel("image/heic"))
        assertEquals("JPEG", mediaFormatLabel("image/jpeg"))
        assertEquals("MOV", mediaFormatLabel("video/quicktime"))
        assertEquals("MP4", mediaFormatLabel("video/mp4"))
        assertTrue(isRawFormat("image/x-sony-arw"))
        assertFalse(isRawFormat("image/heic"))
        // RAW originals cannot go to a legacy Shared Album, which takes only the formats it can show.
        assertFalse(supportsLegacySharedUpload(MediaKind.PHOTO, "image/x-adobe-dng"))
    }

    private fun media(name: String, time: String) = GalleryMedia(name, name, Instant.parse(time).toEpochMilli(), 10, 10,
        MediaOrigin.ICLOUD, MediaAvailability.CLOUD_ONLY, null, null, 0, 0)
}
