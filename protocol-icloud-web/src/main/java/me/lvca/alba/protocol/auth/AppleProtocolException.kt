package me.lvca.alba.protocol.auth

enum class AppleProtocolError {
    INVALID_CREDENTIALS,
    INVALID_TWO_FACTOR_CODE,
    SESSION_EXPIRED,
    TERMS_UPDATE_REQUIRED,
    PHOTOS_UNAVAILABLE,
    UNSUPPORTED_TWO_FACTOR,
    NETWORK,
    MALFORMED_RESPONSE,

    /** "Access iCloud Data on the Web" is off, which Advanced Data Protection does by default. */
    WEB_ACCESS_DISABLED,

    /** The account signs in with a hardware security key, which the web sign-in Alba uses cannot offer. */
    SECURITY_KEY_REQUIRED,

    /** Apple refused the attempt because of too many recent tries (HTTP 429). */
    TOO_MANY_ATTEMPTS,
}

open class AppleProtocolException(
    val error: AppleProtocolError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Apple asked Alba to slow down: HTTP 429 or 503, or a CloudKit THROTTLED, TRY_AGAIN_LATER or ZONE_BUSY error.
 * [retryAfterMillis] is the wait Apple asked for, when it said. A library read waits and tries again.
 */
class AppleThrottledException(
    message: String,
    override val retryAfterMillis: Long?,
) : AppleProtocolException(AppleProtocolError.NETWORK, message), me.lvca.alba.engine.source.ThrottledFailure
