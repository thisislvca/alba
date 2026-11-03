package me.lvca.alba.app.transfers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.messages.UserMessages
import me.lvca.alba.app.platform.BackupPreferences
import me.lvca.alba.engine.companion.GalleryBatchActions
import me.lvca.alba.engine.database.LibraryDao
import me.lvca.alba.engine.model.BackupDraftId
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.engine.model.DeviceFolder
import me.lvca.alba.engine.model.OfflineMediaStore
import me.lvca.alba.engine.model.PhotoCompanion
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Turning backup on: what it covers from the start, and what agreeing again keeps. */
@RunWith(AndroidJUnit4::class)
class LiveTransferModeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = BackupPreferences(context)
    private val photos = FakePhotos()

    @Before
    fun clearSettings() = preferences.update { it.copy(deviceFolders = emptySet()) }

    @Test
    fun backupTurnedOnAfreshStartsWithTheScreenshotsFolders() = runBlocking {
        mode().finishBackup(BackupDraftId("draft"), includeExisting = true)
        assertEquals(setOf(DeviceFolder.SCREENSHOTS), preferences.settings.value.deviceFolders)
        assertEquals(listOf(true), photos.finished)
    }

    @Test
    fun agreeingAgainWhileBackupIsOnKeepsTheFoldersAlreadyChosen() = runBlocking {
        photos.view.value = BackupView(enabled = true, scope = BackupScope.CAMERA_MEDIA)
        preferences.update { it.copy(deviceFolders = setOf("device-folder:external_primary:3")) }
        mode().finishBackup(BackupDraftId("draft"), includeExisting = false)
        assertEquals(setOf("device-folder:external_primary:3"), preferences.settings.value.deviceFolders)
    }

    private fun mode() = LiveTransferMode(photos, GalleryBatchActions(unexpected<LibraryDao>(), unexpected<OfflineMediaStore>(), photos, account = { "account" }),
        TransferScheduler {}, UserMessages(), preferences)

    private class FakePhotos : PhotoCompanion by unexpected() {
        val view = MutableStateFlow(BackupView(enabled = false))
        val finished = mutableListOf<Boolean>()

        override fun observeTransfers() = MutableStateFlow(emptyList<me.lvca.alba.engine.model.TransferView>())
        override fun observeBackup() = view

        override suspend fun finishBackupEnrollment(draft: BackupDraftId, includeExisting: Boolean): BackupView {
            finished += includeExisting
            return BackupView(enabled = true, scope = BackupScope.CAMERA_MEDIA, includesExisting = includeExisting)
        }
    }
}

private inline fun <reified T : Any> unexpected(): T = java.lang.reflect.Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ -> error("Unexpected call to ${method.name}") } as T
