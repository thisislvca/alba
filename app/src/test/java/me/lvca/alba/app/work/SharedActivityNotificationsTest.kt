package me.lvca.alba.app.work

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.intake.IncomingRequest
import me.lvca.alba.app.intake.SharedActivityLinks
import me.lvca.alba.app.sharing.SharedActivityStore
import me.lvca.alba.engine.model.SharedAlbumCount
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.SharedInvitation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class SharedActivityNotificationsTest {
    private val app = ApplicationProvider.getApplicationContext<AlbaApplication>()
    private val albumId = "shared:test:shared:o:family"
    private var count = 2
    private var invitations = emptyList<SharedInvitation>()

    private fun counts() = listOf(SharedAlbumCount(albumId, "Family", count, count * 10L))
    private fun store() = SharedActivityStore(app.getSharedPreferences("shared-activity-test-${System.nanoTime()}", Context.MODE_PRIVATE))
    private fun posted() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
    private suspend fun announce(store: SharedActivityStore, sameAccount: () -> Boolean = { true }) =
        announce(app, store, { counts() }, { invitations }, sameAccount)

    @Test fun announcesNewPhotosAndInvitationsOnceAndNothingOnTheFirstLook() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = store()
        announce(store)
        assertTrue(posted().isEmpty())

        count = 3
        invitations = listOf(SharedInvitation("invitation:test:legacy:x", "Cousins", SharedAlbumGeneration.LEGACY, true))
        announce(store)
        val titles = posted().map { it.extras.getString(Notification.EXTRA_TITLE) }
        assertTrue(titles.any { it?.contains("Family") == true })
        assertTrue(titles.any { it?.contains("invitation") == true })

        val shown = posted().size
        announce(store)
        assertEquals(shown, posted().size)
    }

    @Test fun lockScreenShowsOnlyAGenericNoticeAndTapsOpenTheAlbumOrInbox() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = store()
        announce(store)
        count = 5
        invitations = listOf(SharedInvitation("invitation:test:legacy:x", "Cousins", SharedAlbumGeneration.LEGACY, true))
        announce(store)
        val notifications = posted().filter { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        assertEquals(2, notifications.size)
        notifications.forEach { notification ->
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertFalse(notification.publicVersion.extras.getString(Notification.EXTRA_TITLE)!!.contains("Family"))
        }
        val targets = notifications.map { SharedActivityLinks.from(shadowOf(it.contentIntent).savedIntent) }
        assertTrue(IncomingRequest.SharedActivity(albumId, false) in targets)
        assertTrue(IncomingRequest.SharedActivity(null, true) in targets)
    }

    @Test fun nothingIsPostedWithoutPermissionOrWhenTurnedOff() = runBlocking {
        val store = store()
        announce(store)
        count = 4
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        announce(store)
        assertTrue(posted().isEmpty())

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        store.update { it.copy(notificationsEnabled = false) }
        announce(store)
        assertTrue(posted().isEmpty())
    }

    @Test fun aCheckThatOutlivesSignOutWritesNothing() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = store()
        announce(store)
        count = 6
        var signedIn = true
        announce(app, store, { counts().also { store.clear(); signedIn = false } }, { invitations }) { signedIn }
        assertTrue(store.state.value.seen.isEmpty())
        assertTrue(posted().isEmpty())
    }

    private fun albaHandlers(link: String): List<String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app.packageName)
        return app.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name.substringAfterLast('.') }
    }

    @Test fun onlySharedAlbumPathsReachTheMainActivity() {
        assertEquals(listOf("MainActivity"), albaHandlers("https://photos.icloud.com/shared/album/Token3"))
        // iCloud Photos' web home reaches only the fragment-matching alias, which Android 15 limits to sc, fragments.
        // Robolectric does not apply fragment groups, so this checks paths; SharedAlbumLinkRoutingTest checks fragments.
        for (link in listOf("https://www.icloud.com/photos/", "https://www.icloud.com/photos", "https://www.icloud.com/photos#/sharedalbums/sc,T")) {
            assertEquals(link, listOf("SharedAlbumWebLink"), albaHandlers(link))
        }
        assertTrue(albaHandlers("https://www.icloud.com/mail/").isEmpty())
    }

    @Config(sdk = [34])
    @Test fun beforeAndroid15AlbaClaimsNoICloudPhotosWebLinks() {
        assertEquals(listOf("MainActivity"), albaHandlers("https://photos.icloud.com/shared/album/Token3"))
        assertTrue(albaHandlers("https://www.icloud.com/photos/").isEmpty())
        assertTrue(albaHandlers("https://www.icloud.com/photos#/sharedalbums/sc,T").isEmpty())
    }

    @Test fun notificationTapsOpenOnlySharedAlbumIdsAndTheInbox() {
        assertEquals(IncomingRequest.SharedActivity("shared:a:b:c:d", false),
            SharedActivityLinks.from(Intent().putExtra(SharedActivityLinks.ALBUM, "shared:a:b:c:d")))
        assertEquals(IncomingRequest.SharedActivity(null, true), SharedActivityLinks.from(Intent().putExtra(SharedActivityLinks.INVITATIONS, true)))
        assertNull(SharedActivityLinks.from(Intent().putExtra(SharedActivityLinks.ALBUM, "icloud:personal")))
    }
}
