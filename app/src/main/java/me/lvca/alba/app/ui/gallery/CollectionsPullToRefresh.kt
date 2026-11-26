package me.lvca.alba.app.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * The Collections hub's pull to refresh. Its pages (Albums, Shared Albums, a folder) have their own controls at the
 * top, so only the hub itself pulls and shows the indicator; [refreshing] is the pull's own refresh, never one that
 * was already running.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionsPullToRefresh(
    enabled: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val shown = enabled && refreshing
    Box(Modifier.fillMaxSize().pullToRefresh(isRefreshing = shown, state = state, enabled = enabled, onRefresh = onRefresh)) {
        content()
        if (enabled) PullToRefreshDefaults.Indicator(state = state, isRefreshing = shown,
            modifier = Modifier.align(Alignment.TopCenter).testTag("collections-refresh-indicator"))
    }
}
