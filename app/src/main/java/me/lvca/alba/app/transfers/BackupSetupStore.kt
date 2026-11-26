package me.lvca.alba.app.transfers

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.lvca.alba.engine.model.BackupDraftId
import me.lvca.alba.engine.model.BackupLibrarySummary

/**
 * The automatic backup setup in progress, kept in saved state so the disclosure, a permission request the UI has not
 * launched yet, and a pending permission answer all survive process death.
 */
internal class BackupSetupStore(private val savedState: SavedStateHandle) {
    private val current = MutableStateFlow(savedState.get<String>(DRAFT)?.let {
        BackupSetup(BackupDraftId(it), savedState[DESTINATION] ?: "", savedState[AWAITING_PERMISSIONS] ?: false,
            savedState[INCLUDE_EXISTING] ?: true,
            savedState.get<Int>(LIBRARY_ITEMS)?.let { items -> BackupLibrarySummary(items, savedState[LIBRARY_BYTES] ?: 0L) })
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
        savedState[INCLUDE_EXISTING] = value.includeExisting
        if (value.library == null) {
            savedState.remove<Int>(LIBRARY_ITEMS)
            savedState.remove<Long>(LIBRARY_BYTES)
        } else {
            savedState[LIBRARY_ITEMS] = value.library.items
            savedState[LIBRARY_BYTES] = value.library.bytes
        }
        current.value = value
    }

    /** The count of what "Back up all" covers arrived; it is shown only if [draftId]'s setup is still the one open. */
    fun attachLibrary(draftId: BackupDraftId, library: BackupLibrarySummary) {
        current.value?.takeIf { it.draftId == draftId }?.let { save(it.copy(library = library)) }
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
        savedState.remove<Boolean>(INCLUDE_EXISTING)
        savedState.remove<Int>(LIBRARY_ITEMS)
        savedState.remove<Long>(LIBRARY_BYTES)
        savedState.remove<ArrayList<String>>(UNLAUNCHED_PERMISSIONS)
        current.value = null
    }

    private companion object {
        const val DRAFT = "backupDraft"
        const val DESTINATION = "backupDestination"
        const val AWAITING_PERMISSIONS = "backupAwaitingPermissions"
        const val INCLUDE_EXISTING = "backupIncludeExisting"
        const val UNLAUNCHED_PERMISSIONS = "backupUnlaunchedPermissions"
        const val LIBRARY_ITEMS = "backupLibraryItems"
        const val LIBRARY_BYTES = "backupLibraryBytes"
    }
}
