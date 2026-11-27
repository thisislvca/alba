package me.lvca.alba.app.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import me.lvca.alba.app.platform.AppPermissions
import me.lvca.alba.app.platform.ICloudLinksSource
import me.lvca.alba.app.platform.PlatformICloudLinks
import me.lvca.alba.app.platform.readAppPermissions

/** What Android allows Alba, read again each time Alba returns to the screen, such as from Settings. */
@Stable
internal class AppPermissionsState(private val read: () -> AppPermissions) {
    var value by mutableStateOf(read())
        private set

    fun refresh() {
        value = read()
    }
}

/** Where the iCloud links row reads its state: Android's, unless a test provides a fixed one. */
internal val LocalICloudLinks = compositionLocalOf<ICloudLinksSource> { PlatformICloudLinks }

@Composable
internal fun rememberAppPermissions(): AppPermissionsState {
    val context = LocalContext.current
    val links = LocalICloudLinks.current
    val state = remember(context, links) { AppPermissionsState { readAppPermissions(context, links = links) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.refresh() }
    return state
}
