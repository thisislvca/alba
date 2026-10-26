package me.lvca.alba.app.work

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.sharing.SharedActivityStore
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedAlbumInfo
import me.lvca.alba.engine.model.SharedAlbumRole
import me.lvca.alba.engine.model.SharedInvitation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class SharedActivityNotificationsTest {
    private val app = ApplicationProvider.getApplicationContext<AlbaApplication>()
    private val album = GalleryCollection("shared:test:shared:o:family", "Family",
        shared = SharedAlbumInfo(SharedAlbumGeneration.MODERN, SharedAlbumRole.VIEWER))
    private var media = listOf(photo("a"), photo("b"))
    private var invitations = emptyList<SharedInvitation>()
    private val repository = object : GalleryRepository by app.graph.galleryRepository {
        override fun observeCollections(): Flow<List<GalleryCollection>> = flowOf(listOf(album))
        override fun observeGallery(): Flow<List<GalleryMedia>> = flowOf(media)
        override suspend fun pendingSharedInvitations() = invitations
    }

    private fun photo(id: String) = GalleryMedia("${album.id}:$id", "$id.jpg", 1, 1, 1, MediaOrigin.ICLOUD, MediaAvailability.CLOUD_ONLY,
        null, null, 0, 0, collectionIds = setOf(album.id), addedAtEpochMillis = id.hashCode().toLong())
    private fun store() = SharedActivityStore(app.getSharedPreferences("shared-activity-test-${System.nanoTime()}", Context.MODE_PRIVATE))
    private fun posted() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    @Test fun announcesNewPhotosAndInvitationsOnceAndNothingOnTheFirstLook() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = store()
        announce(app, store, repository)
        assertTrue(posted().isEmpty())

        media = media + photo("c")
        invitations = listOf(SharedInvitation("invitation:test:legacy:x", "Cousins", SharedAlbumGeneration.LEGACY, true))
        announce(app, store, repository)
        val titles = posted().map { it.extras.getString("android.title") }
        assertTrue(titles.any { it?.contains("Family") == true })
        assertTrue(titles.any { it?.contains("invitation") == true })

        val count = posted().size
        announce(app, store, repository)
        assertEquals(count, posted().size)
    }

    @Test fun nothingIsPostedWithoutPermissionOrWhenTurnedOff() = runBlocking {
        val store = store()
        announce(app, store, repository)
        media = media + photo("d")
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        announce(app, store, repository)
        assertTrue(posted().isEmpty())

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        store.update { it.copy(notificationsEnabled = false) }
        announce(app, store, repository)
        assertTrue(posted().isEmpty())
    }

    @Test fun newSharedAlbumLinksOpenAlbaWithOrWithoutATrailingSlash() {
        for (link in listOf("https://www.icloud.com/photos#/sharedalbums/sc,Token1", "https://www.icloud.com/photos/#sc,Token2",
            "https://photos.icloud.com/shared/album/Token3")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app.packageName)
            val matches = app.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }
            assertTrue(link, MainActivity::class.java.name in matches)
        }
    }
}
