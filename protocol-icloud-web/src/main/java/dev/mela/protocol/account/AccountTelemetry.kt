package dev.mela.protocol.account

/**
 * Privacy-safe account telemetry boundary. Implementations receive only fixed operation and outcome
 * values, elapsed time, and optional numeric cookie aggregates. Apple Account identifiers, cookie
 * names/domains/values, tokens, responses, absolute expiry times, and error messages never cross
 * this boundary.
 *
 * Cookie expiry is useful for comparing Apple's declared cookie lifetime with the session lifetime
 * that Mela actually validates. It is not an authoritative logout time: Apple can revoke or renew a
 * session independently of a cookie's expiry attribute.
 */
interface AccountTelemetry {
    fun record(
        operation: AccountOperation,
        outcome: AccountOutcome,
        elapsedMillis: Long,
        cookieLifetime: CookieLifetimeMetrics? = null,
    )

    data object None : AccountTelemetry {
        override fun record(
            operation: AccountOperation,
            outcome: AccountOutcome,
            elapsedMillis: Long,
            cookieLifetime: CookieLifetimeMetrics?,
        ) = Unit
    }
}

data class CookieLifetimeMetrics(
    val persistentCookieCount: Int,
    val sessionCookieCount: Int,
    val expiredPersistentCookieCount: Int,
    val earliestRemainingMillis: Long?,
    val latestRemainingMillis: Long?,
)

enum class AccountOperation {
    RESTORE,
    VALIDATE,
    RENEW,
    SIGN_IN,
    TWO_FACTOR,
    RESEND_TWO_FACTOR,
    SIGN_OUT,
    SESSION_REJECTED,
}

enum class AccountOutcome {
    SUCCESS,
    TWO_FACTOR_REQUIRED,
    PHOTOS_NOT_ENABLED,
    EXPIRED,
    OFFLINE,
    INVALID_CREDENTIALS,
    INVALID_CODE,
    TERMS_REQUIRED,
    UNSUPPORTED,
    NETWORK_ERROR,
    MALFORMED_RESPONSE,
    FAILED,
}

internal inline fun <T> AccountTelemetry.measure(
    operation: AccountOperation,
    block: () -> T,
): T {
    val started = System.nanoTime()
    return try {
        block().also { record(operation, AccountOutcome.SUCCESS, elapsedSince(started)) }
    } catch (error: Throwable) {
        record(operation, error.accountOutcome(), elapsedSince(started))
        throw error
    }
}

internal fun elapsedSince(startedNanos: Long): Long =
    ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0L)
