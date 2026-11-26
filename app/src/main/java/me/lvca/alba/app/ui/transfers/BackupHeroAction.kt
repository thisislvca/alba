package me.lvca.alba.app.ui.transfers

import me.lvca.alba.app.ui.account.BackupStatus

/** The one thing the hero offers when backup is off or needs the user. */
internal enum class BackupHeroAction { TURN_ON, SIGN_IN, ALLOW_ACCESS, RETRY_STORAGE, REVIEW }

/** What fixes [this] status from the hero; turning backup on needs a verified account or the demo ([canTurnOn]). */
internal fun BackupStatus.heroAction(canTurnOn: Boolean): BackupHeroAction? = when (this) {
    BackupStatus.Off -> if (canTurnOn) BackupHeroAction.TURN_ON else BackupHeroAction.SIGN_IN
    BackupStatus.SignInNeeded -> BackupHeroAction.SIGN_IN
    BackupStatus.NeedsPermission -> BackupHeroAction.ALLOW_ACCESS
    BackupStatus.StorageFull -> BackupHeroAction.RETRY_STORAGE
    BackupStatus.NeedsConsent -> BackupHeroAction.REVIEW
    else -> null
}
