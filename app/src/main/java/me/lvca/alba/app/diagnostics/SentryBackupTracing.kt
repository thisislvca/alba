package me.lvca.alba.app.diagnostics

import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SpanStatus
import io.sentry.protocol.Message
import me.lvca.alba.engine.model.BackupAttribute
import me.lvca.alba.engine.model.BackupBreadcrumb
import me.lvca.alba.engine.model.BackupDiagnostics
import me.lvca.alba.engine.model.BackupEvent
import me.lvca.alba.engine.model.BackupPhase
import me.lvca.alba.engine.model.BackupTelemetry
import me.lvca.alba.engine.model.BackupTrace
import me.lvca.alba.engine.model.DiagnosticSeverity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Backup's spans, breadcrumbs and events, sent to Sentry only while [active] (the user allows diagnostics and a DSN is
 * set). Every attribute passes [BackupTelemetry.sanitize] first, so only phases, outcomes, error classes or codes, media
 * kinds, motion format ids, size buckets, counts and durations leave the phone.
 */
internal class SentryBackupTracing(private val active: () -> Boolean) : BackupDiagnostics {
    override fun start(phase: BackupPhase, attributes: Map<BackupAttribute, Any>): BackupTrace {
        if (!active()) return BackupDiagnostics.None.start(phase, attributes)
        val operation = if (phase == BackupPhase.ROUND) DiagnosticOperation.BACKUP_ROUND else DiagnosticOperation.BACKUP_PHASE
        val transaction = Sentry.startTransaction(phase.id, "alba.${operation.wireName}")
        BackupTelemetry.sanitize(attributes).forEach(transaction::setData)
        val finished = AtomicBoolean(false)
        return BackupTrace { outcome, more ->
            if (!finished.compareAndSet(false, true)) return@BackupTrace
            BackupTelemetry.sanitize(more + (BackupAttribute.OUTCOME to outcome)).forEach(transaction::setData)
            transaction.finish(
                when (outcome) {
                    BackupTelemetry.SUCCESS -> SpanStatus.OK
                    BackupTelemetry.CANCELLED -> SpanStatus.CANCELLED
                    else -> SpanStatus.INTERNAL_ERROR
                },
            )
        }
    }

    override fun breadcrumb(crumb: BackupBreadcrumb, attributes: Map<BackupAttribute, Any>) {
        if (!active()) return
        Sentry.addBreadcrumb(
            Breadcrumb().apply {
                category = "alba.backup"
                message = crumb.id
                level = SentryLevel.INFO
                BackupTelemetry.sanitize(attributes).forEach(::setData)
            },
        )
    }

    override fun capture(event: BackupEvent, severity: DiagnosticSeverity, attributes: Map<BackupAttribute, Any>) {
        if (!active()) return
        Sentry.captureEvent(
            SentryEvent().apply {
                level = when (severity) {
                    DiagnosticSeverity.INFO -> SentryLevel.INFO
                    DiagnosticSeverity.WARNING -> SentryLevel.WARNING
                    DiagnosticSeverity.ERROR -> SentryLevel.ERROR
                    DiagnosticSeverity.FATAL -> SentryLevel.FATAL
                }
                message = Message().apply { formatted = "alba.${event.id}" }
                setTag("alba.operation", event.id)
                BackupTelemetry.sanitize(attributes).forEach { (key, value) -> setTag("alba.$key", value.toString()) }
            },
        )
    }
}
