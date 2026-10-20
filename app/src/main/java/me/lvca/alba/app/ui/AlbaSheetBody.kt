package me.lvca.alba.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.theme.AlbaSpacing
import kotlinx.coroutines.CancellationException

/**
 * The scrolling body of an [AlbaBottomSheet]: inset content under an [AlbaSheetHeading], ending clear of
 * the navigation bar. [dismissEnabled] disables the close button while a change is in flight.
 */
@Composable
internal fun AlbaSheetBody(
    title: String,
    dismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dismissEnabled: Boolean = true,
    spacing: Dp = AlbaSpacing.md,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AlbaSpacing.xl)
        .navigationBarsPadding().padding(bottom = AlbaSpacing.xxl), verticalArrangement = Arrangement.spacedBy(spacing)) {
        AlbaSheetHeading(title, subtitle, dismiss, dismissEnabled)
        content()
    }
}

/**
 * Progress while [loading]; when [failed], the failure and, if [onReload] is given, a reload button.
 * Emits its parts directly into the caller's column, so they take the column's spacing.
 */
@Composable
internal fun AlbaLoadState(
    loading: Boolean,
    failed: Boolean,
    failureMessage: String,
    onReload: (() -> Unit)? = null,
    reloadEnabled: Boolean = true,
) {
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (failed) {
        Text(failureMessage, color = MaterialTheme.colorScheme.error)
        if (onReload != null) OutlinedButton(onClick = onReload, enabled = reloadEnabled) { Text(stringResource(R.string.shared_reload)) }
    }
}

/** Content a composable fetches itself, with its loading and failure state. Create it with [rememberLoadable]. */
@Stable
internal class Loadable<T>(initial: T) {
    /** The last successfully loaded value; kept while a reload runs and after a failure. */
    var value by mutableStateOf(initial)
        private set
    /** True until the first load settles, and during every load after it. */
    var loading by mutableStateOf(true)
        private set
    /** Set when a load fails. Actions that change what was loaded may set it too, to offer a reload. */
    var failed by mutableStateOf(false)
    internal var revision by mutableIntStateOf(0)
        private set

    fun reload() { revision++ }

    internal suspend fun load(fetch: suspend () -> T) {
        val generation = revision
        loading = true
        failed = false
        try {
            failed = !attempt { value = fetch() }
        } finally {
            // A reload may already have started a newer load; leave its progress alone.
            if (revision == generation) loading = false
        }
    }
}

/**
 * Loads with [fetch] when first composed, again when [keys] change (with fresh state) and on [Loadable.reload].
 * Failures set [Loadable.failed]; cancellation propagates.
 */
@Composable
internal fun <T> rememberLoadable(vararg keys: Any?, initial: T, fetch: suspend () -> T): Loadable<T> {
    val state = remember(*keys) { Loadable(initial) }
    val currentFetch by rememberUpdatedState(fetch)
    LaunchedEffect(state, state.revision) { state.load { currentFetch() } }
    return state
}

/** Runs [block] and reports whether it completed. Cancellation is rethrown; any other failure returns false. */
internal inline fun attempt(block: () -> Unit): Boolean = try {
    block()
    true
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}

@AlbaPreviews
@Composable
private fun AlbaLoadStatePreview() = AlbaPreviewSurface {
    AlbaLoadState(loading = true, failed = true, failureMessage = "Could not load the latest changes.", onReload = {})
}
