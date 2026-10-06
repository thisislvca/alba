package dev.mela.engine.database

import androidx.room.TypeConverter

/** Names are persisted values. Keep them stable across app upgrades. */
enum class TransferState {
    READY, SENDING, OUTCOME_UNKNOWN, RECONCILING, READ_FAILURE, VERIFIED,
    AMBIGUOUS, NOT_OBSERVED, ABANDONED, NEEDS_SIGN_IN, UNSUPPORTED, NEEDS_ATTENTION,
}

class TransferStateConverters {
    @TypeConverter
    fun decode(value: String): TransferState =
        TransferState.entries.firstOrNull { it.name == value } ?: TransferState.NEEDS_ATTENTION

    @TypeConverter
    fun encode(value: TransferState): String = value.name
}
