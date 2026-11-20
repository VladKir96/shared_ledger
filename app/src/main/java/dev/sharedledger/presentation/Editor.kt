package dev.sharedledger.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sharedledger.data.*
import dev.sharedledger.domain.*
import java.time.LocalDate

@Composable
fun EditorScreen(state: LedgerState, vm: LedgerViewModel, type: EntryType, entry: Entry?, duplicate: Boolean, navigateBack: () -> Unit) {
    val budget = state.budget ?: return
    val editing = entry != null && !duplicate
    val accounts = remember(state.ledger.accounts, entry) {
        state.ledger.accounts.filter { !it.archived || it.id == entry?.accountId || it.id == entry?.destinationId }
    }
    val categories = remember(state.ledger.categories, state.ledger.entries, type, entry) {
        val recent = state.ledger.entries.filter { it.type == type }.take(50)
            .groupingBy { it.categoryId }.eachCount()
        state.ledger.categories.filter { it.type == type && (!it.archived || it.id == entry?.categoryId) }
            .sortedWith(compareByDescending<Category> { recent[it.id] ?: 0 }.thenBy { it.order })
    }
    var accountId by rememberSaveable { mutableStateOf(entry?.accountId ?: state.preferences.lastAccount
        ?.takeIf { last -> accounts.any { it.id == last } } ?: accounts.firstOrNull()?.id) }
    if (accounts.none { it.id == accountId }) accountId = accounts.firstOrNull()?.id
    val account = accounts.find { it.id == accountId }
    var categoryId by rememberSaveable { mutableStateOf(entry?.categoryId) }
    var dateText by rememberSaveable { mutableStateOf(if (duplicate) LocalDate.now().toString() else entry?.date ?: LocalDate.now().toString()) }
    val date = LocalDate.parse(dateText)
    var currencyOverride by rememberSaveable { mutableStateOf(entry?.currency) }
    val currency = if (type == EntryType.TRANSFER) account?.currency ?: budget.currency else currencyOverride ?: account?.currency ?: budget.currency
    var expression by rememberSaveable { mutableStateOf(entry?.amount.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(entry?.note.orEmpty()) }
    var payer by rememberSaveable { mutableStateOf(entry?.payer ?: vm.repository.principal.value.takeIf { budget.principal != null }) }
    var destinationId by rememberSaveable { mutableStateOf(entry?.destinationId) }
    val destinations = accounts.filter { it.id != accountId }
    val destination = destinations.find { it.id == destinationId }
    var received by rememberSaveable { mutableStateOf(entry?.destinationAmount.orEmpty()) }
    var showRates by rememberSaveable { mutableStateOf(false) }
    var manualAccount by rememberSaveable { mutableStateOf("") }
    var manualBudget by rememberSaveable { mutableStateOf("") }
    var delete by remember { mutableStateOf(false) }
    val result = remember(expression) { calculate(expression) }
    val liveResult = result ?: remember(expression) { calculate(expression.trimEnd('+', '−', '×', '÷')) }
    val allowed = budget.role != Role.VIEWER
    val busy by vm.busy.collectAsState()
    val canSave = allowed && !busy && result?.forCurrency(currency)?.signum() == 1 && account != null &&
        (if (type == EntryType.TRANSFER) destination != null else categoryId != null)

    fun submit() {
        val selectedAccount = account ?: return
        val amount = result ?: return
        val accountRate = parseMoney(manualAccount)
        val budgetRate = parseMoney(manualBudget)
        if (manualAccount.isNotBlank() && accountRate?.signum() != 1 || manualBudget.isNotBlank() && budgetRate?.signum() != 1) {
            vm.notice("Enter a positive decimal exchange rate.")
            return
        }
        val draft = EntryDraft(type, amount, date, selectedAccount.id, categoryId, currency, note, payer,
            destinationId, parseMoney(received), accountRate, budgetRate)
        vm.execute("Transaction saved", navigateBack) {
            vm.repository.saveEntry(budget.id, draft, entry?.id.takeIf { editing }, !vm.rateNetwork.available() && date <= LocalDate.now())
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(navigateBack) { Icon(Icons.Outlined.ArrowBack, "Back") }
            Text(tr((if (editing) "Edit " else "New ") + type.label().lowercase()), Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall)
            if (editing && allowed) IconButton({ delete = true }, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "Delete transaction") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = Forest, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(currency, color = Mint, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        Text(expression.ifBlank { "0" }, color = Color.White, style = MaterialTheme.typography.headlineLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton({ expression = expression.dropLast(1) }) { Icon(Icons.Outlined.Backspace, "Delete last digit", tint = Mint) }
                    }
                    Text(liveResult?.let { money(it, currency) } ?: "Enter an amount", color = Mint, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (account != null && type != EntryType.TRANSFER && (currency != account.currency || currency != budget.currency)) {
                val plan = EntryRatePlan(currency, date, account.currency, budget.currency, entry.takeIf { editing })
                EntryRatePreview(plan, liveResult?.forCurrency(currency), parseMoney(manualAccount)?.takeIf { it.signum() > 0 },
                    parseMoney(manualBudget)?.takeIf { it.signum() > 0 }, vm)
            }
            Calculator(expression) { expression = it }
            val accountNames = accounts.associate { it.id to "${it.name} · ${it.currency}" }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Select(if (type == EntryType.TRANSFER) "From account" else "Account", accountId, accounts.map { it.id },
                    { accountNames[it] ?: "Choose account" }, Modifier.weight(1f)) {
                    accountId = it; currencyOverride = null; manualAccount = ""; manualBudget = ""
                    if (destinationId == it) destinationId = null
                }
                if (type != EntryType.TRANSFER) Select("Currency", currency, currencyCodes, { it }, Modifier.weight(.65f)) {
                    currencyOverride = it; manualAccount = ""; manualBudget = ""
                } else Select("To account", destinationId, destinations.map { it.id }, { accountNames[it] ?: "Choose account" },
                    Modifier.weight(1f)) { destinationId = it; received = "" }
            }
            if (type != EntryType.TRANSFER) {
                val names = categories.associate { it.id to it.name }
                Select("Category", categoryId, categories.map { it.id }, { names[it] ?: "Choose category" }, Modifier.fillMaxWidth()) { categoryId = it }
            } else if (destination != null && destination.currency != currency) {
                Field("Amount received in ${destination.currency}", received, { received = it })
            }
            DateButton("", date, Modifier.fillMaxWidth()) { dateText = it.toString(); manualAccount = ""; manualBudget = "" }
            if (state.ledger.members.isNotEmpty()) {
                val members = state.ledger.members.associate { it.userId to it.name }
                Select("Paid by", payer, state.ledger.members.filter { it.active || it.userId == payer }.map { it.userId },
                    { members[it] ?: "Select payer" }, Modifier.fillMaxWidth()) { payer = it }
            }
            Field(tr("Note (optional)"), note, { if (it.length <= 2000) note = it }, singleLine = false)
            if (account != null && type != EntryType.TRANSFER && (currency != account.currency || currency != budget.currency)) {
                TextButton({ showRates = !showRates }) {
                    Text(tr(if (showRates) "Hide manual rate" else "Manual exchange rate"))
                }
                if (showRates) {
                    if (currency != account.currency) Field("Manual $currency → ${account.currency}", manualAccount, { manualAccount = it })
                    if (currency != budget.currency && budget.currency != account.currency)
                        Field("Manual $currency → ${budget.currency}", manualBudget, { manualBudget = it })
                }
            }
        }
        Button(::submit, enabled = canSave, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp).heightIn(min = 50.dp)) {
            Text(tr(if (busy) "Saving…" else if (editing) "Save changes" else "Save ${type.label().lowercase()}"))
        }
    }
    if (delete && entry != null) Confirm("Delete transaction?", "It will be removed from your history and totals.",
        { delete = false }, "Delete") {
        vm.execute("Transaction deleted", navigateBack) { vm.repository.deleteEntry(entry) }
        delete = false
    }
}

@Composable
fun Calculator(expression: String, onExpression: (String) -> Unit) {
    val operators = "+−×÷"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("1 2 3 +", "4 5 6 −", "7 8 9 ×", ". 0 = ÷").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.split(" ").forEach { key ->
                    val secondary = key.length == 1 && key[0] in operators + "="
                    FilledTonalButton(
                        onClick = {
                            val last = expression.lastOrNull()
                            val token = expression.substringAfterLast(operators.findLast { it in expression } ?: '\u0000')
                            val updated = when {
                                key == "=" -> calculate(expression)?.storage() ?: expression
                                key == "." && expression.takeLastWhile { it !in operators }.contains('.') -> expression
                                key[0] in operators && last == null -> expression
                                key[0] in operators && last != null && last in operators -> expression.dropLast(1) + key
                                expression.length >= 96 -> expression
                                key == "." && token.isEmpty() -> expression + "0."
                                else -> expression + key
                            }
                            onExpression(updated)
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (secondary)
                            MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
                        shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(5.dp)
                    ) { Text(key, style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
        TextButton({ onExpression("") }, Modifier.align(Alignment.End)) { Text(tr("Clear amount")) }
    }
}

@Composable
fun Confirm(title: String, description: String, dismiss: () -> Unit, action: String, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(tr(title)) }, text = { Text(tr(description)) },
        dismissButton = { TextButton(dismiss) { Text(tr("Cancel")) } }, confirmButton = { TextButton(confirm) { Text(action) } })
}
