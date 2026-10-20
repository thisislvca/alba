package me.lvca.alba.engine.model

/**
 * A failure the photo companion decided on itself, as opposed to a Room, network or protocol fault.
 * Retrying or resuming the same operation cannot fix it, so recovery treats it as final.
 */
sealed class PhotoCompanionException(message: String) : IllegalStateException(message) {
    /** A user-facing refusal; the exception message is the [reason]'s stored code. */
    class Refused(val reason: StoredMessage) : PhotoCompanionException(reason.code)

    /** Durable state moved under the operation, so its compare-and-set no longer applies. */
    class FenceLost(detail: String) : PhotoCompanionException(detail)
}
