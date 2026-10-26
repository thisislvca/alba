package me.lvca.alba.app.ui.shared

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import me.lvca.alba.app.sharing.SharedActivityState
import me.lvca.alba.app.ui.SharedActions
import me.lvca.alba.app.work.canNotify

/** New shared-album activity for badges and dots; empty where the app does not provide it, such as in previews. */
internal val LocalSharedActivity = staticCompositionLocalOf { SharedActivityState() }

/**
 * Whether Alba may post notifications, and how to ask. [request] asks Android in context, or opens the app's
 * notification settings once Android no longer shows its prompt; [openSettings] always opens them.
 */
internal class NotificationAccess(val granted: Boolean, val request: () -> Unit, val openSettings: () -> Unit)

/** Granted by default, so previews and tests that do not provide it show the switch as it is saved. */
internal val LocalNotificationAccess = compositionLocalOf { NotificationAccess(true, {}, {}) }

/**
 * Asks Android for notification permission in context. The question survives recreation: an answer that arrives
 * after a rotation still reaches [answered].
 */
@Composable
internal fun rememberNotificationAccess(answered: (Boolean) -> Unit): NotificationAccess {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(canNotify(context)) }
    var awaiting by rememberSaveable { mutableStateOf(false) }
    val answer by rememberUpdatedState(answered)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = canNotify(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = canNotify(context)
        if (awaiting) { awaiting = false; answer(granted) }
    }
    val openSettings = {
        runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        Unit
    }
    return NotificationAccess(granted, request = {
        if (needsPermission(context)) { awaiting = true; launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        else if (!granted) openSettings() else answer(true)
    }, openSettings = openSettings)
}

private fun needsPermission(context: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/**
 * What shared-album activity asks of the gallery: open the album, inbox or page a tapped notification is about, and,
 * the first time the Shared Albums page shows with notifications on, ask for permission in context.
 */
@Composable
internal fun SharedActivityEffects(sharedPageShown: Boolean, albumIds: Set<String>, actions: SharedActions,
    openAlbum: (String) -> Unit, openPage: (invitations: Boolean) -> Unit) {
    val activity = LocalSharedActivity.current
    val access = LocalNotificationAccess.current
    LaunchedEffect(activity.open, albumIds.isNotEmpty()) {
        val open = activity.open ?: return@LaunchedEffect
        val album = open.albumId
        // A cold start lists no albums yet; wait for them before falling back to the page.
        if (album != null && albumIds.isEmpty()) return@LaunchedEffect
        if (album != null && album in albumIds) openAlbum(album) else openPage(open.invitations)
        actions.consumeSharedOpen()
    }
    LaunchedEffect(sharedPageShown) {
        val wanted = activity.notificationsEnabled && !activity.notificationsAsked
        if (sharedPageShown && wanted && !access.granted) {
            actions.sharedNotificationsAsked()
            access.request()
        }
    }
}
