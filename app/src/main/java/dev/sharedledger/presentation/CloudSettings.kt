package dev.sharedledger.presentation

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.sharedledger.data.*
import dev.sharedledger.domain.*
import dev.sharedledger.sync.Invitation
import dev.sharedledger.sync.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CloudSettings(state: LedgerState, vm: LedgerViewModel) {
    val session by vm.auth.session.collectAsState()
    val busy by vm.busy.collectAsState()
    val status by vm.sync.status.collectAsState()
    val changes by vm.repository.dao.pendingFlow().collectAsState(initial = emptyList())
    val scopedChanges = changes.filter { change -> state.budgets.any { it.id == change.budgetId && it.principal == session?.userId } }
    val pending = scopedChanges.filter { it.remoteVersion == null }
    val conflicts = scopedChanges.filter { it.remoteVersion != null }
    val budget = state.budget
    val context = LocalContext.current
    var signingUp by rememberSaveable { mutableStateOf(false) }
    var emailNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }
    var inviteRole by rememberSaveable { mutableStateOf(Role.EDITOR) }
    var token by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var promoting by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<Member?>(null) }
    var conflict by remember { mutableStateOf<PendingChange?>(null) }
    var invitations by remember(budget?.id) { mutableStateOf(emptyList<Invitation>()) }
    val ownerShared = budget != null && budget.principal == session?.userId && budget.type == BudgetType.SHARED && budget.role == Role.OWNER
    LaunchedEffect(budget?.id, ownerShared, status.lastSync) {
        if (ownerShared) {
            try { invitations = withContext(Dispatchers.IO) { vm.sync.invitations(budget!!.id) } }
            catch (_: dev.sharedledger.sync.CloudError) { }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!vm.sync.configured) {
            item { Panel(Modifier.fillMaxWidth()) {
                EmptyMessage(tr("Local mode"), tr("To enable cloud sync, configure a Supabase URL and publishable key when building the app."), Icons.Outlined.CloudOff)
            } }
        } else if (session == null) {
            item {
                Panel {
                    Text(tr(if (signingUp) "Create an account" else "Sign in"), style = MaterialTheme.typography.titleLarge)
                    emailNotice?.let { Text(tr(it), color = MaterialTheme.colorScheme.primary) }
                    if (signingUp) Field(tr("Display name"), name, { name = it })
                    Field(tr("Email"), email, { email = it; emailNotice = null })
                    OutlinedTextField(password, { password = it }, label = { Text(tr("Password")) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Button({
                        val createName = name.takeIf { signingUp }
                        var signedIn = false
                        vm.execute(finished = {
                            password = ""
                            if (createName != null && !signedIn) {
                                signingUp = false
                                emailNotice = "Confirmation email requested. Open the newest link on this device."
                            }
                        }) {
                            signedIn = vm.auth.signIn(email, password, createName)
                            if (signedIn) vm.sync.synchronize()
                        }
                    }, enabled = !busy && email.isNotBlank() && password.isNotBlank() && (!signingUp || name.isNotBlank()), modifier = Modifier.fillMaxWidth()) {
                        Text(tr(if (signingUp) "Create account" else "Sign in"))
                    }
                    TextButton({ signingUp = !signingUp; emailNotice = null }, enabled = !busy) {
                        Text(tr(if (signingUp) "Use existing account" else "Create account"))
                    }
                    if (!signingUp) {
                        TextButton({
                            vm.execute(finished = {
                                emailNotice = "If the account exists and is unconfirmed, open the newest email on this device."
                            }) { vm.auth.resendConfirmation(email) }
                        }, enabled = !busy && email.isNotBlank()) { Text(tr("Resend confirmation email")) }
                        TextButton({
                            vm.execute(finished = {
                                emailNotice = "Password reset requested. Open the email on this device."
                            }) { vm.auth.requestRecovery(email) }
                        }, enabled = !busy && email.isNotBlank()) { Text(tr("Reset password")) }
                    }
                }
            }
        } else {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text(session?.email.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Text(status.text)
                    Text("${pending.size} queued · ${conflicts.size} conflicts")
                    Row {
                        TextButton({ vm.execute { vm.sync.synchronize() } }, enabled = !status.running) { Text(tr("Sync now")) }
                        TextButton({ switching = true }) { Text(tr("Sign out")) }
                        TextButton({ deleting = true }) { Text(tr("Delete account")) }
                    }
                }
            }
            if (budget != null && budget.principal == null) item {
                Panel(Modifier.fillMaxWidth()) {
                    Text(budget.name, style = MaterialTheme.typography.titleMedium)
                    Text("Sync this personal budget to your signed-in devices.")
                    Button({ promoting = true }) { Text(tr("Enable sync for this budget")) }
                }
            }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text(tr("Join a shared budget"), style = MaterialTheme.typography.titleMedium)
                    Field(tr("Invitation code or link"), joinCode, { joinCode = it })
                    Button({ vm.execute("Budget joined", { joinCode = "" }) { vm.sync.join(joinCode) } }, enabled = joinCode.isNotBlank()) {
                        Text(tr("Join budget"))
                    }
                }
            }
            if (budget != null && budget.principal != null) item {
                Panel(Modifier.fillMaxWidth()) {
                    Text("${budget.name} members", style = MaterialTheme.typography.titleMedium)
                    state.ledger.members.filter { it.active }.forEach { member ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(member.name, Modifier.weight(1f))
                            if (ownerShared && member.role != Role.OWNER) {
                                Select("Role", member.role, listOf(Role.EDITOR, Role.VIEWER), { it.label() }, Modifier.width(108.dp)) {
                                    vm.execute("Role updated") { vm.sync.memberAccess(budget.id, member.userId, it.name) }
                                }
                                IconButton({ revoking = member }) { Icon(Icons.Outlined.PersonRemove, "Revoke ${member.name}'s access") }
                            } else Text(member.role.label())
                        }
                    }
                    if (ownerShared) {
                        Select("Invite as", inviteRole, listOf(Role.EDITOR, Role.VIEWER), { it.label() }, Modifier.fillMaxWidth()) { inviteRole = it }
                        Button({
                            vm.execute {
                                vm.sync.synchronize()
                                token = vm.sync.invite(budget.id, inviteRole)
                                invitations = vm.sync.invitations(budget.id)
                            }
                        }) { Text(tr("Generate invitation")) }
                        invitations.forEach { invitation ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${invitation.role.label()} · ${invitation.expires.take(10)}" +
                                    when { invitation.revoked -> " · Revoked"; invitation.used > 0 -> " · Used"; else -> "" }, Modifier.weight(1f))
                                if (!invitation.revoked && invitation.used == 0) TextButton({
                                    vm.execute {
                                        vm.sync.revokeInvitation(budget.id, invitation.id)
                                        invitations = vm.sync.invitations(budget.id)
                                    }
                                }) { Text(tr("Revoke")) }
                            }
                        }
                    }
                }
            }
            if (conflicts.isNotEmpty()) item { Text(tr("Conflicts"), style = MaterialTheme.typography.titleLarge) }
            items(conflicts, key = { "${it.kind}-${it.recordId}" }) { change ->
                Panel(Modifier.fillMaxWidth()) {
                    Text("${state.budgets.find { it.id == change.budgetId }?.name} · ${change.kind.replaceFirstChar { it.titlecase() }}")
                    TextButton({ conflict = change }) { Text(tr("Compare changes")) }
                }
            }
        }
    }
    token?.let { code ->
        AlertDialog(onDismissRequest = { token = null }, title = { Text(tr("Invitation ready")) }, text = {
            Text("This one-use code expires in 24 hours. $code")
        }, confirmButton = {
            TextButton({
                val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Join my SharedLedger budget\nsharedledger://invite/$code\nInvitation code: $code")
                context.startActivity(Intent.createChooser(share, "Share invitation"))
                token = null
            }) { Text(tr("Share")) }
        }, dismissButton = { TextButton({ token = null }) { Text(tr("Close")) } })
    }
    if (switching) Confirm("Sign out?", "Synced budgets will be hidden until you sign in again.", { switching = false }, "Sign out") {
        switching = false
        vm.execute { vm.auth.signOut() }
    }
    if (deleting) Confirm("Delete your account?", "This deletes your cloud account, every budget you own in the cloud, and those budgets' shared history.",
        { deleting = false }, "Delete account") {
        deleting = false
        vm.execute("Account deleted") { vm.auth.deleteAccount() }
    }
    if (promoting && budget != null) Confirm("Sync ${budget.name}?", "This uploads this budget and its financial records to your Supabase account.",
        { promoting = false }, "Enable sync") {
        promoting = false
        vm.execute { vm.repository.promote(budget.id); vm.sync.synchronize() }
    }
    revoking?.let { member ->
        Confirm("Revoke ${member.name}'s access?", "Their recorded transactions will remain in the budget.", { revoking = null }, "Revoke access") {
            revoking = null
            vm.execute { vm.sync.memberAccess(member.budgetId, member.userId, "REVOKE") }
        }
    }
    conflict?.let { change ->
        val role = state.budgets.find { it.id == change.budgetId }?.role
        AlertDialog(onDismissRequest = { conflict = null }, title = { Text("Choose which version to keep") },
            text = {
                Column(Modifier.heightIn(max = 350.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Your version"), style = MaterialTheme.typography.titleMedium)
                    Text(changeSummary(change.body))
                    Text(tr("Server version"), style = MaterialTheme.typography.titleMedium)
                    Text(change.remoteBody?.let(::changeSummary) ?: "Unavailable")
                }
            }, confirmButton = {
                TextButton({ vm.execute("Conflict resolved", { conflict = null }) { vm.sync.resolve(change, false) } },
                    enabled = change.remoteBody != null) { Text(tr("Use server version")) }
            }, dismissButton = {
                if (role == Role.OWNER || role == Role.EDITOR && change.kind in listOf("entry", "rule"))
                    TextButton({ vm.execute("Change queued", { conflict = null }) { vm.sync.resolve(change, true); vm.sync.synchronize() } }) { Text(tr("Keep mine")) }
            })
    }
}

private fun changeSummary(body: String): String {
    val objectBody = ledgerJson.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
    return listOf("name", "type", "date", "currency", "amount", "note", "opening", "frequency", "start", "active", "archived",
        "accountId", "categoryId", "payer", "deletedAt").mapNotNull { key ->
        objectBody.string(key)?.takeIf { it.isNotBlank() }?.let { value ->
            if (key == "deletedAt") "Deleted" else "$key · $value"
        }
    }.joinToString("\n").take(1400)
}

@Composable
fun IncomingInvitation(uri: android.net.Uri?, vm: LedgerViewModel, clear: () -> Unit) {
    if (uri == null || uri.scheme != "sharedledger") return
    val session by vm.auth.session.collectAsState()
    if (uri.host == "invite") {
        val code = uri.lastPathSegment ?: return
        AlertDialog(onDismissRequest = clear, title = { Text("Join a shared budget?") },
            text = { Text(if (session == null) "Sign in under Settings → Cloud sync, then open the invitation again." else "The invitation will add this budget to your account.") },
            confirmButton = {
                TextButton({
                    if (session != null) vm.execute("Budget joined", clear) { vm.sync.join(code) } else clear()
                }) { Text(if (session == null) "Close" else "Join budget") }
            }, dismissButton = { TextButton(clear) { Text(tr("Cancel")) } })
    }
}
