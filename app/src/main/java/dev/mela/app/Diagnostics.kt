package dev.mela.app

import android.content.Context
import dev.mela.protocol.account.AccountOperation
import dev.mela.protocol.account.AccountOutcome
import dev.mela.protocol.account.AccountTelemetry
import dev.mela.protocol.account.CookieLifetimeMetrics
import io.sentry.Breadcrumb
import io.sentry.DataCollection
import io.sentry.Hint
import io.sentry.ITransaction
import io.sentry.KeyValueCollectionBehavior
import io.sentry.MeasurementUnit
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SpanStatus
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.Message
import io.sentry.protocol.User
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DiagnosticOperation(val wireName: String, val sampleRate: Double) {
    APP_INITIALIZED("app.initialized", 1.0),
    GALLERY_REFRESH("gallery.refresh", 1.0),
    DEVICE_REFRESH("gallery.device_refresh", 0.25),
    PREVIEW_DOWNLOAD("media.preview", 0.10),
    VIEWER_DOWNLOAD("media.viewer", 0.25),
    PLAYBACK_OPEN("media.playback", 0.25),
    ICLOUD_STORAGE("icloud.storage", 1.0),
    MEDIA_ACTION("media.action", 0.50),
    BATCH_ACTION("media.batch", 1.0),
    SHARED_ALBUM("shared_album.action", 1.0),
    BACKUP_ENROLLMENT("backup.enrollment", 1.0),
    MAINTENANCE_WAKE("backup.wake", 1.0),
}

enum class DiagnosticAttribute(val wireName: String) {
    ACTION("action"),
    REASON("reason"),
    ITEM_COUNT("item_count"),
    CLOUD_COUNT("cloud_count"),
    DEVICE_COUNT("device_count"),
    BYTE_COUNT("byte_count"),
    RECOVERED_COUNT("recovered_count"),
    RECONCILED_COUNT("reconciled_count"),
    STAGED_COUNT("staged_count"),
    INVOKED_COUNT("invoked_count"),
    MORE_WORK("more_work"),
    SHARED_UNAVAILABLE("shared_unavailable"),
}

enum class DiagnosticOutcome { SUCCESS, FAILED, CANCELLED }

interface DiagnosticSpan {
    fun finish(
        outcome: DiagnosticOutcome,
        attributes: Map<DiagnosticAttribute, Any> = emptyMap(),
        error: Throwable? = null,
    )
}

interface MelaDiagnostics {
    val enabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)
    fun start(
        operation: DiagnosticOperation,
        attributes: Map<DiagnosticAttribute, Any> = emptyMap(),
    ): DiagnosticSpan

    data object None : MelaDiagnostics {
        private val state = MutableStateFlow(false)
        override val enabled: StateFlow<Boolean> = state
        override fun setEnabled(enabled: Boolean) = Unit
        override fun start(operation: DiagnosticOperation, attributes: Map<DiagnosticAttribute, Any>) =
            object : DiagnosticSpan {
                override fun finish(
                    outcome: DiagnosticOutcome,
                    attributes: Map<DiagnosticAttribute, Any>,
                    error: Throwable?,
                ) = Unit
            }
    }
}

class SentryMelaDiagnostics(context: Context) : MelaDiagnostics, AccountTelemetry {
    val available = BuildConfig.SENTRY_DSN.isNotBlank()
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val sessionStore = SessionLifetimeStore(preferences)
    private val installationId = preferences.getString(INSTALLATION_ID, null)
        ?: java.util.UUID.randomUUID().toString().also {
            preferences.edit().putString(INSTALLATION_ID, it).apply()
        }
    private val initialized = AtomicBoolean(false)
    private val mutableEnabled = MutableStateFlow(available && preferences.getBoolean(ENABLED, true))
    override val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    fun initialize() {
        if (mutableEnabled.value) initializeSentry()
    }

    override fun setEnabled(enabled: Boolean) {
        if (!available) return
        preferences.edit().putBoolean(ENABLED, enabled).apply()
        mutableEnabled.value = enabled
        if (enabled) initializeSentry() else if (initialized.compareAndSet(true, false)) Sentry.close()
    }

    override fun start(
        operation: DiagnosticOperation,
        attributes: Map<DiagnosticAttribute, Any>,
    ): DiagnosticSpan {
        if (!enabled.value || !initialized.get()) return MelaDiagnostics.None.start(operation, attributes)
        val transaction = Sentry.startTransaction("mela.${operation.wireName}", "mela.${operation.wireName}")
        transaction.applyAttributes(attributes)
        return SentryDiagnosticSpan(operation, transaction)
    }

    override fun record(
        operation: AccountOperation,
        outcome: AccountOutcome,
        elapsedMillis: Long,
        cookieLifetime: CookieLifetimeMetrics?,
    ) {
        val lifetime = sessionStore.record(operation, outcome)
        if (!enabled.value || !initialized.get()) return

        val transaction = Sentry.startTransaction(
            "mela.account.${operation.name.lowercase(Locale.ROOT)}",
            "mela.account",
        )
        transaction.setData("outcome", outcome.name.lowercase(Locale.ROOT))
        transaction.setData("elapsed_ms", elapsedMillis.coerceAtLeast(0L))
        lifetime?.let {
            val ageHours = it.ageMillis / 3_600_000.0
            transaction.setData("session_age_hours", ageHours)
            transaction.setData("validation_count", it.validationCount)
            transaction.setData("renewal_count", it.renewalCount)
            transaction.setMeasurement("session_age_hours", ageHours, MeasurementUnit.Duration.HOUR)
            transaction.setMeasurement("validation_count", it.validationCount)
            transaction.setMeasurement("renewal_count", it.renewalCount)
        }
        transaction.applyCookieLifetime(cookieLifetime)
        transaction.finish(if (outcome.isSuccessLike()) SpanStatus.OK else SpanStatus.INTERNAL_ERROR)

        if (!outcome.isSuccessLike()) {
            captureOutcome(
                name = "mela.account.${outcome.name.lowercase(Locale.ROOT)}",
                operation = operation.name.lowercase(Locale.ROOT),
                errorType = null,
                lifetime = lifetime,
                cookieLifetime = cookieLifetime,
            )
        }
    }

    private fun initializeSentry() {
        if (!available) return
        if (!initialized.compareAndSet(false, true)) return
        SentryAndroid.init(appContext) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.environment = BuildConfig.SENTRY_ENVIRONMENT
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.dist = BuildConfig.VERSION_CODE.toString()
            options.sampleRate = 1.0
            options.tracesSampler = { context ->
                val operation = context.transactionContext.operation.removePrefix("mela.")
                DiagnosticOperation.entries.firstOrNull { it.wireName == operation }?.sampleRate ?: 1.0
            }
            options.profilesSampleRate = 0.05
            options.isEnableAppStartProfiling = true
            options.isAnrEnabled = true
            options.anrProfilingSampleRate = 1.0
            options.isReportHistoricalAnrs = true
            options.isAttachAnrThreadDump = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.isEnableAutoSessionTracking = false
            options.isEnableUserInteractionTracing = false
            options.isEnableUserInteractionBreadcrumbs = false
            options.isEnableActivityLifecycleBreadcrumbs = false
            options.isEnableSystemEventBreadcrumbs = false
            options.isEnableAppComponentBreadcrumbs = false
            options.isEnableNetworkEventBreadcrumbs = false
            options.maxBreadcrumbs = 50
            options.isSendDefaultPii = false
            options.isCollectAdditionalContext = false
            options.isCollectExternalStorageContext = false
            options.isEnableRootCheck = false
            options.isEnableScreenTracking = false
            options.sessionReplay.sessionSampleRate = 0.0
            options.sessionReplay.onErrorSampleRate = 0.0
            options.logs.isEnabled = false
            options.metrics.isEnabled = false
            options.dataCollection = strictDataCollection()
            options.beforeBreadcrumb = { breadcrumb: Breadcrumb, _: Hint? ->
                breadcrumb.takeIf { it.category?.startsWith("mela.") == true }
            }
            options.beforeSend = { event: SentryEvent, _: Hint -> sanitize(event) }
            options.beforeSendTransaction = { transaction, _ -> sanitize(transaction) }
        }
        Sentry.configureScope { scope -> scope.user = anonymousUser() }
    }

    private inner class SentryDiagnosticSpan(
        private val operation: DiagnosticOperation,
        private val transaction: ITransaction,
    ) : DiagnosticSpan {
        private val finished = AtomicBoolean(false)

        override fun finish(
            outcome: DiagnosticOutcome,
            attributes: Map<DiagnosticAttribute, Any>,
            error: Throwable?,
        ) {
            if (!finished.compareAndSet(false, true)) return
            transaction.applyAttributes(attributes)
            transaction.finish(when (outcome) {
                DiagnosticOutcome.SUCCESS -> SpanStatus.OK
                DiagnosticOutcome.CANCELLED -> SpanStatus.CANCELLED
                DiagnosticOutcome.FAILED -> SpanStatus.INTERNAL_ERROR
            })
            if (outcome == DiagnosticOutcome.FAILED) {
                captureOutcome(
                    name = "mela.operation.failed",
                    operation = operation.wireName,
                    errorType = error?.let(::safeErrorType),
                    lifetime = null,
                    cookieLifetime = null,
                )
            }
        }
    }

    private fun captureOutcome(
        name: String,
        operation: String,
        errorType: String?,
        lifetime: SessionLifetime?,
        cookieLifetime: CookieLifetimeMetrics?,
    ) {
        val event = SentryEvent().apply {
            level = SentryLevel.WARNING
            message = Message().apply { formatted = name }
            setTag("mela.operation", operation)
            errorType?.let { setTag("mela.error_type", it) }
            lifetime?.let {
                setExtra("session_age_hours", it.ageMillis / 3_600_000.0)
                setExtra("validation_count", it.validationCount)
                setExtra("renewal_count", it.renewalCount)
            }
            cookieLifetime?.asSentryData()?.forEach(::setExtra)
        }
        Sentry.captureEvent(event)
    }

    private fun ITransaction.applyAttributes(attributes: Map<DiagnosticAttribute, Any>) {
        attributes.forEach { (key, value) ->
            if (value is String) require(value in SAFE_ATTRIBUTE_VALUES) {
                "Diagnostic string attributes must use an approved value"
            }
            setData(key.wireName, value)
        }
    }

    private fun ITransaction.applyCookieLifetime(cookieLifetime: CookieLifetimeMetrics?) {
        cookieLifetime?.asSentryData()?.forEach { (key, value) ->
            setData(key, value)
            if (key.endsWith("_hours")) {
                setMeasurement(key, value, MeasurementUnit.Duration.HOUR)
            } else {
                setMeasurement(key, value)
            }
        }
    }

    private companion object {
        const val PREFERENCES = "mela_diagnostics"
        const val ENABLED = "enabled"
        const val INSTALLATION_ID = "installation_id"
        val SAFE_ATTRIBUTE_VALUES = setOf(
            "automatic_backup", "batch", "foreground", "background", "process_started",
            "picker_upload", "upload", "offline", "share", "save", "export", "storage_cleanup",
            "favorite", "album", "cloud_trash", "shared_album", "connection_check",
        )
        val SAFE_ACCOUNT_EXTRAS = setOf(
            "session_age_hours",
            "validation_count",
            "renewal_count",
            "persistent_cookie_count",
            "session_cookie_count",
            "expired_persistent_cookie_count",
            "earliest_cookie_ttl_hours",
            "latest_cookie_ttl_hours",
        )

        fun strictDataCollection() = DataCollection().apply {
            userInfo = false
            cookies = KeyValueCollectionBehavior.off()
            urlQueryParams = KeyValueCollectionBehavior.off()
            httpBodies = emptySet()
            databaseQueryData = false
            filePaths = false
            httpHeaders.request = KeyValueCollectionBehavior.off()
            httpHeaders.response = KeyValueCollectionBehavior.off()
            graphql.document = false
            graphql.variables = false
        }

        fun safeErrorType(error: Throwable): String =
            error::class.java.simpleName.take(80).filter { it.isLetterOrDigit() || it == '_' }

    }

    private fun anonymousUser() = User().apply {
        id = installationId
        // Sentry's mobile SDK otherwise asks Relay to infer location from the connection IP.
        // A fixed unroutable value prevents that while preserving the anonymous installation ID.
        ipAddress = "0.0.0.0"
        geo = null
    }

    private fun sanitize(event: SentryEvent): SentryEvent {
        event.user = anonymousUser()
        event.request = null
        event.serverName = null
        event.contexts.device?.id = null
        event.breadcrumbs = event.breadcrumbs?.filter { it.category?.startsWith("mela.") == true }
        event.exceptions?.forEach { exception -> exception.value = null }
        event.throwable = null
        event.extras?.keys?.retainAll(SAFE_ACCOUNT_EXTRAS)
        return event
    }

    private fun sanitize(transaction: io.sentry.protocol.SentryTransaction): io.sentry.protocol.SentryTransaction {
        transaction.user = anonymousUser()
        transaction.request = null
        transaction.serverName = null
        transaction.contexts.device?.id = null
        transaction.breadcrumbs = null
        return transaction
    }
}

private fun CookieLifetimeMetrics.asSentryData(): Map<String, Number> = buildMap {
    put("persistent_cookie_count", persistentCookieCount)
    put("session_cookie_count", sessionCookieCount)
    put("expired_persistent_cookie_count", expiredPersistentCookieCount)
    earliestRemainingMillis?.let { put("earliest_cookie_ttl_hours", it / 3_600_000.0) }
    latestRemainingMillis?.let { put("latest_cookie_ttl_hours", it / 3_600_000.0) }
}

private data class SessionLifetime(
    val ageMillis: Long,
    val validationCount: Int,
    val renewalCount: Int,
)

private class SessionLifetimeStore(
    private val preferences: android.content.SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun record(operation: AccountOperation, outcome: AccountOutcome): SessionLifetime? {
        val current = now()
        var authenticatedAt = preferences.getLong(AUTHENTICATED_AT, 0L)
        var validations = preferences.getInt(VALIDATIONS, 0)
        var renewals = preferences.getInt(RENEWALS, 0)
        val authenticated = outcome == AccountOutcome.SUCCESS || outcome == AccountOutcome.PHOTOS_NOT_ENABLED

        if ((operation == AccountOperation.SIGN_IN || operation == AccountOperation.TWO_FACTOR) &&
            authenticated
        ) {
            authenticatedAt = current
            validations = 0
            renewals = 0
        } else if ((operation == AccountOperation.RESTORE || operation == AccountOperation.VALIDATE) &&
            authenticated
        ) {
            if (authenticatedAt == 0L) authenticatedAt = current
            if (operation == AccountOperation.VALIDATE) validations += 1
        } else if (operation == AccountOperation.RENEW && outcome == AccountOutcome.SUCCESS) {
            if (authenticatedAt == 0L) authenticatedAt = current
            renewals += 1
        }

        val lifetime = authenticatedAt.takeIf { it > 0L }?.let {
            SessionLifetime((current - it).coerceAtLeast(0L), validations, renewals)
        }
        val ended = operation == AccountOperation.SIGN_OUT ||
            outcome == AccountOutcome.EXPIRED || operation == AccountOperation.SESSION_REJECTED
        val editor = preferences.edit()
        if (ended) editor.remove(AUTHENTICATED_AT).remove(VALIDATIONS).remove(RENEWALS)
        else editor.putLong(AUTHENTICATED_AT, authenticatedAt).putInt(VALIDATIONS, validations).putInt(RENEWALS, renewals)
        editor.apply()
        return lifetime
    }

    private companion object {
        const val AUTHENTICATED_AT = "authenticated_at"
        const val VALIDATIONS = "validation_count"
        const val RENEWALS = "renewal_count"
    }
}

private fun AccountOutcome.isSuccessLike() =
    this == AccountOutcome.SUCCESS || this == AccountOutcome.TWO_FACTOR_REQUIRED ||
        this == AccountOutcome.PHOTOS_NOT_ENABLED
