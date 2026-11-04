package me.lvca.alba.engine.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The write paths a remote protocol status can pause, by the [key] the status document names them with. Reads are
 * never paused: browsing and verification keep working.
 */
enum class ProtocolArea(val key: String) {
    /** Uploads to the personal library: automatic backup and photos the user chose. */
    UPLOADS("uploads"),

    /** Changes to the personal library: favorites, albums, Hidden and iCloud's Recently Deleted. */
    LIBRARY_EDITS("library-edits"),

    /** Changes to Shared Albums: contributions, invitations, comments, likes, and creating or editing albums. */
    SHARED_ALBUMS("shared-albums"),
    ;

    companion object {
        /** The key that names every area at once. */
        const val ALL = "all"

        fun fromKey(key: String): ProtocolArea? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A pause the status document lists for this app version. [messages] holds the optional explanation by language
 * code ("en", "it"); the app words its own default when there is none.
 */
data class ProtocolPause(
    val id: String,
    val areas: Set<ProtocolArea>,
    val messages: Map<String, String> = emptyMap(),
) {
    /** The message in [language], else in English, else in any language given; null when the document has none. */
    fun message(language: String): String? = messages[language] ?: messages["en"] ?: messages.values.firstOrNull()
}

/**
 * Whether Alba may use a write path of the iCloud web protocol now. An implementation reads a remote status document,
 * caches it, and fails open: when it cannot tell, nothing is paused.
 */
interface ProtocolStatusGate {
    /** The pauses last known to apply to this app version, without fetching. */
    val pauses: Flow<List<ProtocolPause>>

    /**
     * The pause covering [area], if any, after bringing a stale status up to date. Never throws for a network or
     * document problem: those leave the last good status in place, or nothing paused.
     */
    suspend fun pauseFor(area: ProtocolArea): ProtocolPause?

    /** Nothing is ever paused: tests, the demo and builds without a status document. */
    object Open : ProtocolStatusGate {
        override val pauses: Flow<List<ProtocolPause>> = flowOf(emptyList())
        override suspend fun pauseFor(area: ProtocolArea): ProtocolPause? = null
    }
}
