package dev.mela.app

import android.content.Intent
import dev.mela.engine.model.SharedAlbumLinks

/** Null means another intent type; empty means a rejected incoming invitation. */
internal fun incomingSharedInvitation(intent: Intent): String? = when {
    intent.action == Intent.ACTION_SEND && intent.type == "text/plain" ->
        SharedAlbumLinks.extract(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()).orEmpty()
    intent.action == Intent.ACTION_VIEW && intent.data?.scheme in setOf("http", "https") ->
        intent.dataString?.takeIf { SharedAlbumLinks.token(it) != null }.orEmpty()
    else -> null
}
