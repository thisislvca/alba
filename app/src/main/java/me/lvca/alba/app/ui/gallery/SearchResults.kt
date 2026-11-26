package me.lvca.alba.app.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.collections.AlbumCard
import me.lvca.alba.app.ui.collections.CollectionPreview
import me.lvca.alba.app.ui.collections.CollectionRow
import me.lvca.alba.app.ui.collections.icon
import me.lvca.alba.app.ui.countLabel
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.AlbaListGroup
import me.lvca.alba.app.ui.designsystem.AlbaListHeader
import me.lvca.alba.app.ui.designsystem.ICloudIcon
import me.lvca.alba.app.ui.localizedTitle
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.GalleryQuery
import me.lvca.alba.engine.model.GallerySearch
import me.lvca.alba.engine.model.SmartCollection

/** Albums whose name matches what was typed in Search: personal and shared albums, never folders or smart collections. */
internal fun matchingAlbums(collections: List<GalleryCollection>, text: String): List<GalleryCollection> {
    val search = GallerySearch.parse(text)
    return collections.filter { !it.isFolder && !it.id.startsWith("smart:") && !it.id.startsWith("device-folder:") &&
        search.matchesAlbum(it.name) }
}

/** Favorites and every media type iCloud indexes, as one scrollable row of chips with their counts. */
@Composable
internal fun SmartCollectionChips(summaries: Map<String, CollectionPreview>, choose: (String) -> Unit, modifier: Modifier = Modifier) {
    val entries = listOf(GalleryQuery.FAVORITES to null) + SmartCollection.entries.map { it.id to it }
    LazyRow(modifier.testTag("smart-collection-chips"), contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(entries, key = { it.first }) { (id, smart) ->
            val title = smart?.localizedTitle() ?: stringResource(R.string.favorites)
            val count = summaries[id]?.count
            FilterChip(selected = false, onClick = { choose(id) },
                label = { Text(if (count != null && count > 0) "$title · ${countLabel(count)}" else title) },
                leadingIcon = { Icon(painterResource(smart?.icon() ?: R.drawable.icloud_favorites), null,
                    Modifier.size(18.dp)) },
                modifier = Modifier.testTag("smart-chip-$id"))
        }
    }
}

/**
 * Search before anything is typed: a short introduction, the media types as chips, then capture date and Favorites as
 * a grouped list like settings. Its section headers are the grouped list's, as in Collections.
 */
@Composable
internal fun SearchLanding(summaries: Map<String, CollectionPreview>, bottomSpace: Dp, onDates: () -> Unit, choose: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 20.dp, bottom = 20.dp + bottomSpace)
        .testTag("search-landing")) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.find_a_moment), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.search_landing_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
        AlbaListHeader(stringResource(R.string.media_types), Modifier.padding(horizontal = 16.dp))
        SmartCollectionChips(summaries, choose)
        Spacer(Modifier.height(24.dp))
        AlbaListGroup(Modifier.padding(horizontal = 16.dp).testTag("search-landing-shortcuts")) {
            CollectionRow(stringResource(R.string.capture_date), stringResource(R.string.choose_a_date_range),
                icon = { Icon(AlbaIcons.DateRange, null) }, modifier = Modifier.testTag("search-capture-date"), onClick = onDates)
            CollectionRow(stringResource(R.string.favorites), stringResource(R.string.photos_you_ve_marked_with_a_heart),
                icon = { ICloudIcon(R.drawable.icloud_favorites) }, modifier = Modifier.testTag("search-favorites"),
                onClick = { choose(GalleryQuery.FAVORITES) })
        }
    }
}

/** Albums that match the search, above the photos that do. */
@Composable
internal fun MatchingAlbums(albums: List<GalleryCollection>, summaries: Map<String, CollectionPreview>, retry: Int,
    preview: (String) -> Unit, open: (GalleryCollection) -> Unit) {
    Column(Modifier.padding(bottom = 16.dp).testTag("search-albums")) {
        AlbaListHeader(stringResource(R.string.search_matching_albums))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(albums, key = { it.id }) { album ->
                Column(Modifier.width(132.dp)) {
                    AlbumCard(album, summaries[album.id], retry, preview) { open(album) }
                }
            }
        }
    }
}
