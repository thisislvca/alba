package me.lvca.alba.engine.model

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId

/**
 * A parsed search: what the user typed, split into the parts Alba understands. Words that name a month, a year or a
 * kind of photo, in English or Italian, are filters, but a photo whose text contains the word matches too, so "live
 * music" or a caption saying "march" still find what they say. Every other word must appear in the file name, title or
 * caption. Filler words such as "photos" or "di" are ignored. Everything runs locally over the catalog on the phone.
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
    /** The normalized words behind the filters above; a photo whose text contains one of a filter's words also matches it. */
    val filterWords: Map<Filter, Set<String>> = emptyMap(),
) {
    enum class Filter { MONTH, YEAR, TYPE, RAW }

    val isEmpty: Boolean get() = months.isEmpty() && years.isEmpty() && collections.isEmpty() && !raw && words.isEmpty()

    /** Whether [media], dated [epochMillis] in [zone], matches every part of this search. */
    fun matches(media: GalleryMedia, epochMillis: Long, zone: ZoneId): Boolean {
        if (isEmpty) return true
        val text = media.searchHaystack
        fun saidIn(filter: Filter) = filterWords[filter].orEmpty().any { it in text }
        val date by lazy(LazyThreadSafetyMode.NONE) { Instant.ofEpochMilli(epochMillis).atZone(zone) }
        return (months.isEmpty() || date.monthValue in months || saidIn(Filter.MONTH)) &&
            (years.isEmpty() || date.year in years || saidIn(Filter.YEAR)) &&
            (collections.isEmpty() || collections.any { it in media.collectionIds || matchesKind(media, it) } || saidIn(Filter.TYPE)) &&
            (!raw || isRawFormat(media.mimeType) || saidIn(Filter.RAW)) &&
            words.all { it in text }
    }

    /** Whether an album called [name] matches. Albums match on their name alone, by every word typed. */
    fun matchesAlbum(name: String): Boolean {
        val all = words + filterWords.values.flatten()
        if (all.isEmpty()) return false
        val album = normalize(name)
        return all.all { it in album }
    }

    companion object {
        /** Parses [text]. Recognised words are filters; the rest are matched as text. */
        fun parse(text: String): GallerySearch {
            val months = mutableSetOf<Int>()
            val years = mutableSetOf<Int>()
            val collections = mutableSetOf<String>()
            var raw = false
            val words = mutableListOf<String>()
            val filterWords = mutableMapOf<Filter, MutableSet<String>>()
            fun said(filter: Filter, word: String) { filterWords.getOrPut(filter, ::mutableSetOf) += word }
            val tokens = normalize(text).split(SEPARATORS).filter { it.isNotEmpty() && it !in FILLER }
            var index = 0
            while (index < tokens.size) {
                val token = tokens[index]
                // Two-word phrases first: "slow motion", "time lapse", "live photos", "foto live".
                val pair = tokens.getOrNull(index + 1)?.let { "$token $it" }
                val pairType = pair?.let(TYPE_WORDS::get)
                when {
                    pairType != null -> { collections += pairType; said(Filter.TYPE, pair); index++ }
                    token in TYPE_WORDS -> { collections += TYPE_WORDS.getValue(token); said(Filter.TYPE, token) }
                    token in MONTH_WORDS -> { months += MONTH_WORDS.getValue(token); said(Filter.MONTH, token) }
                    token.length == YEAR_DIGITS && token.all(Char::isDigit) && token.toInt() in YEARS -> {
                        years += token.toInt(); said(Filter.YEAR, token)
                    }
                    token == "raw" -> { raw = true; said(Filter.RAW, token) }
                    else -> words += token
                }
                index++
            }
            return GallerySearch(months, years, collections, raw, words, filterWords)
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

        /** Words that say nothing about which photo is meant, in English and Italian. */
        private val FILLER = setOf("photo", "photos", "picture", "pictures", "pic", "pics", "image", "images", "foto",
            "immagine", "immagini", "of", "the", "from", "in", "di", "del", "della", "dei", "delle", "da", "a")

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
