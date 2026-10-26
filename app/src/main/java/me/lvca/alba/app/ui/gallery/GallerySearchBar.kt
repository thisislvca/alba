package me.lvca.alba.app.ui.gallery

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import me.lvca.alba.app.R

/**
 * The Search destination's field, for file names, titles, captions, albums, dates and photo types. It is drawn as a
 * Material search bar: a filled, fully rounded container with a leading search icon and a clear action, like the bar at the top of Google Photos' Search.
 * Submitting closes the keyboard so the results and the navigation come back into view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GallerySearchBar(text: String, onTextChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val colors = SearchBarDefaults.colors()
    Surface(shape = SearchBarDefaults.inputFieldShape, color = colors.containerColor, modifier = modifier.fillMaxWidth()) {
        SearchBarDefaults.InputField(
            query = text,
            onQueryChange = onTextChange,
            onSearch = { focus.clearFocus() },
            expanded = false,
            onExpandedChange = {},
            placeholder = { Text(stringResource(R.string.search_your_photos)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (text.isNotEmpty()) IconButton(onClick = { onTextChange("") }) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.clear_search))
                }
            },
            colors = colors.inputFieldColors,
            modifier = Modifier.fillMaxWidth().testTag("search-field"),
        )
    }
}
