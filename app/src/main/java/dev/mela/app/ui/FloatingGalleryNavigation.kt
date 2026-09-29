package dev.mela.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.mela.app.R

internal enum class GalleryTab(val title: Int) { LIBRARY(R.string.gallery_photos), COLLECTIONS(R.string.collections), SEARCH(R.string.search) }

/** Two library destinations share a capsule; search remains a separate, reachable control. */
@Composable
internal fun FloatingGalleryNavigation(tab: GalleryTab, navigate: (GalleryTab) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().widthIn(max = 480.dp).padding(horizontal = 12.dp)) {
        val stacked = maxWidth < 320.dp || LocalDensity.current.fontScale > 1.2f
        val light = MaterialTheme.colorScheme.background.luminance() > .5f
        val container = if (light) Color.White else Color(0xFF1C1C1E)
        val foreground = if (light) MaterialTheme.colorScheme.onSurface else Color.White
        val selection = if (light) MaterialTheme.colorScheme.surfaceContainerHighest else Color(0xFF49494C)
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = container, contentColor = foreground, shadowElevation = 3.dp,
                modifier = Modifier.testTag("gallery-destination-capsule")) {
                Row(Modifier.padding(4.dp).selectableGroup()) {
                    listOf(GalleryTab.LIBRARY, GalleryTab.COLLECTIONS).forEach { destination ->
                        val selectedColor by animateColorAsState(if (tab == destination) selection else Color.Transparent, label = "destination-selection")
                        val icon: @Composable () -> Unit = {
                            Icon(painterResource(if (destination == GalleryTab.LIBRARY) R.drawable.icloud_library else R.drawable.icloud_albums),
                                null, Modifier.size(20.dp))
                        }
                        val label: @Composable () -> Unit = { Text(stringResource(destination.title), style = MaterialTheme.typography.labelLarge) }
                        Box(Modifier.clip(CircleShape).background(selectedColor)
                            .selectable(tab == destination, role = Role.Tab, onClick = { navigate(destination) })
                            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp).testTag("tab-${destination.name}"), contentAlignment = Alignment.Center) {
                            if (stacked) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) { icon(); label() }
                            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { icon(); label() }
                        }
                    }
                }
            }
            Surface(shape = CircleShape, color = if (tab == GalleryTab.SEARCH) selection else container,
                contentColor = foreground, shadowElevation = 3.dp) {
                Box(Modifier.size(56.dp).clip(CircleShape)
                    .selectable(tab == GalleryTab.SEARCH, role = Role.Tab, onClick = { navigate(GalleryTab.SEARCH) })
                    .testTag("tab-SEARCH"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Search, stringResource(R.string.search), Modifier.size(24.dp))
                }
            }
        }
    }
}
