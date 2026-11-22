package dev.sharedledger.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sharedledger.R
import dev.sharedledger.data.*
import dev.sharedledger.domain.*
import java.text.NumberFormat
import java.time.LocalDate

@Composable
fun HomeScreen(state: LedgerState, vm: LedgerViewModel, openEntry: (Entry) -> Unit, openHistory: (EntryType?, String?) -> Unit) {
    val budget = state.budget
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 14.dp, 20.dp, 22.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("SharedLedger", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text(tr("Overview"), style = MaterialTheme.typography.headlineLarge)
                }
                if (budget != null) Select("Budget", budget, state.budgets, { it.name }, Modifier.widthIn(max = 168.dp)) { vm.select(it.id) }
            }
        }
        if (budget == null) {
            item { Panel { EmptyMessage(tr("No active budget"), tr("Create a budget in Settings to start tracking.")) } }
            return@LazyColumn
        }
        item { PeriodControl(state.period, { vm.period.value = it }) }
        item {
            val current = state.filters.account
            val names = state.ledger.accounts.associate { it.id to it.name }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Select("Account", current, listOf(null) + names.keys, { names[it] ?: "All accounts" }, Modifier.weight(1f)) {
                    vm.filters.value = state.filters.copy(account = it)
                }
                if (state.ledger.members.isNotEmpty()) {
                    val members = state.ledger.members.associate { it.userId to it.name }
                    Select("Member", state.filters.payer, listOf(null) + members.keys, { members[it] ?: "All members" }, Modifier.weight(1f)) {
                        vm.filters.value = state.filters.copy(payer = it)
                    }
                }
                if (current == null && state.filters.copy(account = null, payer = null) != Filters()) {
                    IconButton({ vm.filters.value = Filters(payer = state.filters.payer) }) { Icon(Icons.Outlined.FilterAltOff, "Clear transaction filters") }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Forest, Color(0xFF285E48))))
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tr("Net cash flow"), color = Mint, style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Outlined.AccountBalanceWallet, null, tint = Mint)
                }
                Text(valuationMoney(state.totals.net, budget.currency, state.totals.approximate), color = Color.White, style = MaterialTheme.typography.displaySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                HorizontalDivider(color = Color.White.copy(alpha = .18f))
                Row {
                    SummaryFigure("Income", valuationMoney(state.totals.income, budget.currency, state.totals.approximateIncome), Mint, Modifier.weight(1f))
                    SummaryFigure("Expenses", valuationMoney(state.totals.expenses, budget.currency, state.totals.approximateExpenses), Color(0xFFFFC9B6), Modifier.weight(1f))
                }
            }
        }
        if (state.totals.pending > 0) item {
            Text("${state.totals.pending} transaction(s) awaiting a rate. Totals are partial.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        item { Distribution(state.entries, budget.currency, state.ledger.categories) { type, category -> openHistory(type, category) } }
        item {
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Recent activity"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton({ openHistory(null, null) }) { Text(tr("View all")) }
                }
                if (state.entries.isEmpty()) EmptyMessage(tr("No transactions in this period"), tr("Add an expense or income to get started."))
                state.entries.take(5).forEach { EntryRow(it, state.ledger, budget.currency) { openEntry(it) } }
            }
        }
    }
}

@Composable
private fun SummaryFigure(label: String, value: String, tint: Color, modifier: Modifier) {
    Column(modifier) {
        Text(tr(label), color = tint, style = MaterialTheme.typography.labelMedium)
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

enum class GroupBy {
    DATE, CATEGORY, ACCOUNT, PAYER;

    fun key(entry: Entry): String = when (this) {
        DATE -> entry.date
        CATEGORY -> entry.categoryId ?: "transfer"
        ACCOUNT -> entry.accountId
        PAYER -> entry.payer.orEmpty()
    }
}

@Composable
fun HistoryScreen(state: LedgerState, vm: LedgerViewModel, openEntry: (Entry) -> Unit) {
    var group by rememberSaveable { mutableStateOf(GroupBy.DATE) }
    var expandedFilters by rememberSaveable { mutableStateOf(false) }
    val budget = state.budget
    val currency = budget?.currency ?: state.preferences.defaultCurrency
    var collapsed by remember(group, budget?.id) { mutableStateOf(setOf<String>()) }
    val names = remember(state.ledger.categories) { state.ledger.categories.associate { it.id to it.name } }
    val accounts = remember(state.ledger.accounts) { state.ledger.accounts.associate { it.id to it.name } }
    val members = remember(state.ledger.members) { state.ledger.members.associate { it.userId to it.name } }
    val grouping = remember(state.entries, group, currency) { transactionGroups(state.entries, currency, group::key) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LazyColumn(Modifier.fillMaxSize().testTag("history-list"), contentPadding = PaddingValues(20.dp, 14.dp, 20.dp, 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(tr("Transactions"), style = MaterialTheme.typography.headlineLarge) }
        if (budget != null) {
            item { Select("Budget", budget, state.budgets, { it.name }, Modifier.fillMaxWidth()) { vm.select(it.id) } }
        }
        item { PeriodControl(state.period, { vm.period.value = it }, true) }
        item {
            OutlinedTextField(
                value = state.filters.query,
                onValueChange = { vm.filters.value = state.filters.copy(query = it) },
                label = { Text(tr("Search notes and categories")) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() })
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Select("Group by", group, GroupBy.entries, { it.label() }, Modifier.weight(1f)) { group = it }
                OutlinedButton({ expandedFilters = !expandedFilters }, Modifier.heightIn(min = 49.dp)) {
                    Icon(Icons.Outlined.Tune, null); Spacer(Modifier.width(6.dp)); Text(tr("Filters"))
                }
            }
        }
        if (expandedFilters) item {
            Panel {
                Select("Category", state.filters.category, listOf(null) + names.keys, { names[it] ?: "All categories" }, Modifier.fillMaxWidth()) {
                    vm.filters.value = state.filters.copy(category = it)
                }
                Select("Account", state.filters.account, listOf(null) + accounts.keys, { accounts[it] ?: "All accounts" }, Modifier.fillMaxWidth()) {
                    vm.filters.value = state.filters.copy(account = it)
                }
                Select("Type", state.filters.type, listOf(null) + EntryType.entries, { it?.label() ?: "All types" }, Modifier.fillMaxWidth()) {
                    vm.filters.value = state.filters.copy(type = it)
                }
                Select("Currency", state.filters.currency, listOf(null) + currencyCodes, { it ?: "All currencies" }, Modifier.fillMaxWidth()) {
                    vm.filters.value = state.filters.copy(currency = it)
                }
                if (members.isNotEmpty()) Select("Payer", state.filters.payer, listOf(null) + members.keys, { members[it] ?: "All members" }, Modifier.fillMaxWidth()) {
                    vm.filters.value = state.filters.copy(payer = it)
                }
                var min by rememberSaveable { mutableStateOf(state.filters.minimum?.storage().orEmpty()) }
                var max by rememberSaveable { mutableStateOf(state.filters.maximum?.storage().orEmpty()) }
                Field("Minimum in ${budget?.currency.orEmpty()}", min, { min = it })
                Field("Maximum in ${budget?.currency.orEmpty()}", max, { max = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(state.filters.byAmount, { vm.filters.value = state.filters.copy(byAmount = !state.filters.byAmount) }, { Text(tr("Sort by amount")) })
                    Spacer(Modifier.weight(1f))
                    TextButton({
                        val lower = parseMoney(min); val upper = parseMoney(max)
                        if (min.isNotBlank() && lower == null || max.isNotBlank() && upper == null || lower != null && upper != null && lower > upper)
                            vm.notice("Enter a valid amount range.")
                        else vm.filters.value = state.filters.copy(minimum = lower, maximum = upper)
                    }) { Text(tr("Apply amounts")) }
                }
                TextButton({ vm.filters.value = Filters(); min = ""; max = "" }) { Text(tr("Reset filters")) }
            }
        }
        if (grouping.isEmpty()) item { Panel { EmptyMessage(tr("No matching transactions"), tr("Change the date range or clear your filters.")) } }
        grouping.forEach { summary ->
            val key = summary.key
            val expanded = key !in collapsed
            item("header-$group-$key") {
                val title = when (group) {
                    GroupBy.DATE -> LocalDate.parse(key).shortDate()
                    GroupBy.CATEGORY -> names[key] ?: tr(if (key == "transfer") "Transfers" else "Uncategorized")
                    GroupBy.ACCOUNT -> accounts[key] ?: tr("Account")
                    GroupBy.PAYER -> members[key] ?: stringResource(R.string.history_unassigned_payer)
                }
                TransactionGroupHeader(title, summary, currency, expanded, Modifier.animateItem()) {
                    collapsed = if (expanded) collapsed + key else collapsed - key
                }
            }
            if (expanded) items(summary.entries, key = { it.id }) { entry ->
                Surface(modifier = Modifier.animateItem(), shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.padding(horizontal = 10.dp)) { EntryRow(entry, state.ledger, currency) { openEntry(entry) } }
                }
            }
        }
    }
}

@Composable
private fun TransactionGroupHeader(title: String, group: TransactionGroup, currency: String, expanded: Boolean,
                                   modifier: Modifier = Modifier, toggle: () -> Unit) {
    val count = group.entries.size
    val countDescription = pluralStringResource(R.plurals.history_group_transactions, count, count)
    val action = stringResource(if (expanded) R.string.history_group_collapse else R.string.history_group_expand)
    val status = stringResource(if (expanded) R.string.history_group_expanded else R.string.history_group_collapsed)
    val totals = group.totals
    val mixed = totals.incoming.count > 0 && totals.outgoing.count > 0

    Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().testTag("history-group-${group.key}")
            .clickable(onClickLabel = action, onClick = toggle).semantics { stateDescription = status }) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null,
                    modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = countDescription }) {
                    Text(NumberFormat.getIntegerInstance(LedgerFormats.locale).format(count),
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (totals.incoming.count > 0) GroupAmount(totals.incoming, EntryType.INCOME, currency, mixed)
                if (totals.outgoing.count > 0) GroupAmount(totals.outgoing, EntryType.EXPENSE, currency, mixed)
                if (totals.pending > 0 && (totals.incoming.hasAmount || totals.outgoing.hasAmount)) {
                    Text(pluralStringResource(R.plurals.history_group_partial, totals.pending, totals.pending),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End)
                }
                if (group.transfersByCurrency.isNotEmpty()) {
                    Text(stringResource(R.string.history_group_transfers_sent), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                    group.transfersByCurrency.forEach { (source, amount) ->
                        Text(money(amount, source), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupAmount(total: DirectionTotal, type: EntryType, currency: String, mixed: Boolean) {
    val income = type == EntryType.INCOME
    val label = if (mixed) tr(if (income) "Income" else "Expenses") + " · " else ""
    val amount = if (total.hasAmount) {
        (if (total.approximate) "≈ " else "") + (if (income) "+" else "−") + money(total.amount, currency)
    } else tr("Rate pending")
    Text(label + amount, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
        color = if (income) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
}
