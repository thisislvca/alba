package me.lvca.alba.app

import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import me.lvca.alba.app.ui.ExternalUse
import java.util.concurrent.atomic.AtomicLong

/** A one-shot platform request that the UI performs once, even across a configuration change. */
sealed interface AlbaEffect {
    /** Open the system share sheet for prepared copies. */
    data class Share(val intent: Intent) : AlbaEffect

    /** Ask Android to confirm moving a verified phone original to its trash. */
    data class ConfirmVerifiedTrash(val request: IntentSender) : AlbaEffect

    /** Hand a prepared copy to another app, or print it. [uri] is a FileProvider copy readable through a grant. */
    data class UseElsewhere(val use: ExternalUse, val uri: Uri, val mimeType: String, val name: String) : AlbaEffect

    /** Ask for the media permissions automatic backup needs before it can be enabled. */
    data class RequestBackupPermissions(val permissions: List<String>) : AlbaEffect
}

/** An [AlbaEffect] waiting for the UI. [id] tells apart two equal requests made one after the other. */
data class PendingEffect(val id: Long, val effect: AlbaEffect)

/**
 * The [AlbaEffect]s waiting for the UI, oldest first. A request stays queued until the UI reports it handled, so it
 * still reaches the UI when the collector is cancelled, for example by the activity stopping, between receiving the
 * request and acting on it.
 */
internal class EffectQueue {
    private val ids = AtomicLong()
    private val pending = MutableStateFlow<List<PendingEffect>>(emptyList())

    /** The oldest request not yet [handled]. Every new collector receives it again until it is. */
    val next: Flow<PendingEffect> = pending.map { it.firstOrNull() }.distinctUntilChanged().filterNotNull()

    fun send(effect: AlbaEffect) {
        val request = PendingEffect(ids.incrementAndGet(), effect)
        pending.update { it + request }
    }

    fun handled(effect: PendingEffect) = pending.update { queue -> queue.filterNot { it.id == effect.id } }
}
