package me.lvca.alba.engine.model

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId

/**
 * A parsed search: what the user typed, split into the parts Alba understands. Words that name a month, a year or a
 * kind of photo, in English or Italian, become filters; every other word must appear in the file name, title or
 * caption. Everything runs locally over the catalog already on the phone.
 */
data class GallerySearch(
    /** Months, 1 to 12. A photo matches any of them. */
    val months: Set<Int> = emptySet(),
    /** Years. A photo matches any of them. */
    val years: Set<Int> = emptySet(),
    /** Smart collections a word named, such as Videos for "videos" or "video". A photo matches any of them. */
    val collections: Set<String> = emptySet(),
    /** "raw" asks for RAW originals. */
    val raw: Boolean = false,
    /** Normalized words that must each appear in the photo's text. */
    val words: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = months.isEmpty() && years.isEmpty() && collections.isEmpty() && !raw && words.isEmpty()

    /** Whether [media], dated [epochMillis] in [zone], matches every part of this search. */
    fun matches(media: GalleryMedia, epochMillis: Long, zone: ZoneId): Boolean =
        isEmpty || (matchesDate(epochMillis, zone) &&
            (collections.isEmpty() || collections.any { it in media.collectionIds || matchesKind(media, it) }) &&
            (!raw || isRawFormat(media.mimeType)) && matchesWords(media.fileName + " " + media.details.searchableText))

    private fun matchesDate(epochMillis: Long, zone: ZoneId): Boolean {
        if (months.isEmpty() && years.isEmpty()) return true
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return (months.isEmpty() || date.monthValue in months) && (years.isEmpty() || date.year in years)
    }

    private fun matchesWords(text: String): Boolean = words.isEmpty() || normalize(text).let { normalized -> words.all { it in normalized } }

    /** Whether an album called [name] matches the typed words. Albums match on their name alone. */
    fun matchesAlbum(name: String): Boolean = words.isNotEmpty() && matchesWords(name)

    companion object {
        /** Parses [text]. Recognised words are filters; the rest are matched as text. */
        fun parse(text: String): GallerySearch {
            val months = mutableSetOf<Int>()
            val years = mutableSetOf<Int>()
            val collections = mutableSetOf<String>()
            var raw = false
            val words = mutableListOf<String>()
            val tokens = normalize(text).split(SEPARATORS).filter(String::isNotEmpty)
            var index = 0
            while (index < tokens.size) {
                val token = tokens[index]
                // Two-word phrases first: "slow motion", "time lapse", "live photos", "foto live".
                val pair = tokens.getOrNull(index + 1)?.let { "$token $it" }
                val pairType = pair?.let(TYPE_WORDS::get)
                when {
                    pairType != null -> { collections += pairType; index++ }
                    token in TYPE_WORDS -> collections += TYPE_WORDS.getValue(token)
                    token in MONTH_WORDS -> months += MONTH_WORDS.getValue(token)
                    token.length == YEAR_DIGITS && token.all(Char::isDigit) && token.toInt() in YEARS -> years += token.toInt()
                    token == "raw" -> raw = true
                    else -> words += token
                }
                index++
            }
            return GallerySearch(months, years, collections, raw, words)
        }

        /** Lower case without accents, so "Città" and "citta" match. */
        fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")

        private fun matchesKind(media: GalleryMedia, collection: String) = when (collection) {
            SmartCollection.VIDEOS.id -> media.kind == MediaKind.VIDEO
            SmartCollection.LIVE_PHOTOS.id -> media.kind == MediaKind.LIVE_PHOTO
            GalleryQuery.FAVORITES -> media.isFavorite
            else -> false
        }

        private val SEPARATORS = Regex("[\\s,;]+")
        private val DIACRITICS = Regex("\\p{Mn}+")
        private val YEARS = 1900..2100
        private const val YEAR_DIGITS = 4

        private val MONTH_WORDS: Map<String, Int> = buildMap {
            val english = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september",
                "october", "november", "december")
            val italian = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto",
                "settembre", "ottobre", "novembre", "dicembre")
            english.forEachIndexed { index, name -> put(name, index + 1); put(name.take(3), index + 1) }
            // "ago" and "set" are left out: as English words they are more likely text than August or September.
            italian.forEachIndexed { index, name ->
                put(name, index + 1)
                name.take(3).takeIf { it !in setOf("ago", "set") }?.let { putIfAbsent(it, index + 1) }
            }
            put("sept", 9)
        }

        private val TYPE_WORDS: Map<String, String> = buildMap {
            fun words(collection: String, vararg names: String) = names.forEach { put(it, collection) }
            words(SmartCollection.VIDEOS.id, "video", "videos", "filmato", "filmati")
            words(SmartCollection.LIVE_PHOTOS.id, "live", "live photo", "live photos", "foto live")
            words(SmartCollection.SCREENSHOTS.id, "screenshot", "screenshots", "schermata", "schermate")
            words(SmartCollection.PANORAMAS.id, "panorama", "panoramas", "panoramica", "panoramiche")
            words(SmartCollection.BURSTS.id, "burst", "bursts", "raffica", "raffiche", "sequenza", "sequenze")
            words(SmartCollection.SLO_MO.id, "slo-mo", "slomo", "slow-mo", "slow motion", "rallentatore", "rallenty")
            words(SmartCollection.TIME_LAPSE.id, "time-lapse", "timelapse", "time lapse")
            words(GalleryQuery.FAVORITES, "favorite", "favorites", "favourite", "favourites", "preferito", "preferiti",
                "preferita", "preferite")
        }
    }
}
