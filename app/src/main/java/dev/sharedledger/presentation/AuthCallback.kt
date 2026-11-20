package dev.sharedledger.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.sharedledger.sync.AuthLink
import dev.sharedledger.sync.EmailPurpose

sealed interface AuthCallback {
    data class Checking(val purpose: EmailPurpose) : AuthCallback
    data class Password(val link: AuthLink.Code, val saving: Boolean = false, val error: String? = null) : AuthCallback
    data class Complete(val purpose: EmailPurpose, val message: String) : AuthCallback
    data class Failed(val purpose: EmailPurpose, val message: String, val retry: AuthLink.Code? = null) : AuthCallback
}

@Composable
fun AuthCallbackDialog(state: AuthCallback, vm: LedgerViewModel, openCloud: () -> Unit) {
    fun continueToCloud() {
        vm.dismissAuthCallback()
        openCloud()
    }
    when (state) {
        is AuthCallback.Checking -> AlertDialog(
            onDismissRequest = {},
            title = { Text(tr(state.purpose.title())) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(tr("Verifying email link…"))
                }
            },
            confirmButton = {}
        )
        is AuthCallback.Password -> {
            // Do not put passwords into saved instance state.
            var password by remember(state.link) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { if (!state.saving) vm.dismissAuthCallback() },
                title = { Text(tr("Reset password")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(password, { password = it }, label = { Text(tr("New password")) },
                            enabled = !state.saving, singleLine = true, visualTransformation = PasswordVisualTransformation())
                        state.error?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton({ vm.updateRecoveredPassword(state.link, password) }, enabled = !state.saving && password.length in 8..128) {
                        Text(tr(if (state.saving) "Updating password…" else "Update password"))
                    }
                },
                dismissButton = { TextButton(vm::dismissAuthCallback, enabled = !state.saving) { Text(tr("Cancel")) } }
            )
        }
        is AuthCallback.Complete -> AlertDialog(
            onDismissRequest = vm::dismissAuthCallback,
            title = { Text(tr(if (state.purpose == EmailPurpose.CONFIRMATION) "Email verified" else "Password updated")) },
            text = { Text(tr(state.message)) },
            confirmButton = { TextButton(::continueToCloud) { Text(tr("Continue")) } }
        )
        is AuthCallback.Failed -> AlertDialog(
            onDismissRequest = vm::dismissAuthCallback, title = { Text(tr(state.purpose.title())) },
            text = { Text(tr(state.message)) },
            confirmButton = {
                val retry = state.retry
                if (retry != null) TextButton({ vm.retryAuthCallback(retry) }) { Text(tr("Try again")) }
                else TextButton(::continueToCloud) { Text(tr("Open Cloud sync")) }
            },
            dismissButton = { TextButton(vm::dismissAuthCallback) { Text(tr("Close")) } }
        )
    }
}

private fun EmailPurpose.title(): String = if (this == EmailPurpose.CONFIRMATION) "Verify email" else "Reset password"
