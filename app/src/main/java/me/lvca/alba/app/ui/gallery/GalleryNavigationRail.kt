package me.lvca.alba.app.ui.gallery

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.designsystem.theme.AlbaIconSize
import me.lvca.alba.app.ui.navigation.NavigationMotion
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion

/**
 * The gallery's navigation on medium and expanded windows, in place of the floating capsule: Photos and Collections as
 * rail destinations, and Search as the action in the rail's header, apart from them as the capsule keeps it. The tags
 * match the capsule's, so a test reaches a destination the same way at every width.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GalleryNavigationRail(tab: GalleryTab, navigate: (GalleryTab) -> Unit, modifier: Modifier = Modifier) {
    val reduceMotion = reduceGalleryMotion()
    NavigationRail(
        modifier = modifier.testTag("navigation-rail"),
        containerColor = MaterialTheme.colorScheme.surface,
        // Ignore visibility: the viewer hides the bars, and the rail must not jump as they return.
        windowInsets = WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.navigationBarsIgnoringVisibility)
            .union(WindowInsets.displayCutout).only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
        header = {
            val selected = tab == GalleryTab.SEARCH
            val container by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                if (reduceMotion) snap() else NavigationMotion.fastEffects(), label = "rail-search")
            Box(Modifier.padding(top = 8.dp).size(56.dp).clip(CircleShape).background(container)
                .selectable(selected, role = Role.Tab, onClick = { navigate(GalleryTab.SEARCH) })
                .testTag("tab-SEARCH"), contentAlignment = Alignment.Center) {
                Icon(GalleryTab.SEARCH.icon(), stringResource(R.string.search), Modifier.size(AlbaIconSize.standard),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
    ) {
        Spacer(Modifier.height(16.dp))
        listOf(GalleryTab.LIBRARY, GalleryTab.COLLECTIONS).forEach { destination ->
            val selected = tab == destination
            NavigationRailItem(selected = selected, onClick = { navigate(destination) },
                icon = { Icon(destination.icon(selected), null, Modifier.size(AlbaIconSize.standard)) },
                label = { Text(stringResource(destination.title)) },
                modifier = Modifier.testTag("tab-${destination.name}"))
        }
    }
}
