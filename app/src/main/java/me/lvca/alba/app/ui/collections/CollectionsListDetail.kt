package me.lvca.alba.app.ui.collections

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldPaneScope
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion

/**
 * Collections on an expanded window: Material's list-detail layout, with the hub, a page or a folder in the list pane
 * on the left and the opened album, smart collection or shared album in the detail pane on the right. The detail pane
 * handles its own back (see GalleryHome), which closes the collection and leaves the list where it was.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun CollectionsListDetail(
    detailOpen: Boolean,
    list: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    detail: @Composable () -> Unit,
) {
    val directive = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())
    val value = calculateThreePaneScaffoldValue(directive.maxHorizontalPartitions, ListDetailPaneScaffoldDefaults.adaptStrategies(),
        ThreePaneScaffoldDestinationItem<Nothing>(if (detailOpen) ListDetailPaneScaffoldRole.Detail else ListDetailPaneScaffoldRole.List))
    val reduceMotion = reduceGalleryMotion()
    ListDetailPaneScaffold(
        directive = directive,
        value = value,
        listPane = { Pane("list-pane", reduceMotion, list) },
        detailPane = { Pane("detail-pane", reduceMotion, detail) },
        modifier = modifier,
    )
}

/** A pane with Material's pane motion, or none when animations are off. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun ThreePaneScaffoldPaneScope.Pane(tag: String, reduceMotion: Boolean, content: @Composable () -> Unit) {
    if (reduceMotion) AnimatedPane(Modifier.testTag(tag), EnterTransition.None, ExitTransition.None, snap()) { content() }
    else AnimatedPane(Modifier.testTag(tag)) { content() }
}

/** The detail pane before anything is opened: what it is for, quietly, in the middle. */
@Composable
internal fun CollectionDetailPlaceholder() {
    Box(Modifier.fillMaxSize().padding(AlbaSpacing.xxl).testTag("collection-detail-placeholder"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AlbaSpacing.md)) {
            Icon(painterResource(R.drawable.icloud_albums), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.collection_detail_placeholder), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
