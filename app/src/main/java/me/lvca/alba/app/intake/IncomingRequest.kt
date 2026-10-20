package me.lvca.alba.app.intake

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import me.lvca.alba.engine.model.SharedAlbumLinks

/** What another app asked Alba to show: shared media, or an iCloud shared-album invitation. */
internal sealed interface IncomingRequest {
    /** Shared or opened media. An empty [uris] list is a request Alba refused to read. */
    data class Media(val uris: List<String>, val savedCopy: Boolean = false) : IncomingRequest

    /** A shared-album invitation. A blank [link] is a link Alba rejected as not first-party. */
    data class Invitation(val link: String) : IncomingRequest

    companion object {
        /** Null when [intent] is neither a media share nor an invitation, for example a launcher start. */
        fun from(intent: Intent): IncomingRequest? =
            incomingSharedInvitation(intent)?.let(::Invitation) ?: incomingMedia(intent)
    }
}

/** The incoming request on screen, and whether finishing with it returns to the app that sent it. */
internal data class IncomingSession(val request: IncomingRequest?, val returnToCaller: Boolean) {
    /** Keeps the request across recreation so a handled intent is not parsed (and acted on) twice. */
    fun save(state: Bundle) {
        state.putBoolean(HANDLED, true)
        when (request) {
            is IncomingRequest.Invitation -> state.putString(INVITATION, request.link)
            is IncomingRequest.Media -> {
                state.putStringArrayList(URIS, ArrayList(request.uris))
                state.putBoolean(SAVED_COPY, request.savedCopy)
            }
            null -> Unit
        }
        state.putBoolean(RETURN_TO_CALLER, returnToCaller)
    }

    companion object {
        val None = IncomingSession(null, returnToCaller = false)
        private const val HANDLED = "incomingHandled"
        private const val INVITATION = "incomingInvitation"
        private const val URIS = "incomingUris"
        private const val SAVED_COPY = "savedCopy"
        private const val RETURN_TO_CALLER = "returnToCaller"

        fun from(intent: Intent): IncomingSession =
            IncomingRequest.from(intent).let { IncomingSession(it, returnToCaller = it != null) }

        /** Null when [state] holds no handled request, so the launching intent should be read instead. */
        fun restore(state: Bundle?): IncomingSession? {
            if (state?.getBoolean(HANDLED) != true) return null
            val request = state.getString(INVITATION)?.let(IncomingRequest::Invitation)
                ?: state.getStringArrayList(URIS)?.let { IncomingRequest.Media(it, state.getBoolean(SAVED_COPY)) }
            return IncomingSession(request, state.getBoolean(RETURN_TO_CALLER))
        }
    }
}

/** An incoming content reference, reduced to the parts the safety check reads. */
internal data class IncomingUri(val value: String, val scheme: String?, val authority: String?)

internal const val MAX_INCOMING_ITEMS = 50
private const val MAX_INCOMING_URI_CHARACTERS = 32_000

/**
 * The references Alba may open. Never turn a file path, a network URL, or an unbounded incoming payload into a read:
 * any unsafe candidate refuses the whole request.
 */
internal fun safeIncomingUris(candidates: List<IncomingUri>): List<String> {
    val distinct = candidates.distinctBy(IncomingUri::value)
    val values = distinct.map(IncomingUri::value)
    val safe = values.size in 1..MAX_INCOMING_ITEMS && values.sumOf { it.length } <= MAX_INCOMING_URI_CHARACTERS &&
        distinct.all { it.scheme == "content" && !it.authority.isNullOrBlank() }
    return if (safe) values else emptyList()
}

@Suppress("DEPRECATION")
internal fun incomingMedia(intent: Intent): IncomingRequest.Media? {
    if (intent.action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
    val candidates: List<Uri> = when (intent.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }.ifEmpty {
        intent.clipData?.let { clip -> (0 until minOf(clip.itemCount, MAX_INCOMING_ITEMS + 1)).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
    }
    return IncomingRequest.Media(safeIncomingUris(candidates.map { IncomingUri(it.toString(), it.scheme, it.authority) }))
}

/** Null means another intent type; empty means a rejected incoming invitation. */
internal fun incomingSharedInvitation(intent: Intent): String? = sharedInvitationLink(
    action = intent.action, type = intent.type, text = intent.getStringExtra(Intent.EXTRA_TEXT),
    dataScheme = intent.data?.scheme, dataString = intent.dataString,
)

/** [incomingSharedInvitation] on the intent's parts, so it can run without Android. */
internal fun sharedInvitationLink(action: String?, type: String?, text: String?, dataScheme: String?, dataString: String?): String? = when {
    action == Intent.ACTION_SEND && type == "text/plain" -> SharedAlbumLinks.extract(text.orEmpty()).orEmpty()
    action == Intent.ACTION_VIEW && dataScheme in setOf("http", "https") ->
        dataString?.takeIf { SharedAlbumLinks.token(it) != null }.orEmpty()
    else -> null
}
