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
