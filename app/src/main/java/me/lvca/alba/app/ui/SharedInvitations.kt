package me.lvca.alba.app.ui

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
import me.lvca.alba.app.R
import me.lvca.alba.engine.model.SharedInvitation
import kotlinx.coroutines.launch
import me.lvca.alba.app.ui.theme.AlbaSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedInvitations(actions: InvitationActions, dismiss: () -> Unit, initialUrl: String = "", invalidLink: Boolean = false, demo: Boolean = false) {
    val invitations = rememberLoadable(initial = emptyList<SharedInvitation>()) { actions.pendingSharedInvitations() }
    val pending = invitations.value
    var resolved by remember { mutableStateOf<SharedInvitation?>(null) }
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var acting by remember { mutableStateOf(false) }
    val busy = acting || invitations.loading
    var confirmation by remember { mutableStateOf<Pair<SharedInvitation, Boolean>?>(null) }
    val scope = rememberCoroutineScope()
    fun act(change: suspend () -> Unit) {
        acting = true; invitations.failed = false
        scope.launch {
            try { invitations.failed = !attempt { change() } }
            finally { acting = false }
        }
    }
    AlbaBottomSheet(onDismissRequest = { if (!busy) dismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AlbaSheetBody(stringResource(R.string.shared_invitations), dismiss, subtitle = stringResource(R.string.shared_invitation_help),
            dismissEnabled = !busy, spacing = AlbaSpacing.lg) {
            if (demo) Text(stringResource(R.string.demo_shared_notice))
            if (invalidLink && url.isBlank()) Text(stringResource(R.string.shared_invalid_link), color = MaterialTheme.colorScheme.error)
            AlbaLoadState(busy, invitations.failed, stringResource(R.string.shared_action_failed))
            OutlinedTextField(url, { url = it; resolved = null }, label = { Text(stringResource(R.string.shared_invitation_link)) },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("shared-invitation-link"))
            OutlinedButton(onClick = {
                resolved = null
                act { resolved = actions.resolveSharedInvitation(url) }
            }, enabled = !busy && url.isNotBlank()) { Text(stringResource(R.string.shared_invitation_preview)) }
            (pending + listOfNotNull(resolved)).distinctBy { it.id }.forEach { invitation ->
                AlbaCard(contentPadding = PaddingValues(AlbaSpacing.lg)) {
                    Column {
                        Text(invitation.name, style = MaterialTheme.typography.titleMedium)
                        Row {
                            TextButton(onClick = { confirmation = invitation to true }, enabled = !busy) { Text(stringResource(R.string.shared_accept)) }
                            if (invitation.canDecline) TextButton(onClick = { confirmation = invitation to false }, enabled = !busy) { Text(stringResource(R.string.shared_decline)) }
                        }
                    }
                }
            }
            if (!busy && !invitations.failed && pending.isEmpty() && resolved == null) Text(stringResource(R.string.shared_no_invitations))
            TextButton(onClick = invitations::reload, enabled = !busy) { Text(stringResource(R.string.shared_reload)) }
        }
    }
    confirmation?.let { (invitation, accept) ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(stringResource(if(accept) R.string.shared_accept else R.string.shared_decline)) },
            text = { Column { Text(invitation.name); Text(stringResource(if(accept) R.string.shared_accept_notice else R.string.shared_decline_notice)) } },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                act { actions.respondSharedInvitation(invitation.id, accept); resolved = null; url = ""; invitations.reload() }
            }) { Text(stringResource(R.string.shared_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.shared_cancel)) } })
    }
}
