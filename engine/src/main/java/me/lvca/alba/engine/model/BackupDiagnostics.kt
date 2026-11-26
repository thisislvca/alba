package me.lvca.alba.engine.model

/** A traced step of backup. Its [id] is what diagnostics see; keep the ids. */
enum class BackupPhase(val id: String) {
    ROUND("backup.round"),
    STAGE("backup.stage"),
    UPLOAD("backup.upload"),
    PUT_ASSET("backup.put_asset"),
    RECEIPT_MATCH("backup.receipt_match"),
    HEAL_CHECK("backup.heal_check"),
    BYTE_CHECK("backup.byte_check"),
    MOTION_READ("motion.read"),
    LIVE_ATTACH("live.attach"),
    LIVE_ASSETS_UPLOAD("live.assets_upload"),
    LIVE_VIDEO_POST("live.video_post"),
    LIVE_RECORDS_MODIFY("live.records_modify"),
    LIVE_REREAD("live.reread"),
}

/** Something diagnostics are told about as an event of its own. */
enum class BackupEvent(val id: String) {
    UNEXPECTED_ERROR("backup.unexpected_error"),
    RECEIPT_MISMATCH("backup.receipt_mismatch"),
    UPLOAD_UNRESOLVED("backup.upload_unresolved"),
    LIVE_ATTACH_FAILED("live.attach_failed"),
    BYTE_CHECK_FAILED("backup.byte_check_failed"),
    MOTION_PARSE_FAILED("motion.parse_failed"),
}

/** A decision worth a breadcrumb: retries, backoff and healing. */
enum class BackupBreadcrumb(val id: String) {
    RETRY_SCHEDULED("retry_scheduled"),
    RETRY_NOW("retry_now"),
    HEAL_VERIFIED("heal_verified"),
    HEAL_AMBIGUOUS("heal_ambiguous"),
    HEAL_SEND_AGAIN("heal_send_again"),
    LIVE_ATTACH_RETRY("live_attach_retry"),
}

enum class DiagnosticSeverity { INFO, WARNING, ERROR, FATAL }

/**
 * The only attributes backup diagnostics carry. Nothing here can name a photo, a person or an account: no file names,
 * paths, record names, checksums, receipts, URLs, account ids, places or EXIF; [BackupTelemetry.sanitize] enforces it.
 */
enum class BackupAttribute(val id: String) {
    PHASE("phase"),
    OUTCOME("outcome"),
    ERROR("error"),
    MEDIA_KIND("media_kind"),
    MOTION_FORMAT("motion_format"),
    SIZE_BUCKET("size_bucket"),
    COUNT("count"),
    DURATION_MS("duration_ms"),
}

/** A traced step; [finish] once, with how it ended. */
fun interface BackupTrace {
    fun finish(outcome: String, attributes: Map<BackupAttribute, Any>)

    fun finish(outcome: String) = finish(outcome, emptyMap())
}

/**
 * Where backup reports spans, breadcrumbs and events. The app sends them to Sentry, only while the user allows
 * diagnostics; the engine never calls Sentry itself. Every attribute goes through [BackupTelemetry.sanitize] first.
 */
interface BackupDiagnostics {
    fun start(phase: BackupPhase, attributes: Map<BackupAttribute, Any> = emptyMap()): BackupTrace

    fun breadcrumb(crumb: BackupBreadcrumb, attributes: Map<BackupAttribute, Any> = emptyMap())

    fun capture(event: BackupEvent, severity: DiagnosticSeverity, attributes: Map<BackupAttribute, Any> = emptyMap())

    object None : BackupDiagnostics {
        override fun start(phase: BackupPhase, attributes: Map<BackupAttribute, Any>) = BackupTrace { _, _ -> }
        override fun breadcrumb(crumb: BackupBreadcrumb, attributes: Map<BackupAttribute, Any>) = Unit
        override fun capture(event: BackupEvent, severity: DiagnosticSeverity, attributes: Map<BackupAttribute, Any>) = Unit
    }
}

/** Values backup diagnostics may carry, and the helpers that make them. */
object BackupTelemetry {
    const val SUCCESS = "success"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    /**
     * A short identifier: a lower-case id (`motion_photo_v1`), an upper-case code (`BAD_REQUEST`, `HTTP_400`), an error
     * class name (`IOException`), or a size bucket. A path, URL, file name, record name, checksum or base64 never matches.
     */
    private val SAFE_TEXT = Regex(
        "[a-z][a-z0-9_]{0,23}|[A-Z][A-Z0-9_]{0,31}|[A-Z][A-Za-z0-9]{0,47}(Exception|Error)|<1MB|1-10MB|10-100MB|>100MB",
    )

    /** A coarse size: under 1 MB, 1 to 10, 10 to 100, or over 100 MB. */
    fun sizeBucket(bytes: Long): String = when {
        bytes < MB -> "<1MB"
        bytes < TEN_MB -> "1-10MB"
        bytes < HUNDRED_MB -> "10-100MB"
        else -> ">100MB"
    }

    /** The media kind of a MIME type: photo, video or other. */
    fun mediaKind(mimeType: String?): String = when {
        mimeType == null -> "other"
        mimeType.startsWith("image/") -> "photo"
        mimeType.startsWith("video/") -> "video"
        else -> "other"
    }

    /** An error as its class name, never its message, which can hold names or URLs. */
    fun errorClass(error: Throwable): String = error::class.java.simpleName.ifBlank { "Error" }

    /**
     * Only what may leave the phone: numbers, booleans, and short identifiers. Anything else, such as a value that looks
     * like a path, a URL, a file name or base64, is dropped rather than sent.
     */
    fun sanitize(attributes: Map<BackupAttribute, Any>): Map<String, Any> = buildMap {
        attributes.forEach { (key, value) ->
            when (value) {
                is Int, is Long -> put(key.id, value)
                is Boolean -> put(key.id, value)
                is String -> if (SAFE_TEXT.matches(value)) put(key.id, value)
                is Enum<*> -> if (SAFE_TEXT.matches(value.name)) put(key.id, value.name.lowercase())
                else -> Unit
            }
        }
    }

    /** Runs [block] inside a [phase] span, finished with its outcome; a failure ends it "failed" with its class. */
    inline fun <T> BackupDiagnostics.traced(
        phase: BackupPhase,
        attributes: Map<BackupAttribute, Any> = emptyMap(),
        block: () -> T,
    ): T {
        val trace = start(phase, attributes)
        val started = System.nanoTime()
        val result = runCatching(block)
        val error = result.exceptionOrNull()
        val took = BackupAttribute.DURATION_MS to elapsedMillis(started)
        when (error) {
            null -> trace.finish(SUCCESS, mapOf(took))
            is kotlinx.coroutines.CancellationException -> trace.finish(CANCELLED, mapOf(took))
            else -> trace.finish(FAILED, mapOf(took, BackupAttribute.ERROR to errorClass(error)))
        }
        return result.getOrThrow()
    }

    /** Milliseconds since [startedNanos], a [System.nanoTime] reading. */
    fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI

    private const val MB = 1_000_000L
    private const val TEN_MB = 10 * MB
    private const val HUNDRED_MB = 100 * MB
    private const val NANOS_PER_MILLI = 1_000_000L
}
