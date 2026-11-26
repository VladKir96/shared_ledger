package dev.sharedledger.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.onFocusChanged
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
    val list = rememberLazyListState()
    val chart = rememberDonutSelection(budget?.id)
    val filtered = state.filters != Filters()
    fun resetOverview() {
        chart.reset()
        vm.clearOverviewFilters()
    }
    OverviewReset(list, budget != null && (filtered || chart.canReset), ::resetOverview) {
        LazyColumn(Modifier.fillMaxSize().testTag("home-list"), state = list, contentPadding = PaddingValues(20.dp, 14.dp, 20.dp, 22.dp),
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
                }
            }
            if (filtered) item {
                TextButton(::resetOverview, Modifier.testTag("home-clear-filters")) {
                    Icon(Icons.Outlined.FilterAltOff, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.overview_clear_filters))
                }
            }
            item("home-chart") {
                Distribution(state.entries, budget.currency, state.ledger.categories, openHistory, chart, ::resetOverview,
                    swipeEnabled = true)
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
}

@Composable
private fun SummaryFigure(label: String, value: String, tint: Color, modifier: Modifier) {
    Column(modifier) {
        Text(tr(label), color = tint, style = MaterialTheme.typography.labelMedium)
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(state: LedgerState, vm: LedgerViewModel, visit: Int, openEntry: (Entry) -> Unit) {
    val groupSelection = rememberSaveable { mutableStateOf(GroupBy.DATE) }
    val sortSelection = rememberSaveable { mutableStateOf(GroupSort.NEWEST) }
    val group = groupSelection.value
    val sort = group.compatibleSort(sortSelection.value)
    var expandedFilters by rememberSaveable { mutableStateOf(false) }
    val budget = state.budget
    val currency = budget?.currency ?: state.preferences.defaultCurrency
    val expansionScope = "$visit/${group.name}/${budget?.id.orEmpty()}"
    val expansionState = rememberSaveable(expansionScope, stateSaver = GroupExpansion.Saver) {
        mutableStateOf(GroupExpansion(scope = expansionScope))
    }
    val expansion = expansionState.value.takeIf { it.scope == expansionScope } ?: GroupExpansion(scope = expansionScope)
    val names = remember(state.ledger.categories) { state.ledger.categories.associate { it.id to it.name } }
    val accounts = remember(state.ledger.accounts) { state.ledger.accounts.associate { it.id to it.name } }
    val members = remember(state.ledger.members) { state.ledger.members.associate { it.userId to it.name } }
    val aggregated = remember(state.entries, group, currency) { transactionGroups(state.entries, currency, group::key) }
    val otherLabel = when (group) {
        GroupBy.DATE -> ""
        GroupBy.CATEGORY -> tr("Uncategorized")
        GroupBy.ACCOUNT -> tr("Account")
        GroupBy.PAYER -> stringResource(R.string.history_unassigned_payer)
    }
    val groupNames = when (group) {
        GroupBy.DATE -> emptyMap()
        GroupBy.CATEGORY -> names + (GroupBy.TRANSFERS to tr("Transfers"))
        GroupBy.ACCOUNT -> accounts
        GroupBy.PAYER -> members
    }
    val locale = LedgerFormats.locale
    val titles = remember(aggregated, group, groupNames, otherLabel, locale) {
        aggregated.associate { (key) ->
            key to if (group == GroupBy.DATE) LocalDate.parse(key).shortDate() else groupNames[key] ?: otherLabel
        }
    }
    val grouping = remember(aggregated, group, sort, titles, locale) {
        sortTransactionGroups(aggregated, group, sort, titles, locale)
    }
    val displayedKeys = titles.keys
    val anyCollapsed = displayedKeys.any { !expansion.isExpanded(it) }
    val anyExpanded = displayedKeys.any { expansion.isExpanded(it) }
    fun expandVisible(expand: Boolean) {
        val knownKeys = state.ledger.entries.asSequence().filter { it.deletedAt == null }.map(group::key).toSet()
        expansionState.value = expansion.setVisible(displayedKeys, knownKeys, expand)
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()
    var searchFocused by remember { mutableStateOf(false) }
    var minimumFocused by remember { mutableStateOf(false) }
    var maximumFocused by remember { mutableStateOf(false) }
    var min by rememberSaveable(budget?.id) { mutableStateOf(state.filters.minimum?.storage().orEmpty()) }
    var max by rememberSaveable(budget?.id) { mutableStateOf(state.filters.maximum?.storage().orEmpty()) }
    val filtered = state.filters != Filters() || sort != group.sortOptions.first()
    fun resetOverview() {
        focus.clearFocus()
        keyboard?.hide()
        vm.clearOverviewFilters()
        sortSelection.value = group.sortOptions.first()
        min = ""
        max = ""
    }
    OverviewReset(list, filtered && !searchFocused && !minimumFocused && !maximumFocused, ::resetOverview) {
        LazyColumn(Modifier.fillMaxSize().testTag("history-list"), state = list, contentPadding = PaddingValues(20.dp, 14.dp, 20.dp, 22.dp),
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
                    modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused = it.hasFocus },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() })
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Select("Group by", group, GroupBy.entries, { it.label() }, Modifier.weight(1f).testTag("history-group-by")) {
                        sortSelection.value = it.compatibleSort(sort)
                        groupSelection.value = it
                    }
                    OutlinedButton({ expandedFilters = !expandedFilters }, Modifier.heightIn(min = 49.dp)) {
                        Icon(Icons.Outlined.Tune, null); Spacer(Modifier.width(6.dp)); Text(tr("Filters"))
                    }
                }
            }
            item {
                val sortLabels = group.sortOptions.associateWith { historySortLabel(it, group) }
                Select("Sort by", sort, group.sortOptions, sortLabels::getValue, Modifier.fillMaxWidth().testTag("history-sort")) {
                    sortSelection.value = it
                }
            }
            if (displayedKeys.isNotEmpty() || filtered) item("history-group-actions") {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (anyCollapsed) GroupExpansionButton(expand = true) { expandVisible(true) }
                    if (anyExpanded) GroupExpansionButton(expand = false) { expandVisible(false) }
                    if (filtered) TextButton(::resetOverview, Modifier.testTag("history-clear-filters")) {
                        Icon(Icons.Outlined.FilterAltOff, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(stringResource(R.string.overview_clear_filters))
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
                    MonetaryTextField("Minimum in ${budget?.currency.orEmpty()}", min, { min = it },
                        Modifier.fillMaxWidth().onFocusChanged { minimumFocused = it.hasFocus })
                    MonetaryTextField("Maximum in ${budget?.currency.orEmpty()}", max, { max = it },
                        Modifier.fillMaxWidth().onFocusChanged { maximumFocused = it.hasFocus })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        TextButton({
                            val lower = parseCurrencyInput(min); val upper = parseCurrencyInput(max)
                            if (min.isNotBlank() && lower == null || max.isNotBlank() && upper == null || lower != null && upper != null && lower > upper)
                                vm.notice("Enter a valid amount range.")
                            else vm.filters.value = state.filters.copy(minimum = lower, maximum = upper)
                        }) { Text(tr("Apply amounts")) }
                    }
                }
            }
            if (grouping.isEmpty()) item { Panel { EmptyMessage(tr("No matching transactions"), tr("Change the date range or clear your filters.")) } }
            grouping.forEach { summary ->
                val key = summary.key
                val expanded = expansion.isExpanded(key)
                item("header-$group-$key") {
                    TransactionGroupHeader(titles.getValue(key), summary, currency, expanded, Modifier.animateItem()) {
                        expansionState.value = expansion.toggle(key)
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
}

@Composable
private fun historySortLabel(sort: GroupSort, group: GroupBy): String = stringResource(when (sort) {
    GroupSort.NEWEST -> R.string.history_sort_newest
    GroupSort.OLDEST -> R.string.history_sort_oldest
    GroupSort.NAME_ASC -> R.string.history_sort_name_asc
    GroupSort.NAME_DESC -> R.string.history_sort_name_desc
    GroupSort.AMOUNT_HIGH -> if (group == GroupBy.CATEGORY) R.string.history_sort_amount_high else R.string.history_sort_expenses_high
    GroupSort.AMOUNT_LOW -> if (group == GroupBy.CATEGORY) R.string.history_sort_amount_low else R.string.history_sort_expenses_low
})

@Composable
private fun GroupExpansionButton(expand: Boolean, onClick: () -> Unit) {
    TextButton(onClick, Modifier.testTag(if (expand) "expand-all-groups" else "collapse-all-groups")) {
        Icon(if (expand) Icons.Outlined.UnfoldMore else Icons.Outlined.UnfoldLess, null, Modifier.size(18.dp))
        Spacer(Modifier.width(5.dp))
        Text(stringResource(if (expand) R.string.history_expand_all else R.string.history_collapse_all))
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
