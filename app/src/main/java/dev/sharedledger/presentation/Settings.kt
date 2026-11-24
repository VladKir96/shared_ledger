package dev.sharedledger.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.sharedledger.data.*
import dev.sharedledger.domain.*
import java.math.BigDecimal

@Composable
fun SettingsScreen(state: LedgerState, vm: LedgerViewModel, openUpdates: Boolean = false,
                   consumedUpdates: () -> Unit = {}, onBudgetJoined: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf("") }
    fun back() { screen = if (screen == "App Updates") "About" else "" }
    BackHandler(screen.isNotEmpty(), ::back)
    LaunchedEffect(openUpdates) {
        if (openUpdates) {
            screen = "App Updates"
            consumedUpdates()
        }
    }
    fun toggle(name: String) { screen = name }
    if (screen.isNotEmpty()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(::back) { Icon(Icons.Outlined.ArrowBack, tr("Back")) }
                Text(tr(screen), style = MaterialTheme.typography.headlineSmall)
            }
            when (screen) {
                "Budgets" -> Budgets(state, vm)
                "Accounts" -> Accounts(state, vm)
                "Categories" -> Categories(state, vm)
                "Appearance" -> Appearance(state, vm)
                "Notifications" -> NotificationSettings(state, vm)
                "About" -> Column(Modifier.padding(18.dp)) {
                    Panel { SettingsLink("App Updates", "Check and install new versions", Icons.Outlined.SystemUpdate, ::toggle) }
                }
                "App Updates" -> AppUpdateSettings(vm.updates)
                else -> AdvancedSettings(screen, state, vm, onBudgetJoined)
            }
        }
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 14.dp, 20.dp, 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(tr("Settings"), style = MaterialTheme.typography.headlineLarge) }
        item {
            Panel {
                SettingsLink("Budgets", state.budget?.name ?: "Create a budget", Icons.Outlined.AccountBalanceWallet, ::toggle)
                SettingsLink("Accounts", "Balances and wallets", Icons.Outlined.AccountBalance, ::toggle)
                SettingsLink("Categories", "Expense and income categories", Icons.Outlined.Category, ::toggle)
                AdditionalSettingsLinks(::toggle)
            }
        }
        item {
            Panel {
                SettingsLink("Appearance", "Theme and colors", Icons.Outlined.Palette, ::toggle)
                SettingsLink("Notifications", "Shared budget activity", Icons.Outlined.Notifications, ::toggle)
                PreferenceSettingsLinks(::toggle)
                SettingsLink("About", "Version and updates", Icons.Outlined.Info, ::toggle)
            }
        }
    }
}

@Composable
fun SettingsLink(title: String, detail: String, icon: ImageVector, onOpen: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpen(title) }.heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(tr(title), style = MaterialTheme.typography.titleMedium)
            Text(tr(detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, null)
    }
}

@Composable
private fun Budgets(state: LedgerState, vm: LedgerViewModel) {
    var editor by remember { mutableStateOf<Budget?>(null) }
    var creating by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf<Budget?>(null) }
    val repository = vm.repository
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Button({ creating = true }, Modifier.fillMaxWidth()) { Text(tr("Create budget")) } }
        items(state.budgets, key = { it.id }) { budget ->
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryMark(budget.icon, budget.color)
                    Column(Modifier.weight(1f)) {
                        Text(budget.name, style = MaterialTheme.typography.titleMedium)
                        Text("${budget.type.label()} · ${budget.currency} · ${budget.role.label()}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.budget?.id == budget.id) Icon(Icons.Outlined.CheckCircle, "Active budget")
                    else TextButton({ vm.select(budget.id) }) { Text(tr("Switch")) }
                }
                if (budget.role == Role.OWNER) Row {
                    TextButton({ editor = budget }) { Text(tr("Edit")) }
                    TextButton({ removal = budget }) { Text(tr("Delete")) }
                }
            }
        }
    }
    if (creating || editor != null) {
        val value = editor
        var name by remember { mutableStateOf(value?.name.orEmpty()) }
        var currency by remember { mutableStateOf(value?.currency ?: state.preferences.defaultCurrency) }
        var type by remember { mutableStateOf(value?.type ?: BudgetType.PERSONAL) }
        var icon by remember { mutableStateOf(value?.icon ?: "wallet") }
        var color by remember { mutableLongStateOf(value?.color ?: categoryColors.first()) }
        var account by remember { mutableStateOf("Cash") }
        EditDialog(if (creating) "Create budget" else "Edit budget", { creating = false; editor = null }, name.isNotBlank(), {
            vm.execute("Budget saved", { creating = false; editor = null }) {
                if (value == null) repository.createBudget(name, currency, type, icon, color, account)
                else repository.updateBudget(value.copy(name = name, currency = currency, icon = icon, color = color))
            }
        }) {
            Field(tr("Name"), name, { name = it })
            if (creating) Select("Type", type, BudgetType.entries, { it.label() }, Modifier.fillMaxWidth()) { type = it }
            Select("Base currency", currency, currencyCodes, { it }, Modifier.fillMaxWidth()) { currency = it }
            if (value != null && value.currency != currency) Text("Existing transactions will use their historical rates where available.")
            if (creating) Field(tr("First account"), account, { account = it })
            IconColor(icon, { icon = it }, color, { color = it })
        }
    }
    removal?.let { budget ->
        Confirm("Delete ${budget.name}?", "Accounts, categories and transactions in this budget will be removed.",
            { removal = null }, "Delete") {
            vm.execute("Budget deleted", { removal = null }) { repository.updateBudget(budget.copy(deletedAt = System.currentTimeMillis())) }
        }
    }
}

@Composable
private fun Accounts(state: LedgerState, vm: LedgerViewModel) {
    val budget = state.budget ?: return
    var editor by remember { mutableStateOf<Account?>(null) }
    val balances by produceState(emptyMap<String, Balance>(), state.ledger.accounts, state.ledger.entries) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            state.ledger.accounts.associate { it.id to accountBalance(it, state.ledger.entries) }
        }
    }
    val active = state.ledger.accounts.filterNot { it.archived }
    val combined = active.groupBy { it.currency }.mapValues { (_, accounts) ->
        accounts.mapNotNull { balances[it.id] }.fold(BigDecimal.ZERO) { total, balance -> total + balance.value }
    }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (budget.role == Role.OWNER) item {
            Button({ editor = Account(id(), budget.id, "", budget.currency, order = state.ledger.accounts.size) }, Modifier.fillMaxWidth()) {
                Text(tr("Add account"))
            }
        }
        if (combined.isNotEmpty()) item {
            Panel(Modifier.fillMaxWidth()) {
                Text(tr("Combined balances"), style = MaterialTheme.typography.titleMedium)
                combined.forEach { (currency, amount) -> Text(money(amount, currency)) }
                if (active.any { (balances[it.id]?.pending ?: 0) > 0 }) Text("Balances exclude unconverted amounts.", color = MaterialTheme.colorScheme.error)
            }
        }
        items(state.ledger.accounts, key = { it.id }) { account ->
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CategoryMark(account.icon, account.color)
                    Column(Modifier.weight(1f)) {
                        Text(account.name, style = MaterialTheme.typography.titleMedium)
                        Text(account.type.label() + if (account.archived) " · Archived" else "")
                    }
                    balances[account.id]?.let {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(money(it.value, account.currency), style = MaterialTheme.typography.labelLarge)
                            if (it.pending > 0) Text(tr("Partial balance"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (budget.role == Role.OWNER) Row {
                    TextButton({ editor = account }) { Text(tr("Edit")) }
                    TextButton({
                        vm.execute(if (account.archived) "Account restored" else "Account archived") {
                            vm.repository.saveAccount(account.copy(archived = !account.archived))
                        }
                    }) { Text(if (account.archived) "Restore" else "Archive") }
                }
            }
        }
    }
    editor?.let { account ->
        var name by remember { mutableStateOf(account.name) }
        var currency by remember { mutableStateOf(account.currency) }
        var type by remember { mutableStateOf(account.type) }
        var opening by remember { mutableStateOf(account.opening) }
        var icon by remember { mutableStateOf(account.icon) }
        var color by remember { mutableLongStateOf(account.color) }
        EditDialog("Account", { editor = null }, name.isNotBlank(), {
            vm.execute("Account saved", { editor = null }) {
                vm.repository.saveAccount(account.copy(name = name, currency = currency, type = type, opening = opening, icon = icon, color = color))
            }
        }) {
            Field(tr("Name"), name, { name = it })
            Select("Type", type, AccountType.entries, { it.label() }, Modifier.fillMaxWidth()) { type = it }
            Select("Currency", currency, currencyCodes, { it }, Modifier.fillMaxWidth()) { currency = it }
            Field(tr("Opening balance"), opening, { opening = it })
            IconColor(icon, { icon = it }, color, { color = it })
        }
    }
}

@Composable
private fun Categories(state: LedgerState, vm: LedgerViewModel) {
    val budget = state.budget ?: return
    var type by rememberSaveable { mutableStateOf(EntryType.EXPENSE) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var editor by remember { mutableStateOf<Category?>(null) }
    var merge by remember { mutableStateOf<Category?>(null) }
    val all = state.ledger.categories.filter { it.type == type }
    var ordered by remember(all, showArchived) { mutableStateOf(all.filter { it.archived == showArchived }) }
    val list = rememberLazyListState()
    var dragged by remember { mutableStateOf<String?>(null) }
    val canEdit = budget.role == Role.OWNER
    Column(Modifier.padding(horizontal = 18.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(type == EntryType.EXPENSE, { type = EntryType.EXPENSE }, { Text(tr("Expenses")) })
            FilterChip(type == EntryType.INCOME, { type = EntryType.INCOME }, { Text(tr("Income")) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(showArchived, { showArchived = !showArchived }, { Text(tr("Archived")) })
            Spacer(Modifier.weight(1f))
            if (canEdit) TextButton({ editor = Category(id(), budget.id, "", type, "more", categoryColors.first(), all.size) }) {
                Icon(Icons.Outlined.Add, null); Text(tr("Add category"))
            }
        }
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(ordered, key = { it.id }) { category ->
                Surface(shape = RoundedCornerShape(12.dp), color = if (dragged == category.id)
                    MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CategoryMark(category.icon, category.color)
                        Text(category.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (canEdit) {
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "Manage ${category.name}") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem({ Text(tr("Edit")) }, { menu = false; editor = category })
                                    DropdownMenuItem({ Text(if (category.archived) "Restore" else "Archive") }, {
                                        menu = false
                                        vm.execute { vm.repository.saveCategory(category.copy(archived = !category.archived)) }
                                    })
                                    if (!showArchived) {
                                        DropdownMenuItem({ Text("Merge or move transactions") }, { menu = false; merge = category })
                                        listOf(-1 to "Move up", 1 to "Move down").forEach { (offset, label) ->
                                            DropdownMenuItem({ Text(label) }, {
                                                menu = false
                                                val from = ordered.indexOf(category)
                                                val to = from + offset
                                                if (to in ordered.indices) {
                                                    val order = ordered.toMutableList().apply { add(to, removeAt(from)) }
                                                    vm.execute { vm.repository.reorder(order) }
                                                }
                                            })
                                        }
                                    }
                                }
                            }
                            if (!showArchived) Icon(Icons.Outlined.DragHandle, "Hold and drag to reorder",
                                Modifier.size(36.dp).padding(5.dp).pointerInput(category.id) {
                                    var drift = 0f
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { dragged = category.id; drift = 0f },
                                        onDragEnd = {
                                            dragged = null
                                            val order = ordered
                                            vm.execute { vm.repository.reorder(order) }
                                        },
                                        onDragCancel = { dragged = null; ordered = all.filterNot { it.archived } }
                                    ) { change, delta ->
                                        change.consume()
                                        drift += delta.y
                                        val from = ordered.indexOfFirst { it.id == category.id }
                                        val crossed = kotlin.math.abs(drift) > 33.dp.toPx()
                                        val to = if (drift > 0) from + 1 else from - 1
                                        if (crossed && from >= 0 && to in ordered.indices) {
                                            ordered = ordered.toMutableList().apply { add(to, removeAt(from)) }
                                            drift = 0f
                                        }
                                    }
                                })
                        }
                    }
                }
            }
        }
    }
    editor?.let { category ->
        var name by remember { mutableStateOf(category.name) }
        var icon by remember { mutableStateOf(category.icon) }
        var color by remember { mutableLongStateOf(category.color) }
        EditDialog("Category", { editor = null }, name.isNotBlank(), {
            vm.execute("Category saved", { editor = null }) {
                vm.repository.saveCategory(category.copy(name = name, icon = icon, color = color))
            }
        }) {
            Field(tr("Name"), name, { name = it })
            IconColor(icon, { icon = it }, color, { color = it })
        }
    }
    merge?.let { source ->
        var target by remember { mutableStateOf<Category?>(null) }
        var archive by remember { mutableStateOf(true) }
        EditDialog("Move transactions from ${source.name}", { merge = null }, target != null, {
            val destination = target ?: return@EditDialog
            vm.execute("Transactions moved", { merge = null }) { vm.repository.merge(source, destination, archive) }
        }) {
            Select("Destination", target, all.filter { it.id != source.id && !it.archived }, { it?.name ?: "Choose category" },
                Modifier.fillMaxWidth()) { target = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(archive, { archive = it }); Text("Archive the source category")
            }
        }
    }
}

@Composable
fun IconColor(icon: String, onIcon: (String) -> Unit, color: Long, onColor: (Long) -> Unit) {
    Select("Icon", icon, categoryIcons + listOf("bank", "card"), { it }, Modifier.fillMaxWidth(), onIcon)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        CategoryMark(icon, color)
        categoryColors.chunked(4).forEach { row ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                row.forEachIndexed { _, value ->
                    IconButton({ onColor(value) }, Modifier.size(42.dp)) {
                        Icon(if (value == color) Icons.Outlined.CheckCircle else Icons.Outlined.Circle,
                            "Color %06X".format(value and 0xFFFFFF), tint = Color(value))
                    }
                }
            }
        }
    }
}

@Composable
fun EditDialog(title: String, dismiss: () -> Unit, enabled: Boolean, save: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
        dismissButton = { TextButton(dismiss) { Text(tr("Cancel")) } },
        confirmButton = { TextButton(save, enabled = enabled) { Text(tr("Save")) } })
}

@Composable
private fun Appearance(state: LedgerState, vm: LedgerViewModel) {
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Select("Theme", state.preferences.theme, ThemeMode.entries, { it.label() }, Modifier.fillMaxWidth()) { mode ->
            vm.execute { vm.repository.preferences { it.copy(theme = mode) } }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Use device colors"), Modifier.weight(1f))
            Switch(state.preferences.dynamicColor, { toggle -> vm.execute { vm.repository.preferences { it.copy(dynamicColor = toggle) } } })
        }
    }
}
