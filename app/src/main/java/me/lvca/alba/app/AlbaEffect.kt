package me.lvca.alba.app

import android.content.Intent
import android.content.IntentSender

/** A one-shot platform request that the UI performs once, even across a configuration change. */
sealed interface AlbaEffect {
    /** Open the system share sheet for prepared copies. */
    data class Share(val intent: Intent) : AlbaEffect

    /** Ask Android to confirm moving a verified phone original to its trash. */
    data class ConfirmVerifiedTrash(val request: IntentSender) : AlbaEffect

    /** Ask for the media permissions automatic backup needs before it can be enabled. */
    data class RequestBackupPermissions(val permissions: List<String>) : AlbaEffect
}
