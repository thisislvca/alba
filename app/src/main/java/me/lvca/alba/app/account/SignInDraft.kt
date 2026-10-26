package me.lvca.alba.app.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The password being typed on the sign-in form. It lives in the view model's memory only: it survives rotation and
 * other configuration changes, but never reaches saved instance state, so Android cannot write it to disk and it is
 * gone after process death. The Apple Account name and verification code use saved state instead.
 */
class SignInDraft {
    var password by mutableStateOf("")
}

/** A sign-in problem the form shows next to the field it concerns, rather than as a passing message. */
enum class SignInProblem {
    /** Apple rejected the account name or password. */
    WRONG_PASSWORD,

    /** Apple rejected the verification code. */
    WRONG_CODE,

    /** The account uses hardware security keys, which Apple's web sign-in cannot offer to Alba. */
    SECURITY_KEY,

    /** Apple refused because of too many recent attempts. */
    TOO_MANY_ATTEMPTS,
}
