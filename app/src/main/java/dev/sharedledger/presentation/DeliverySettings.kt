package dev.sharedledger.presentation

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sharedledger.BuildConfig
import dev.sharedledger.R
import dev.sharedledger.domain.BudgetType
import dev.sharedledger.push.PushBudget
import dev.sharedledger.push.PushSettings
import dev.sharedledger.updates.AppUpdates
import dev.sharedledger.updates.UpdatePhase
import dev.sharedledger.updates.UpdateRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun NotificationSettings(state: LedgerState, vm: LedgerViewModel) {
    val push = vm.push
    val session by vm.auth.session.collectAsStateWithLifecycle()
    val owner = session?.userId
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(push.permitted) }
    var refresh by remember { mutableIntStateOf(0) }
    var model by remember(owner) { mutableStateOf<PushSettings?>(null) }
    var busy by remember(owner) { mutableStateOf(false) }
    var error by remember(owner) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitted = push.permitted
        push.enqueue()
    }
    LifecycleResumeEffect(owner) {
        permitted = push.permitted
        refresh++
        onPauseOrDispose { }
    }
    LaunchedEffect(owner, refresh) {
        if (!push.configured || owner == null) return@LaunchedEffect
        busy = true
        error = false
        try {
            model = push.settings()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
        } finally {
            busy = false
        }
    }
    fun change(budget: String?, field: String, value: Boolean) {
        busy = true
        error = false
        scope.launch {
            try {
                val updated = push.settings(budget, field, value)
                if (vm.auth.session.value?.userId == owner) model = updated
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (vm.auth.session.value?.userId == owner) error = true
            } finally {
                busy = false
            }
        }
    }

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel {
                Text(stringResource(if (permitted) R.string.push_permission_on else R.string.push_permission_off))
                if (push.configured) TextButton(onClick = {
                    val activity = context as? Activity
                    if (!permitted && Build.VERSION.SDK_INT >= 33 && !push.permissionAsked &&
                        (activity == null || !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS))) {
                        push.recordPermissionRequest()
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                }) {
                    Text(stringResource(if (permitted) R.string.push_system_settings else R.string.push_enable_device))
                }
                when {
                    !push.configured -> Text(stringResource(R.string.push_unconfigured))
                    owner == null -> Text(stringResource(R.string.push_sign_in))
                    error -> {
                        Text(stringResource(R.string.push_settings_failed), color = MaterialTheme.colorScheme.error)
                        TextButton({ refresh++ }) { Text(stringResource(R.string.delivery_retry)) }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        model?.let { loaded ->
            item {
                Panel {
                    SettingSwitch(R.string.push_global_enabled, loaded.global.enabled, !busy) { change(null, "enabled", it) }
                    SettingSwitch(R.string.push_show_amounts, loaded.global.showAmounts, !busy) { change(null, "show_amounts", it) }
                    SettingSwitch(R.string.push_lock_details, loaded.global.lockScreenDetails, !busy) { change(null, "lock_screen_details", it) }
                }
            }
            items(state.budgets.filter { it.type == BudgetType.SHARED && it.principal == owner }, key = { it.id }) { budget ->
                val enabled = loaded.budgets[budget.id] ?: PushBudget()
                Panel {
                    Text(budget.name, style = MaterialTheme.typography.titleMedium)
                    SettingSwitch(R.string.push_expenses, enabled.expense, !busy) { change(budget.id, "expense", it) }
                    SettingSwitch(R.string.push_income, enabled.income, !busy) { change(budget.id, "income", it) }
                    SettingSwitch(R.string.push_edits, enabled.edits, !busy) { change(budget.id, "edits", it) }
                    SettingSwitch(R.string.push_deletions, enabled.deletions, !busy) { change(budget.id, "deletions", it) }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: Int, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(checked, change, enabled = enabled)
    }
}

@Composable
internal fun AppUpdateSettings(updates: AppUpdates) {
    val state by updates.state.collectAsStateWithLifecycle()
    val release = state.release
    val busy = state.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.INSTALL_REQUESTED)
    var notes by rememberSaveable { mutableStateOf(false) }
    var confirmDownload by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { updates.installerReturned() }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel {
                Text(stringResource(R.string.update_installed, BuildConfig.VERSION_NAME))
                if (release != null) Text(stringResource(R.string.update_latest, release.metadata.versionName))
                Text(stringResource(R.string.update_channel))
                Text(if (state.checkedAt == 0L) stringResource(R.string.update_no_check)
                else stringResource(R.string.update_last_check, DateFormat.getDateTimeInstance().format(Date(state.checkedAt))))
                Text(stringResource(if (updates.configured) state.phase.label() else R.string.update_unconfigured))
                SettingSwitch(R.string.update_automatic, state.automatic, updates.configured, updates::setAutomatic)
                OutlinedButton({ updates.checkForUpdate() }, enabled = updates.configured && !busy) { Text(stringResource(R.string.update_check)) }
            }
        }
        if (release != null) item {
            val candidate = release.metadata.versionCode > BuildConfig.VERSION_CODE && release.metadata.minSdk <= Build.VERSION.SDK_INT
            Panel {
                if (release.metadata.releaseNotes.isNotEmpty())
                    TextButton({ notes = true }) { Text(stringResource(R.string.update_notes)) }
                if (candidate && state.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)) {
                    Text(release.metadata.apkAsset)
                    val fraction = (state.downloaded.toFloat() / release.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                    LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth())
                    Text(stringResource(R.string.update_progress, Formatter.formatFileSize(context, state.downloaded),
                        Formatter.formatFileSize(context, release.size), (fraction * 100).toInt()))
                    TextButton(updates::cancelDownload) { Text(stringResource(R.string.delivery_cancel)) }
                } else if (candidate && !busy) {
                    if (state.phase in setOf(UpdatePhase.READY, UpdatePhase.INSTALL_FAILED)) {
                        if (!context.packageManager.canRequestPackageInstalls()) {
                            Text(stringResource(R.string.update_install_permission))
                            Button({ updates.openInstaller(installer::launch) }) { Text(stringResource(R.string.update_allow_install)) }
                        } else Button({ updates.openInstaller(installer::launch) }) { Text(stringResource(R.string.update_install)) }
                    } else {
                        Button({ confirmDownload = true }) {
                            Text(stringResource(if (state.phase in setOf(UpdatePhase.DOWNLOAD_FAILED, UpdatePhase.VERIFICATION_FAILED))
                                R.string.delivery_retry else R.string.update_download))
                        }
                    }
                }
            }
        }
    }
    if (release != null && notes) AlertDialog(onDismissRequest = { notes = false },
        title = { Text(stringResource(R.string.update_notes)) },
        text = { ReleaseNotes(release) },
        confirmButton = { TextButton({ notes = false }) { Text(stringResource(R.string.delivery_close)) } })
    if (release != null && confirmDownload) UpdatePrompt(release, { confirmDownload = false }) {
        confirmDownload = false
        updates.startDownload()
    }
}

@Composable
internal fun UpdatePrompt(release: UpdateRelease, dismiss: () -> Unit, download: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.update_prompt)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.update_installed, BuildConfig.VERSION_NAME))
                Text(stringResource(R.string.update_latest, release.metadata.versionName))
                ReleaseNotes(release)
            }
        },
        confirmButton = { Button(download) { Text(stringResource(R.string.update_now)) } },
        dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.update_later)) } })
}

@Composable
private fun ReleaseNotes(release: UpdateRelease) {
    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
        release.metadata.releaseNotes.forEach { Text("• $it") }
    }
}

private fun UpdatePhase.label() = when (this) {
    UpdatePhase.NOT_CHECKED -> R.string.update_no_check
    UpdatePhase.CHECKING -> R.string.update_checking
    UpdatePhase.CURRENT -> R.string.update_current
    UpdatePhase.NO_RELEASE -> R.string.update_no_release
    UpdatePhase.AVAILABLE -> R.string.update_available
    UpdatePhase.INCOMPATIBLE -> R.string.update_incompatible
    UpdatePhase.DOWNLOADING -> R.string.update_downloading
    UpdatePhase.VERIFYING -> R.string.update_verifying
    UpdatePhase.READY -> R.string.update_ready
    UpdatePhase.INSTALL_REQUESTED -> R.string.update_install_requested
    UpdatePhase.CHECK_FAILED -> R.string.update_check_failed
    UpdatePhase.RATE_LIMITED -> R.string.update_rate_limited
    UpdatePhase.DOWNLOAD_FAILED -> R.string.update_download_failed
    UpdatePhase.VERIFICATION_FAILED -> R.string.update_verification_failed
    UpdatePhase.INSTALL_FAILED -> R.string.update_install_failed
}
