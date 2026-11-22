package dev.sharedledger.presentation

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
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
import androidx.compose.ui.unit.dp
import dev.sharedledger.data.*
import dev.sharedledger.domain.*
import dev.sharedledger.sync.Background
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun AdditionalSettingsLinks(open: (String) -> Unit) {
    SettingsLink("Recurring transactions", "Manage scheduled income and expenses", Icons.Outlined.EventRepeat, open)
    SettingsLink("Exchange rates", "Automatic updates and manual rates", Icons.Outlined.CurrencyExchange, open)
    SettingsLink("Cloud sync", "Sign-in, members and invitations", Icons.Outlined.Cloud, open)
    SettingsLink("Import and export", "Move or back up your financial records", Icons.Outlined.ImportExport, open)
}

@Composable
fun PreferenceSettingsLinks(open: (String) -> Unit) {
    SettingsLink("Language", "English, Russian or Serbian", Icons.Outlined.Language, open)
}

@Composable
fun AdvancedSettings(screen: String, state: LedgerState, vm: LedgerViewModel, onBudgetJoined: () -> Unit) {
    when (screen) {
        "Cloud sync" -> CloudSettings(state, vm, onBudgetJoined)
        "Recurring transactions" -> RecurringSettings(state, vm)
        "Currencies", "Exchange rates" -> CurrencySettings(state, vm)
        "Import and export" -> PortabilitySettings(state, vm)
        "Language" -> Column(Modifier.padding(18.dp)) {
            val languages = linkedMapOf("" to "Device language", "en" to "English", "ru" to "Русский", "sr-Latn" to "Srpski (latinica)")
            val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            Select("App language", current, languages.keys.toList(), { languages[it] ?: it }, Modifier.fillMaxWidth()) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it))
            }
        }
    }
}

@Composable
private fun RecurringSettings(state: LedgerState, vm: LedgerViewModel) {
    val budget = state.budget ?: return
    val allowed = budget.role != Role.VIEWER
    var editor by remember { mutableStateOf<Rule?>(null) }
    var remove by remember { mutableStateOf<Rule?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (allowed) item {
            Button({
                editor = Rule(id(), budget.id, EntryType.EXPENSE, "", "", "", budget.currency, Frequency.MONTHLY, 1, LocalDate.now().toString())
            }, Modifier.fillMaxWidth()) { Text(tr("Add recurring transaction")) }
        }
        if (state.ledger.rules.isEmpty()) item { Panel { EmptyMessage("No recurring transactions", "Schedule regular payments or income.", Icons.Outlined.EventRepeat) } }
        items(state.ledger.rules.sortedBy { it.nextDate() ?: LocalDate.MAX }, key = { it.id }) { rule ->
            val due = rule.nextDate()
            val category = state.ledger.categories.find { it.id == rule.categoryId }
            Panel(Modifier.fillMaxWidth()) {
                Text(rule.note.ifBlank { category?.name ?: rule.type.label() }, style = MaterialTheme.typography.titleMedium)
                RecurringAmount(rule, state.ledger.accounts.find { it.id == rule.accountId }, budget.currency, vm)
                Text(rule.frequency.label(), style = MaterialTheme.typography.bodySmall)
                Text(when {
                    !rule.active -> "Paused"
                    due == null -> "Completed"
                    !rule.automatic && due <= LocalDate.now() -> "Awaiting confirmation · ${due.shortDate()}"
                    else -> "Next · ${due.shortDate()}"
                })
                if (allowed) {
                    Row {
                        TextButton({ editor = rule }) { Text(tr("Edit")) }
                        TextButton({ vm.execute { vm.recurring.pause(rule); enqueueRecurring(context) } }) { Text(if (rule.active) "Pause" else "Resume") }
                        TextButton({ remove = rule }) { Text(tr("Delete")) }
                    }
                    if (rule.active && due != null && due <= LocalDate.now()) {
                        Button({ vm.execute("Occurrence recorded") { vm.recurring.confirm(rule) } }) { Text(tr("Record occurrence")) }
                    }
                }
            }
        }
    }
    editor?.let { rule ->
        val isNew = state.ledger.rules.none { it.id == rule.id }
        val accounts = state.ledger.accounts.filter { !it.archived || it.id == rule.accountId }
        val accountNames = accounts.associate { it.id to it.name }
        var type by remember { mutableStateOf(rule.type) }
        val categories = state.ledger.categories.filter { it.type == type && (!it.archived || it.id == rule.categoryId) }
        val categoryNames = categories.associate { it.id to it.name }
        var account by remember { mutableStateOf(rule.accountId.ifBlank { accounts.firstOrNull()?.id.orEmpty() }) }
        var category by remember { mutableStateOf(rule.categoryId) }
        var amount by remember { mutableStateOf(rule.amount) }
        var currency by remember { mutableStateOf(rule.currency) }
        var frequency by remember { mutableStateOf(rule.frequency) }
        var interval by remember { mutableStateOf(rule.interval.toString()) }
        var start by remember { mutableStateOf(LocalDate.parse(rule.start)) }
        var end by remember { mutableStateOf(rule.end?.let(LocalDate::parse)) }
        var automatic by remember { mutableStateOf(rule.automatic) }
        var remind by remember { mutableStateOf(rule.remind) }
        var note by remember { mutableStateOf(rule.note) }
        val members = state.ledger.members.filter { it.active || it.userId == rule.payer }.associate { it.userId to it.name }
        var payer by remember { mutableStateOf(rule.payer ?: vm.repository.principal.value.takeIf { budget.principal != null }) }
        EditDialog(if (isNew) "Add recurring transaction" else "Edit recurring transaction", { editor = null },
            parseMoney(amount)?.signum() == 1 && category in categoryNames && account in accountNames && interval.toIntOrNull() in 1..366 &&
                (end == null || end!! >= start), {
                if (remind && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                vm.execute("Rule saved", { editor = null }) {
                    vm.recurring.save(rule.copy(type = type, accountId = account, categoryId = category, amount = amount,
                        currency = currency, frequency = frequency, interval = interval.toInt(), start = start.toString(), end = end?.toString(),
                        automatic = automatic, remind = remind, note = note, payer = payer))
                    enqueueRecurring(context)
                }
            }) {
            if (isNew) Select("Type", type, listOf(EntryType.EXPENSE, EntryType.INCOME), { it.label() }, Modifier.fillMaxWidth()) { type = it; category = "" }
            Select("Account", account, accountNames.keys.toList(), { accountNames[it].orEmpty() }, Modifier.fillMaxWidth()) {
                account = it
                currency = accounts.first { wallet -> wallet.id == it }.currency
            }
            Select("Category", category, categoryNames.keys.toList(), { categoryNames[it] ?: "Choose category" }, Modifier.fillMaxWidth()) { category = it }
            Field(tr("Amount"), amount, { amount = it })
            Select("Currency", currency, currencyCodes, { it }, Modifier.fillMaxWidth()) { currency = it }
            if (rule.nextIndex == 0L) {
                Select("Frequency", frequency, Frequency.entries, { it.label() }, Modifier.fillMaxWidth()) { frequency = it }
                Field(if (frequency in listOf(Frequency.CUSTOM, Frequency.DAILY)) "Interval in days" else "Repeat every n periods", interval, { interval = it })
                DateButton("Start", start, Modifier.fillMaxWidth()) { start = it }
            }
            DateButton("End", end ?: start, Modifier.fillMaxWidth()) { end = it }
            if (end != null) TextButton({ end = null }) { Text(tr("Remove end date")) }
            if (members.isNotEmpty()) Select("Paid by", payer, members.keys.toList(), { members[it] ?: "Current member" }, Modifier.fillMaxWidth()) { payer = it }
            Field(tr("Note (optional)"), note, { note = it })
            ToggleRow("Record automatically", automatic) { automatic = it }
            ToggleRow("Notify when due", remind) { remind = it }
        }
    }
    remove?.let { rule -> Confirm("Delete recurring rule?", "Transactions already recorded will stay in your history.", { remove = null }, "Delete") {
        vm.execute("Rule deleted", { remove = null }) { vm.recurring.pause(rule, delete = true) }
    } }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, change)
    }
}

private fun enqueueRecurring(context: android.content.Context) {
    androidx.work.WorkManager.getInstance(context).enqueueUniqueWork("ledger-recurring-immediate",
        androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE, androidx.work.OneTimeWorkRequestBuilder<dev.sharedledger.sync.RecurringWorker>().build())
}

@Composable
private fun PortabilitySettings(state: LedgerState, vm: LedgerViewModel) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var csv by rememberSaveable { mutableStateOf(false) }
    var limitRange by rememberSaveable { mutableStateOf(false) }
    var scope by rememberSaveable { mutableStateOf<String?>(state.budget?.id) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var external by remember { mutableStateOf<CsvTable?>(null) }
    var clearLocal by remember { mutableStateOf(false) }
    val currencies = state.budgets.associate { it.id to it.name }
    var exportPlan by remember { mutableStateOf<Triple<List<String>, DateSpan, Boolean>?>(null) }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val plan = exportPlan
        if (uri != null && plan != null) vm.execute("Archive exported") {
            val archive = vm.portability.export(plan.first, plan.second)
            resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(vm.portability.encode(archive, false)) }
                ?: throw IllegalArgumentException("Choose a writable destination.")
        }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val plan = exportPlan
        if (uri != null && plan != null) vm.execute("CSV exported") {
            val archive = vm.portability.export(plan.first, plan.second)
            resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(vm.portability.encode(archive, true)) }
                ?: throw IllegalArgumentException("Choose a writable destination.")
        }
    }
    val session by vm.auth.session.collectAsState()
    val online by remember(vm.rateNetwork) { vm.rateNetwork.observe() }.collectAsState(vm.rateNetwork.available())
    val writable = state.budgets.filter { it.role != Role.VIEWER && (it.principal == null || it.principal == session?.userId) }
    var destination by remember { mutableStateOf<ImportDestination?>(ImportDestination.NewPersonal) }
    var isBackup by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<ImportReview?>(null) }
    var reviewVersion by remember { mutableIntStateOf(0) }
    var completed by remember { mutableStateOf<ImportReview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsState()

    fun showPreview(file: ImportPreview, backup: Boolean) {
        preview = file
        isBackup = backup
        external = null
        completed = null
        error = null
        review = null
        destination = if (file.archive.budgets.size > 1) null
            else writable.find { it.id == state.budget?.id }?.let { ImportDestination.Existing(it.id) } ?: ImportDestination.NewPersonal
    }

    val read = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            var decoded: ImportPreview? = null
            var parsed: CsvTable? = null
            vm.execute(failed = { error = it }, finished = {
                val backup = decoded
                if (backup != null) showPreview(backup, true) else {
                    external = parsed
                    preview = null
                    completed = null
                    error = null
                }
            }) {
                val bytes = resolver.openInputStream(uri)?.use { it.readNBytesCompat(24_000_001) }
                    ?: throw IllegalArgumentException("The file could not be opened.")
                require(bytes.size <= 24_000_000) { "Choose a file smaller than 24 MB." }
                val data = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                if (data.trimStart().startsWith("{")) decoded = vm.portability.decodeJson(data)
                else parsed = vm.portability.parseCsv(data)
            }
        }
    }
    val destinations = buildList {
        if (preview?.archive?.budgets?.size == 1) {
            writable.forEach { add(ImportDestination.Existing(it.id)) }
            add(ImportDestination.NewPersonal)
            if (online && vm.sync.configured) session?.let { add(ImportDestination.NewShared(it.userId)) }
        }
        if (isBackup) add(null)
    }
    val selected = destination
    val permitted = selected in destinations
    LaunchedEffect(preview, selected, permitted, reviewVersion) {
        review = null
        val file = preview ?: return@LaunchedEffect
        if (selected == null || !permitted) return@LaunchedEffect
        error = null
        try {
            review = withContext(Dispatchers.IO) { vm.portability.reviewMerge(file, selected) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            error = e.message ?: "The destination could not be reviewed."
        } catch (_: Exception) {
            error = "The destination could not be reviewed. Choose the file again."
        }
    }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Panel(Modifier.fillMaxWidth()) {
            Text(tr("Export"), style = MaterialTheme.typography.titleLarge)
            Select("Format", csv, listOf(false, true), { if (it) "CSV" else "JSON" }, Modifier.fillMaxWidth()) { csv = it }
            Select("Budget", scope, listOf(null) + currencies.keys, { currencies[it] ?: "All visible budgets" }, Modifier.fillMaxWidth()) { scope = it }
            ToggleRow("Use selected date range", limitRange) { limitRange = it }
            if (limitRange) PeriodControl(state.period, { vm.period.value = it }, true)
            Text(if (csv) "CSV exports transaction rows. JSON preserves accounts, amounts, categories and rules." else
                "JSON preserves the budget structure and rate metadata. Choose a private storage location.")
            Button({
                val ids = scope?.takeIf { it in currencies }?.let(::listOf) ?: state.budgets.map { it.id }
                exportPlan = Triple(ids, if (limitRange) state.period.span else DateSpan(), csv)
                if (csv) exportCsv.launch("sharedledger-transactions.csv") else exportJson.launch("sharedledger-archive.json")
            }, enabled = state.budgets.isNotEmpty()) { Text(tr("Export file")) }
        } }
        item { Panel(Modifier.fillMaxWidth()) {
            Text(tr("Import"), style = MaterialTheme.typography.titleLarge)
            Button({ read.launch(arrayOf("application/json", "text/csv", "text/plain", "application/octet-stream")) }, enabled = !busy) { Text(tr("Choose a file")) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            preview?.let { file ->
                Select("Destination", selected, destinations, { option ->
                    when (option) {
                        is ImportDestination.Existing -> writable.find { it.id == option.budgetId }?.let { budget ->
                            "${budget.name} · ${if (budget.type == BudgetType.SHARED) "Shared" else "Personal"}"
                        } ?: "Budget unavailable"
                        ImportDestination.NewPersonal -> "New personal budget"
                        is ImportDestination.NewShared -> "New shared budget"
                        null -> "Restore backup as local budgets"
                    }
                }, Modifier.fillMaxWidth()) { destination = it }
                if (!permitted) Text("This destination is unavailable. Choose a writable budget, or connect to create a shared budget.")
                if (file.archive.budgets.size == 1 && destinations.none { it is ImportDestination.NewShared })
                    Text("Sign in and connect to create a new shared budget.")
                if (selected != null && permitted && error != null)
                    TextButton({ reviewVersion++ }, enabled = !busy) { Text("Refresh import preview") }
                val prepared = review
                if (prepared != null) {
                    ImportSummary(prepared, completed = false)
                    Text("Existing transactions, accounts, and categories will be preserved. Imported data will be merged into the selected budget.")
                    if (prepared.budget.principal != null) Text("Cloud changes queue locally and sync when connected.")
                } else if (selected == null) {
                    Text("${file.count} transactions · ${file.archive.budgets.size} local budget(s)")
                    Text("Restore the original backup structure as local budgets.")
                    file.archive.budgets.firstNotNullOfOrNull { source ->
                        source.entries.firstOrNull()?.let { it to source.budget.currency }
                    }?.let { (entry, currency) -> ImportAmountPreview(entry, currency) }
                } else if (permitted && error == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Button({ confirming = true }, enabled = !busy && permitted &&
                    (selected == null || prepared != null)) { Text(tr("Review and import")) }
            }
            completed?.let { result ->
                Text("Import result", style = MaterialTheme.typography.titleMedium)
                ImportSummary(result, completed = true)
                if (result.budget.principal != null) {
                    val pending by remember(result.budget.id) { vm.repository.dao.pendingImports(result.budget.id) }
                        .collectAsState(initial = null)
                    val status by vm.sync.status.collectAsState()
                    val queue = pending
                    val visible = state.budgets.any { it.id == result.budget.id }
                    Text(when {
                        !visible -> "Reconnect to the budget account to verify cloud sync."
                        queue == null -> "Checking cloud sync…"
                        queue.isEmpty() -> "Cloud import synchronized."
                        queue.any { it.remoteVersion != null || it.failure != null } -> "Cloud sync needs attention. Review pending changes under Cloud sync."
                        else -> "${queue.size} imported changes pending cloud sync."
                    })
                    if (queue?.isNotEmpty() == true && status.text != "Up to date") Text(status.text)
                } else Text("Saved to the local budget.")
            }
        } }
        item { TextButton({ clearLocal = true }) { Text(tr("Delete local data"), color = MaterialTheme.colorScheme.error) } }
    }
    if (clearLocal) Confirm("Delete all local data?", "This permanently removes budgets stored on this device and the cached exchange rates.", { clearLocal = false }, "Delete local data") {
        vm.execute("Local data deleted", { clearLocal = false }) { vm.repository.clearLocalData() }
    }
    external?.let { file -> CsvDialog(file, state.preferences.defaultCurrency, { external = null }) { mapping, name, currency ->
        var decoded: ImportPreview? = null
        vm.execute(failed = { error = it }, finished = { showPreview(checkNotNull(decoded), false) }) {
            decoded = vm.portability.decodeCsv(file, mapping, name, currency)
        }
    } }
    if (confirming) Confirm(if (selected == null) "Restore this backup?" else "Merge into ${review?.budget?.name.orEmpty()}?",
        if (selected == null) "Restore this archive as local budgets." else
            "Existing transactions, accounts, and categories will be preserved. Imported data will be merged into the selected budget.",
        { confirming = false }, "Import") {
        confirming = false
        val file = preview
        val checked = review
        if (file != null && permitted) {
            var result: ImportReview? = null
            vm.execute(failed = { error = it }, finished = {
                preview = null
                completed = result
                vm.filters.value = Filters()
            }) {
                if (selected == null) {
                    val (inserted, skipped) = vm.portability.restore(file)
                    vm.notice("Restored $inserted transactions · $skipped duplicates skipped")
                } else if (checked != null) {
                    if (selected is ImportDestination.NewShared) {
                        require(vm.rateNetwork.available()) { "Connect to the internet to create a shared budget." }
                        vm.sync.verifySharedCreation(selected.userId)
                    }
                    val imported = vm.portability.applyMerge(file, selected, checked)
                    result = imported
                    if (imported.budget.principal != null) Background.enqueueSync(context)
                }
            }
        }
    }
}

@Composable
private fun ImportSummary(review: ImportReview, completed: Boolean) {
    Text(review.budget.name, style = MaterialTheme.typography.titleMedium)
    Text(if (completed) "${review.transactions} transactions imported" else "${review.transactions} transactions to import")
    if (!completed) review.sample?.let { ImportAmountPreview(it, review.budget.currency) }
    Text("Accounts · ${review.accountsCreated} ${if (completed) "created" else "new"} · ${review.accountsReused} reused")
    Text("Categories · ${review.categoriesCreated} ${if (completed) "created" else "new"} · ${review.categoriesReused} reused")
    Text(if (completed) "${review.duplicates} duplicates skipped" else "${review.duplicates} duplicates to skip")
    if (review.attention > 0) Text("${review.attention} records need attention and ${if (completed) "were skipped" else "will be skipped"}.")
    var expanded by remember(review) { mutableStateOf(false) }
    review.warnings.take(if (expanded) review.warnings.size else 4).forEach { warning ->
        Text(warning, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    if (review.warnings.size > 4) TextButton({ expanded = !expanded }) { Text(if (expanded) "Show fewer details" else "Show all details") }
    if (!completed && (review.accounts.isNotEmpty() || review.categories.isNotEmpty())) {
        var showMappings by remember(review) { mutableStateOf(false) }
        TextButton({ showMappings = !showMappings }) { Text(if (showMappings) "Hide mappings" else "Review account and category mappings") }
        if (showMappings) {
            Text("Accounts", style = MaterialTheme.typography.labelLarge)
            Text(review.accounts.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            Text("Categories", style = MaterialTheme.typography.labelLarge)
            Text(review.categories.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ImportAmountPreview(entry: Entry, currency: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Sample transaction", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Column(horizontalAlignment = Alignment.End) {
            TransactionAmountBlock(transactionAmounts(entry, currency), entry.type, currency)
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(maximum: Int): ByteArray {
    val result = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (result.size() < maximum) {
        val count = read(buffer, 0, minOf(buffer.size, maximum - result.size()))
        if (count < 0) break
        result.write(buffer, 0, count)
    }
    return result.toByteArray()
}

@Composable
private fun CsvDialog(table: CsvTable, defaultCurrency: String, dismiss: () -> Unit, apply: (CsvMapping, String, String) -> Unit) {
    fun column(name: String) = table.headers.find { it.equals(name, true) }
    var date by remember { mutableStateOf(column("date") ?: table.headers.first()) }
    var amount by remember { mutableStateOf(column("amount") ?: table.headers.last()) }
    var type by remember { mutableStateOf(column("type")) }
    var currency by remember { mutableStateOf(column("currency")) }
    var category by remember { mutableStateOf(column("category")) }
    var account by remember { mutableStateOf(column("account")) }
    var note by remember { mutableStateOf(column("note")) }
    var id by remember { mutableStateOf(column("id")) }
    var name by remember { mutableStateOf("CSV import") }
    var base by remember { mutableStateOf(defaultCurrency) }
    EditDialog("Map CSV columns", dismiss, name.isNotBlank() && date != amount, {
        apply(CsvMapping(date, amount, type, currency, category, account, note, id), name, base)
    }) {
        Text("${table.rows.size} rows. Dates must use YYYY-MM-DD.")
        val options = listOf(null) + table.headers
        val label: (String?) -> String = { it ?: "Unmapped" }
        Field(tr("Budget name"), name, { name = it })
        Select("Budget currency", base, currencyCodes, { it }, Modifier.fillMaxWidth()) { base = it }
        Select("Date", date, table.headers, { it }, Modifier.fillMaxWidth()) { date = it }
        Select("Amount", amount, table.headers, { it }, Modifier.fillMaxWidth()) { amount = it }
        Select("Type", type, options, label, Modifier.fillMaxWidth()) { type = it }
        Select("Currency", currency, options, label, Modifier.fillMaxWidth()) { currency = it }
        Select("Account", account, options, label, Modifier.fillMaxWidth()) { account = it }
        Select("Category", category, options, label, Modifier.fillMaxWidth()) { category = it }
        Select("Note", note, options, label, Modifier.fillMaxWidth()) { note = it }
        Select("Transaction ID", id, options, label, Modifier.fillMaxWidth()) { id = it }
    }
}
