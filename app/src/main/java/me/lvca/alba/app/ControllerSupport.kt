package me.lvca.alba.app

import android.content.Intent
import android.content.IntentSender
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.lvca.alba.protocol.auth.AppleProtocolError
import me.lvca.alba.protocol.auth.AppleProtocolException

/** The one transient message the UI shows. A newer message replaces an older one. */
internal class UserMessages {
    private val current = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = current.asStateFlow()

    fun show(message: UiMessage) { current.value = message }
    fun show(@StringRes resource: Int) = show(UiMessage(resource))
    fun clear() { current.value = null }
}

/** A one-shot platform request that the UI performs once, even across a configuration change. */
sealed interface AlbaEffect {
    /** Open the system share sheet for prepared copies. */
    data class Share(val intent: Intent) : AlbaEffect

    /** Ask Android to confirm moving a verified phone original to its trash. */
    data class ConfirmVerifiedTrash(val request: IntentSender) : AlbaEffect

    /** Ask for the media permissions automatic backup needs before it can be enabled. */
    data class RequestBackupPermissions(val permissions: List<String>) : AlbaEffect
}

internal fun Throwable.toUserMessage(@StringRes fallback: Int): UiMessage = when (this) {
    is AppleProtocolException -> UiMessage(when (error) {
        AppleProtocolError.INVALID_CREDENTIALS -> R.string.invalid_credentials
        AppleProtocolError.INVALID_TWO_FACTOR_CODE -> R.string.invalid_two_factor_code
        AppleProtocolError.SESSION_EXPIRED -> R.string.session_expired
        AppleProtocolError.TERMS_UPDATE_REQUIRED -> R.string.terms_update_required
        AppleProtocolError.PHOTOS_UNAVAILABLE -> R.string.apple_did_not_provide_a_photos_service_for_this_account
        AppleProtocolError.UNSUPPORTED_TWO_FACTOR -> R.string.unsupported_two_factor
        AppleProtocolError.NETWORK -> R.string.network_error
        AppleProtocolError.MALFORMED_RESPONSE -> R.string.malformed_response
    })
    else -> localizedStoredMessage(message, fallback)
}

/** Runs [block] inside a diagnostics span that records success, failure or cancellation. */
internal suspend fun <T> AlbaDiagnostics.measure(
    operation: DiagnosticOperation,
    attributes: Map<DiagnosticAttribute, Any> = emptyMap(),
    block: suspend () -> T,
): T {
    val span = start(operation, attributes)
    return try {
        block().also { span.finish(DiagnosticOutcome.SUCCESS) }
    } catch (error: Throwable) {
        span.finish(
            if (error is CancellationException) DiagnosticOutcome.CANCELLED else DiagnosticOutcome.FAILED,
            error = error.takeUnless { it is CancellationException },
        )
        throw error
    }
}
