package me.lvca.alba.app.messages

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.lvca.alba.app.R
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
