package dev.mela.protocol.auth

enum class AppleProtocolError {
    INVALID_CREDENTIALS,
    INVALID_TWO_FACTOR_CODE,
    SESSION_EXPIRED,
    TERMS_UPDATE_REQUIRED,
    PHOTOS_UNAVAILABLE,
    UNSUPPORTED_TWO_FACTOR,
    NETWORK,
    MALFORMED_RESPONSE,
}

class AppleProtocolException(
    val error: AppleProtocolError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
