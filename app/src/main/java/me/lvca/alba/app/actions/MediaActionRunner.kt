package me.lvca.alba.app.actions

import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.lvca.alba.app.R
import me.lvca.alba.app.diagnostics.AlbaDiagnostics
import me.lvca.alba.app.diagnostics.DiagnosticAttribute
import me.lvca.alba.app.diagnostics.DiagnosticOperation
import me.lvca.alba.app.diagnostics.DiagnosticOutcome
import me.lvca.alba.app.messages.UserMessages
import me.lvca.alba.app.messages.toUserMessage

/** What a user-started media action does. [diagnosticTag] names it in diagnostics and plays no part in locking. */
internal enum class MediaAction(val diagnosticTag: String) {
    FAVORITE("favorite"),
    ALBUM("album"),
    CLOUD_TRASH("cloud_trash"),
    HIDE("hide"),
    SHARED_ALBUM("shared_album"),
    SHARE("share"),
    SAVE_TO_GALLERY("save"),
    EXPORT("export"),
    REMOVE_OFFLINE_COPY("offline"),
    VERIFIED_TRASH("verified_trash"),
    STORAGE_CLEANUP("storage_cleanup"),
}

/**
 * Identifies one running action for locking. Two actions with equal keys exclude each other, for example two album
 * edits, or two exports of the same photo. Actions with different keys run side by side.
 */
internal data class MediaActionKey(val action: MediaAction, val target: String? = null)

/** What the UI needs to know about running media actions. */
data class MediaWork(
    /** Some media action is running; the UI holds back further library edits until it ends. */
    val busy: Boolean = false,
    /** Copies are being prepared for the share sheet; the user may cancel. */
    val sharing: Boolean = false,
)

/**
 * Runs user-started media actions, each in its own job with a diagnostics span and a fallback error message. Tracks
 * them per [MediaActionKey], so an unrelated action is never dropped because another one is running. Starting an
 * action whose key is already running shows a busy message instead of silently doing nothing. Every message an action
 * shows, success or failure, belongs to its key: starting that action again clears it, and starting a different action
 * leaves it alone.
 *
 * Main-thread confined, like the scope it launches in.
 */
internal class MediaActionRunner(
    private val scope: CoroutineScope,
    private val diagnostics: AlbaDiagnostics,
    private val messages: UserMessages,
) {
    private val jobs = linkedMapOf<MediaActionKey, Job>()
    private val active = MutableStateFlow<Set<MediaActionKey>>(emptySet())
    val running: StateFlow<Set<MediaActionKey>> = active.asStateFlow()

    /** Starts [block] unless an action with the same [key] is still running. Returns whether it started. */
    fun start(key: MediaActionKey, @StringRes fallbackMessage: Int, block: suspend () -> Unit): Boolean {
        if (jobs[key]?.isActive == true) {
            messages.show(R.string.action_still_running)
            return false
        }
        lateinit var job: Job
        job = scope.launch(messages.ownedBy(key), start = CoroutineStart.LAZY) {
            val span = diagnostics.start(
                DiagnosticOperation.MEDIA_ACTION,
                mapOf(DiagnosticAttribute.ACTION to key.action.diagnosticTag),
            )
            // Only this action's own earlier message: another action's stays until it is replaced or dismissed.
            messages.clear(key)
            try {
                block()
                span.finish(DiagnosticOutcome.SUCCESS)
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    span.finish(DiagnosticOutcome.CANCELLED)
                    throw error
                }
                span.finish(DiagnosticOutcome.FAILED, error = error)
                messages.show(error.toUserMessage(fallbackMessage))
            } finally {
                if (jobs[key] === job) jobs.remove(key)
                active.value = jobs.keys.toSet()
            }
        }
        jobs[key] = job
        active.value = jobs.keys.toSet()
        job.start()
        return true
    }

    /** Like [start], but waits for a running action with the same [key] to end instead of refusing to start. */
    fun startAfter(key: MediaActionKey, @StringRes fallbackMessage: Int, block: suspend () -> Unit) {
        val running = jobs[key]?.takeIf { it.isActive }
        if (running == null) start(key, fallbackMessage, block)
        else scope.launch { running.join(); start(key, fallbackMessage, block) }
    }

    fun cancel(key: MediaActionKey) {
        jobs[key]?.cancel()
    }

    /** Cancels every running action and returns their jobs, so the caller can wait for them to end. */
    fun cancelAll(): List<Job> = jobs.values.toList().onEach(Job::cancel)
}
