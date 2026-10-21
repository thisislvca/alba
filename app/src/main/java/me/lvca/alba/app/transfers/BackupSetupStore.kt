package me.lvca.alba.app.transfers

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.lvca.alba.engine.model.BackupDraftId

/**
 * The automatic backup setup in progress, kept in saved state so the disclosure, a permission request the UI has not
 * launched yet, and a pending permission answer all survive process death.
 */
internal class BackupSetupStore(private val savedState: SavedStateHandle) {
    private val current = MutableStateFlow(savedState.get<String>(DRAFT)?.let {
        BackupSetup(BackupDraftId(it), savedState[DESTINATION] ?: "", savedState[AWAITING_PERMISSIONS] ?: false)
    })
    val setup: StateFlow<BackupSetup?> = current.asStateFlow()

    /**
     * Permissions the UI was asked to request but has not launched yet. A request the UI already launched is not
     * here: Android delivers its answer to the recreated activity, so launching it again would ask twice.
     */
    val unlaunchedPermissions: List<String>?
        get() = savedState.get<ArrayList<String>>(UNLAUNCHED_PERMISSIONS)?.takeIf { current.value?.awaitingPermissions == true }

    fun save(value: BackupSetup) {
        savedState[DRAFT] = value.draftId.value
        savedState[DESTINATION] = value.destinationLabel
        savedState[AWAITING_PERMISSIONS] = value.awaitingPermissions
        current.value = value
    }

    /** The user accepted the disclosure for [value]; [permissions] must now be requested. */
    fun awaitPermissions(value: BackupSetup, permissions: List<String>) {
        savedState[UNLAUNCHED_PERMISSIONS] = ArrayList(permissions)
        save(value.copy(awaitingPermissions = true))
    }

    /** The UI launched the permission request; its answer arrives through the activity result. */
    fun permissionsLaunched() {
        savedState.remove<ArrayList<String>>(UNLAUNCHED_PERMISSIONS)
    }

    fun clear() {
        savedState.remove<String>(DRAFT)
        savedState.remove<String>(DESTINATION)
        savedState.remove<Boolean>(AWAITING_PERMISSIONS)
        savedState.remove<ArrayList<String>>(UNLAUNCHED_PERMISSIONS)
        current.value = null
    }

    private companion object {
        const val DRAFT = "backupDraft"
        const val DESTINATION = "backupDestination"
        const val AWAITING_PERMISSIONS = "backupAwaitingPermissions"
        const val UNLAUNCHED_PERMISSIONS = "backupUnlaunchedPermissions"
    }
}
