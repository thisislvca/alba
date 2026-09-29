package dev.mela.protocol.account

data class AppleSessionSnapshot(
    val accountName: String,
    val clientId: String,
    val accountCountryCode: String,
    val sessionId: String,
    val sessionToken: String,
    val trustToken: String?,
    val dsid: String,
    val webservices: Map<String, String>,
    val cookies: List<PersistedCookie>,
    val displayName: String? = null,
)

data class PersistedCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAtEpochMillis: Long?,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
)

interface AppleSessionStore {
    suspend fun load(): AppleSessionSnapshot?

    suspend fun save(snapshot: AppleSessionSnapshot)

    suspend fun clear()
}

internal fun AppleSessionSnapshot.cookieLifetimeMetrics(
    nowEpochMillis: Long = System.currentTimeMillis(),
): CookieLifetimeMetrics {
    val expiries = cookies.mapNotNull(PersistedCookie::expiresAtEpochMillis)
    val remaining = expiries.map { (it - nowEpochMillis).coerceAtLeast(0L) }
    return CookieLifetimeMetrics(
        persistentCookieCount = expiries.size,
        sessionCookieCount = cookies.size - expiries.size,
        expiredPersistentCookieCount = expiries.count { it <= nowEpochMillis },
        earliestRemainingMillis = remaining.minOrNull(),
        latestRemainingMillis = remaining.maxOrNull(),
    )
}
