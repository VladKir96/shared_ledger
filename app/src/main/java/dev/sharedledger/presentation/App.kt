package dev.sharedledger.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import dev.sharedledger.domain.*

private data class Tab(val route: String, val title: String, val icon: ImageVector)
private val tabs = listOf(
    Tab("home", "Home", Icons.Outlined.Home),
    Tab("history", "Transactions", Icons.Outlined.ReceiptLong),
    Tab("analytics", "Analytics", Icons.Outlined.BarChart),
    Tab("settings", "Settings", Icons.Outlined.Settings)
)

@Composable
fun LedgerApp(vm: LedgerViewModel, link: android.net.Uri? = null, clearLink: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val authCallback by vm.authCallback.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    var entryDetails by rememberSaveable { mutableStateOf<String?>(null) }
    fun edit(type: EntryType, id: String = "new", duplicate: Boolean = false) = nav.navigate("entry/${type.name}/$id/$duplicate")
    fun go(tab: String) = nav.navigate(tab) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    fun openCloud() { nav.navigate("cloud-auth") { launchSingleTop = true } }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it, duration = SnackbarDuration.Short) } }
    LedgerTheme(state.preferences.theme, state.preferences.dynamicColor) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (tabs.any { it.route == route }) {
                    Column {
                        if (route in listOf("home", "history", "analytics") && state.budget?.role in listOf(Role.OWNER, Role.EDITOR)) {
                            Surface(color = MaterialTheme.colorScheme.background) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button({ edit(EntryType.EXPENSE) }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("add-expense"),
                                        shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.error)) {
                                        Icon(Icons.Outlined.Remove, null); Spacer(Modifier.width(5.dp)); Text(tr("Expense"))
                                    }
                                    Button({ edit(EntryType.INCOME) }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("add-income"),
                                        shape = RoundedCornerShape(15.dp)) {
                                        Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(5.dp)); Text(tr("Income"))
                                    }
                                    FilledTonalIconButton({ edit(EntryType.TRANSFER) }, Modifier.size(48.dp), shape = RoundedCornerShape(15.dp)) {
                                        Icon(Icons.Outlined.SwapHoriz, "Transfer")
                                    }
                                }
                            }
                        }
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            tabs.forEach { tab ->
                                NavigationBarItem(modifier = Modifier.testTag("tab-${tab.route}"), selected = tab.route == route, onClick = { go(tab.route) },
                                    icon = { Icon(tab.icon, null) }, label = { Text(tr(tab.title), maxLines = 1) })
                            }
                        }
                    }
                }
            }
        ) { padding ->
            NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding).imePadding()) {
                composable("home") {
                    HomeScreen(state, vm, { entryDetails = it.id }) { type, category ->
                        vm.filters.value = state.filters.copy(type = type, category = category)
                        go("history")
                    }
                }
                composable("history") { HistoryScreen(state, vm) { entryDetails = it.id } }
                composable("analytics") {
                    AnalyticsScreen(state, vm) { type, category ->
                        vm.filters.value = state.filters.copy(type = type, category = category)
                        go("history")
                    }
                }
                composable("settings") { SettingsScreen(state, vm) { go("home") } }
                composable("cloud-auth") {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("Back")) }
                            Text(tr("Cloud sync"), style = MaterialTheme.typography.headlineSmall)
                        }
                        CloudSettings(state, vm) { go("home") }
                    }
                }
                composable("entry/{type}/{id}/{duplicate}",
                    arguments = listOf(navArgument("duplicate") { type = NavType.BoolType })) { back ->
                    val type = EntryType.valueOf(back.arguments!!.getString("type")!!)
                    val id = back.arguments!!.getString("id")
                    val existing = state.ledger.entries.find { it.id == id }
                    if (id == "new" || existing != null) {
                        EditorScreen(state, vm, type, existing, back.arguments!!.getBoolean("duplicate")) { nav.popBackStack() }
                    } else Panel { EmptyMessage("Transaction unavailable", "Return to history to review available transactions.") }
                }
            }
        }
        val authState = authCallback
        if (authState != null) {
            AuthCallbackDialog(authState, vm, ::openCloud)
        } else IncomingInvitation(link, vm, clearLink, ::openCloud) { go("home") }
        val item = if (authState == null) state.ledger.entries.find { it.id == entryDetails } else null
        if (item != null) AlertDialog(
            onDismissRequest = { entryDetails = null },
            title = { Text(state.ledger.categories.find { it.id == item.categoryId }?.name ?: "Transfer") },
            text = {
                Column {
                    EntryRow(item, state.ledger)
                    if (item.note.isNotBlank()) Text(item.note)
                    RecordedExchangeRates(item)
                    item.destinationAmount?.let { Text("Received ${money(it.decimal(), item.destinationCurrency!!)}") }
                    item.transferRate?.let { Text("Transfer rate $it") }
                    state.ledger.members.find { it.userId == item.creator }?.let { Text("Created by ${it.name}") }
                }
            },
            confirmButton = {
                if (state.budget?.role != Role.VIEWER) TextButton({ entryDetails = null; edit(item.type, item.id) }) { Text(tr("Edit")) }
            },
            dismissButton = {
                if (state.budget?.role != Role.VIEWER) TextButton({ entryDetails = null; edit(item.type, item.id, true) }) { Text(tr("Duplicate")) }
                else TextButton({ entryDetails = null }) { Text(tr("Close")) }
            }
        )
    }
}
