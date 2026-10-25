package me.lvca.alba.app.testing

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import me.lvca.alba.app.gallery.GalleryState
import me.lvca.alba.app.ui.AlbaActions
import me.lvca.alba.engine.model.GalleryQuery

/**
 * Test actions that change [state] as AlbaViewModel would for selection, the open photo and the open collection, so a
 * test rendered with `setAlbaContent(actions.state, ...)` can drive those flows from fixed state. Every other action
 * does what [base] does.
 */
internal open class GalleryStateActions(initial: GalleryState, base: AlbaActions = testAlbaActions()) : AlbaActions by base {
    val state: MutableState<GalleryState> = mutableStateOf(initial)

    private fun update(change: GalleryState.() -> GalleryState) {
        state.value = state.value.change()
    }

    override fun toggleSelection(mediaId: String) =
        update { copy(selection = if (mediaId in selection) selection - mediaId else selection + mediaId) }

    override fun selectItems(mediaIds: Set<String>, selected: Boolean) =
        update { copy(selection = if (selected) selection + mediaIds else selection - mediaIds) }

    override fun clearSelection() = update { copy(selection = emptySet()) }

    override fun openMedia(mediaId: String) = update { copy(selectedMedia = items.first { it.id == mediaId }) }

    override fun closeMedia() = update { copy(selectedMedia = null) }

    override fun changeQuery(query: GalleryQuery) = update { copy(query = query) }
}
