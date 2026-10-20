package me.lvca.alba.app.gallery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.app.R
import me.lvca.alba.app.diagnostics.AlbaDiagnostics
import me.lvca.alba.app.messages.UserMessages
import me.lvca.alba.app.messages.toUserMessage
import me.lvca.alba.app.platform.NetworkStatus

/** Thumbnail downloads for visible tiles, and the signal that tells tiles to retry after a failure may have cleared. */
internal class PreviewController(
    scope: CoroutineScope,
    repository: GalleryRepository,
    diagnostics: AlbaDiagnostics,
    messages: UserMessages,
    network: StateFlow<NetworkStatus?>,
    selectedMediaId: () -> String?,
    needsPreview: (String) -> Boolean,
) {
    private val retries = MutableStateFlow(0)

    /** Increases whenever previews that failed may now succeed. Visible tiles request theirs again. */
    val retryVersion: StateFlow<Int> = retries.asStateFlow()

    private val queue = PreviewQueue(
        scope = scope,
        selectedMediaId = selectedMediaId,
        needsPreview = needsPreview,
        load = repository::ensurePreview,
        diagnostics = diagnostics,
        onFailure = { error -> messages.show(error.toUserMessage(R.string.message_could_not_load_this_preview)) },
    )

    /** Set after a write failed for lack of space; previews stay paused until storage is freed or the user refreshes. */
    var storageBlocked: Boolean
        get() = queue.storageBlocked
        set(value) { queue.storageBlocked = value }

    init {
        scope.launch {
            var wasOffline = false
            network.map { it?.online }.distinctUntilChanged().collectLatest { online ->
                if (online == false) wasOffline = true
                if (online == true && wasOffline) {
                    // Coalesce network handovers, then retire failed/in-flight reads before
                    // the existing visible-tile effects retry their bounded preview window.
                    delay(300)
                    queue.cancelAndJoin()
                    retry()
                    wasOffline = false
                }
            }
        }
    }

    fun request(mediaId: String) = queue.request(mediaId)
    fun prefetch(mediaIds: List<String>) = queue.prefetch(mediaIds)
    fun cancel(): List<Job> = queue.cancel()
    suspend fun cancelAndJoin() = queue.cancelAndJoin()
    fun retry() = retries.update { it + 1 }
}
