package me.lvca.alba.engine.source

import me.lvca.alba.engine.model.BackupNetwork
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.BackupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFoldersTest {
    @Test
    fun cameraFolderMatchesPhoneMakerVariantsOnAnyVolumeAndCase() {
        listOf("DCIM/Camera/", "dcim/camera/", "DCIM/100ANDRO/", "DCIM/100MEDIA/", "DCIM/OpenCamera/").forEach {
            assertTrue(it, BackupFolders.isCamera(it))
        }
        listOf("Pictures/Screenshots/", "DCIM/Camera/Raw/", "DCIM/", null).forEach { assertFalse("$it", BackupFolders.isCamera(it)) }
    }

    @Test
    fun chosenFoldersAreMatchedByVolumeAndBucket() {
        val screenshots = BackupFolders.folderId("external_primary", "-1313584517")
        val sdCard = BackupFolders.folderId("1234-5678", "42")
        assertEquals("external_primary" to "-1313584517", BackupFolders.parse(screenshots))
        assertNull(BackupFolders.parse("device-folder:missing"))

        val (selection, args) = BackupFolders.selection(setOf(screenshots, sdCard, "not-a-folder"))
        assertEquals(2, Regex("bucket_id = \\?").findAll(selection).count())
        assertEquals(BackupFolders.CAMERA_PATHS + listOf("external_primary", "-1313584517", "1234-5678", "42"), args)
    }

    @Test
    fun anOlderJpegConsentNeverWidensToVideosOrFolders() {
        assertEquals(listOf("image/jpeg", "image/jpg"), BackupFolders.mimeTypes(BackupScope.CAMERA_JPEGS))
        assertNull(BackupFolders.mimeTypes(BackupScope.CAMERA_MEDIA))
        assertTrue(BackupFolders.extraFolders(BackupScope.CAMERA_JPEGS, setOf("device-folder:a:b")).isEmpty())
        assertEquals(setOf("device-folder:a:b"), BackupFolders.extraFolders(BackupScope.CAMERA_MEDIA, setOf("device-folder:a:b")))
    }

    @Test
    fun mobileDataAndRoamingAreOffUntilTheUserAllowsThem() {
        val defaults = BackupSettings()
        assertTrue(defaults.allows(BackupNetwork.UNMETERED, video = true))
        assertFalse(defaults.allowsAny(BackupNetwork.METERED))
        assertFalse(defaults.allowsAny(BackupNetwork.OFFLINE))

        val photos = defaults.copy(photosOnMobileData = true)
        assertTrue(photos.allows(BackupNetwork.METERED, video = false))
        assertFalse(photos.allows(BackupNetwork.METERED, video = true))
        assertFalse("Roaming needs its own switch", photos.allows(BackupNetwork.ROAMING, video = false))
        assertTrue(photos.copy(whileRoaming = true).allows(BackupNetwork.ROAMING, video = false))
        assertFalse(photos.copy(paused = true).allows(BackupNetwork.UNMETERED, video = false))
    }
}
