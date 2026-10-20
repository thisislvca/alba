package me.lvca.alba.engine.companion

import me.lvca.alba.engine.model.PhotoCompanionException
import me.lvca.alba.engine.model.StoredMessage
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** Like [require] or [check] for a user-facing condition: throws a final [PhotoCompanionException.Refused]. */
@OptIn(ExperimentalContracts::class)
internal inline fun refuseUnless(condition: Boolean, reason: StoredMessage) {
    contract { returns() implies condition }
    if (!condition) throw PhotoCompanionException.Refused(reason)
}

/** Like [requireNotNull] for a user-facing condition: throws a final [PhotoCompanionException.Refused]. */
@OptIn(ExperimentalContracts::class)
internal inline fun <T : Any> refuseIfNull(value: T?, reason: StoredMessage): T {
    contract { returns() implies (value != null) }
    return value ?: throw PhotoCompanionException.Refused(reason)
}

/** Like [check] for a compare-and-set: throws a final [PhotoCompanionException.FenceLost]. */
@OptIn(ExperimentalContracts::class)
internal inline fun fenceHolds(condition: Boolean, detail: () -> String = { "Check failed." }) {
    contract { returns() implies condition }
    if (!condition) throw PhotoCompanionException.FenceLost(detail())
}

/** Alba's own refusals are final; any other failure may be a transient Room, network or protocol fault. */
internal fun Throwable.isFinalRefusal(): Boolean = this is PhotoCompanionException
