package me.lvca.alba.engine.companion

import me.lvca.alba.engine.database.TransferState

/** A user selected the photo; it may upload on any network. */
internal const val AUTHORITY_MANUAL = "MANUAL"

/** Automatic backup discovered the photo; it uploads only under a current enrollment on an unmetered network. */
internal const val AUTHORITY_AUTOMATIC = "AUTOMATIC"

/** How long reconciliation looks for an uncertain upload before its outcome becomes final. */
internal const val RECONCILIATION_DEADLINE_MILLIS = 15 * 60 * 1_000L

/** An attempt in these states holds the write slot while iCloud is checked; it is never uploaded again. */
internal val RECONCILABLE_STATES = setOf(TransferState.OUTCOME_UNKNOWN, TransferState.RECONCILING, TransferState.READ_FAILURE)

/**
 * Uploads one account may have in flight at once. Each holds a write slot from arming until it is verified or resolved,
 * and its own journal row, so a crash with several in flight recovers each on its own.
 */
internal const val MAX_UPLOADS_IN_FLIGHT = 3

/** Why a transfer stopped for good, as [me.lvca.alba.engine.database.TransferEntity.failureReason] keeps it. */
internal object FailureReasons {
    /** iCloud received the file but would not register or process it; the same bytes would be refused again. */
    const val ICLOUD_REJECTED = "ICLOUD_REJECTED"

    /** The original is no longer on the phone. */
    const val FILE_GONE = "FILE_GONE"
}
