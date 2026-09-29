package dev.mela.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mela.app.R
import dev.mela.engine.model.SharedInvitation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedInvitations(actions: LibraryActions, dismiss: () -> Unit, initialUrl: String = "", invalidLink: Boolean = false) {
    var pending by remember { mutableStateOf<List<SharedInvitation>>(emptyList()) }
    var resolved by remember { mutableStateOf<SharedInvitation?>(null) }
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var confirmation by remember { mutableStateOf<Pair<SharedInvitation, Boolean>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(revision) {
        busy = true; error = false
        try { pending = actions.pendingSharedInvitations() }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { error = true }
        finally { busy = false }
    }
    MelaBottomSheet(onDismissRequest = { if (!busy) dismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MelaSheetHeading(stringResource(R.string.shared_invitations), stringResource(R.string.shared_invitation_help), dismiss, !busy)
            if (invalidLink && url.isBlank()) Text(stringResource(R.string.shared_invalid_link), color = MaterialTheme.colorScheme.error)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error) Text(stringResource(R.string.shared_action_failed), color = MaterialTheme.colorScheme.error)
            OutlinedTextField(url, { url = it; resolved = null }, label = { Text(stringResource(R.string.shared_invitation_link)) },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("shared-invitation-link"))
            OutlinedButton(onClick = {
                busy = true; error = false; resolved = null
                scope.launch {
                    try { resolved = actions.resolveSharedInvitation(url) }
                    catch(e: CancellationException) { throw e }
                    catch(_: Exception) { error = true }
                    finally { busy = false }
                }
            }, enabled = !busy && url.isNotBlank()) { Text(stringResource(R.string.shared_invitation_preview)) }
            (pending + listOfNotNull(resolved)).distinctBy { it.id }.forEach { invitation ->
                Surface(shape = MaterialTheme.shapes.large, color = melaGroupColor()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(invitation.name, style = MaterialTheme.typography.titleMedium)
                        Row {
                            TextButton(onClick = { confirmation = invitation to true }, enabled = !busy) { Text(stringResource(R.string.shared_accept)) }
                            if (invitation.canDecline) TextButton(onClick = { confirmation = invitation to false }, enabled = !busy) { Text(stringResource(R.string.shared_decline)) }
                        }
                    }
                }
            }
            if (!busy && !error && pending.isEmpty() && resolved == null) Text(stringResource(R.string.shared_no_invitations))
            TextButton(onClick = { revision++ }, enabled = !busy) { Text(stringResource(R.string.shared_reload)) }
        }
    }
    confirmation?.let { (invitation, accept) ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(stringResource(if(accept) R.string.shared_accept else R.string.shared_decline)) },
            text = { Column { Text(invitation.name); Text(stringResource(if(accept) R.string.shared_accept_notice else R.string.shared_decline_notice)) } },
            confirmButton = { TextButton(onClick = {
                confirmation = null; busy = true; error = false
                scope.launch {
                    try { actions.respondSharedInvitation(invitation.id, accept); resolved = null; url = ""; revision++ }
                    catch(e: CancellationException) { throw e }
                    catch(_: Exception) { error = true }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.shared_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.shared_cancel)) } })
    }
}
