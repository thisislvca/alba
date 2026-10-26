package me.lvca.alba.app.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.collections.AlbumCard
import me.lvca.alba.app.ui.collections.CollectionPreview
import me.lvca.alba.app.ui.collections.icon
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
    LazyRow(modifier.testTag("smart-collection-chips"), contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(entries, key = { it.first }) { (id, smart) ->
            val title = smart?.localizedTitle() ?: stringResource(R.string.favorites)
            val count = summaries[id]?.count
            FilterChip(selected = false, onClick = { choose(id) },
                label = { Text(if (count != null && count > 0) "$title · $count" else title) },
                leadingIcon = { androidx.compose.material3.Icon(painterResource(smart?.icon() ?: R.drawable.icloud_favorites), null,
                    Modifier.size(18.dp)) },
                modifier = Modifier.testTag("smart-chip-$id"))
        }
    }
}

/** Albums that match the search, above the photos that do. */
@Composable
internal fun MatchingAlbums(albums: List<GalleryCollection>, summaries: Map<String, CollectionPreview>, retry: Int,
    preview: (String) -> Unit, open: (GalleryCollection) -> Unit) {
    Column(Modifier.padding(bottom = 16.dp).testTag("search-albums")) {
        Text(stringResource(R.string.search_matching_albums), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(albums, key = { it.id }) { album ->
                Column(Modifier.width(132.dp)) {
                    AlbumCard(album, summaries[album.id], retry, preview) { open(album) }
                }
            }
        }
    }
}
