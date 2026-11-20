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
fun AdvancedSettings(screen: String, state: LedgerState, vm: LedgerViewModel) {
    when (screen) {
        "Cloud sync" -> CloudSettings(state, vm)
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
                Text(money(rule.amount.decimal(), rule.currency) + " · " + rule.frequency.label())
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
    var destination by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var external by remember { mutableStateOf<CsvTable?>(null) }
    var discard by remember { mutableStateOf(false) }
    var clearLocal by remember { mutableStateOf(false) }
    val currencies = state.budgets.associate { it.id to it.name }
    val local = state.budgets.filter { it.principal == null }.associate { it.id to it.name }
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
    val read = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.execute {
            val bytes = resolver.openInputStream(uri)?.use { it.readNBytesCompat(24_000_001) }
                ?: throw IllegalArgumentException("The file could not be opened.")
            require(bytes.size <= 24_000_000) { "Choose a file smaller than 24 MB." }
            val data = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            if (data.trimStart().startsWith("{")) {
                preview = vm.portability.decodeJson(data)
                external = null
            } else {
                external = vm.portability.parseCsv(data)
                preview = null
            }
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
            Button({ read.launch(arrayOf("application/json", "text/csv", "text/plain", "application/octet-stream")) }) { Text(tr("Choose a file")) }
            preview?.let { result ->
                Text("${result.count} valid transactions · ${result.archive.budgets.size} budget(s)")
                result.errors.take(8).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (result.errors.size > 8) Text("${result.errors.size - 8} more reported issues")
                if (result.archive.budgets.size == 1) Select("Destination", destination, listOf(null) + local.keys,
                    { local[it] ?: "Import as local budget" }, Modifier.fillMaxWidth()) { destination = it }
                Button({ discard = true }, enabled = result.count > 0 || result.errors.isEmpty()) { Text(tr("Review and import")) }
            }
        } }
        item { TextButton({ clearLocal = true }) { Text(tr("Delete local data"), color = MaterialTheme.colorScheme.error) } }
    }
    if (clearLocal) Confirm("Delete all local data?", "This permanently removes budgets stored on this device and the cached exchange rates.", { clearLocal = false }, "Delete local data") {
        vm.execute("Local data deleted", { clearLocal = false }) { vm.repository.clearLocalData() }
    }
    external?.let { file -> CsvDialog(file, state.preferences.defaultCurrency, { external = null }) { mapping, name, currency ->
        vm.execute(finished = { external = null }) { preview = vm.portability.decodeCsv(file, mapping, name, currency) }
    } }
    if (discard) Confirm("Import these records?", "Existing records are kept. Duplicate transactions are skipped.",
        { discard = false }, "Import") {
        val result = preview
        discard = false
        if (result != null) vm.execute(finished = { preview = null }) {
            val chosen = destination.takeIf { result.archive.budgets.size == 1 }
            val (inserted, skipped) = vm.portability.apply(result, chosen)
            vm.notice("Imported $inserted transactions · $skipped duplicates skipped")
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
