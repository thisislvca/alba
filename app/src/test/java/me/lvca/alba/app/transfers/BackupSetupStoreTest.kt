package me.lvca.alba.app.transfers

import androidx.lifecycle.SavedStateHandle
import me.lvca.alba.engine.model.BackupDraftId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSetupStoreTest {
    private val savedState = SavedStateHandle()
    private val accepted = BackupSetup(BackupDraftId("draft-1"), "iCloud Photos")
    private val permissions = listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO")

    /** A store reading the same saved state, as a ViewModel restored after process death would. */
    private fun restored() = BackupSetupStore(savedState)

    @Test fun anUnlaunchedPermissionRequestSurvivesProcessDeath() {
        BackupSetupStore(savedState).awaitPermissions(accepted, permissions)

        val store = restored()

        assertEquals(accepted.copy(awaitingPermissions = true), store.setup.value)
        assertEquals(permissions, store.unlaunchedPermissions)
    }

    @Test fun aLaunchedPermissionRequestIsNotAskedAgainAfterProcessDeath() {
        BackupSetupStore(savedState).apply {
            awaitPermissions(accepted, permissions)
            permissionsLaunched()
        }

        val store = restored()

        assertTrue(store.setup.value!!.awaitingPermissions)
        assertNull(store.unlaunchedPermissions)
    }

    @Test fun clearingTheSetupDropsAnUnlaunchedRequest() {
        BackupSetupStore(savedState).apply {
            awaitPermissions(accepted, permissions)
            clear()
        }

        val store = restored()

        assertNull(store.setup.value)
        assertNull(store.unlaunchedPermissions)
    }
}
