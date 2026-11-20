package dev.sharedledger.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sharedledger.data.*
import dev.sharedledger.domain.*
import dev.sharedledger.sync.RateWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
private fun onlineRates(vm: LedgerViewModel): Boolean {
    val flow = remember(vm.rateNetwork) { vm.rateNetwork.observe() }
    return flow.collectAsStateWithLifecycle(initialValue = vm.rateNetwork.available()).value
}

@Composable
fun EntryRatePreview(
    plan: EntryRatePlan, amount: BigDecimal?, accountOverride: BigDecimal?, budgetOverride: BigDecimal?, vm: LedgerViewModel
) {
    val online = onlineRates(vm)
    val today = LocalDate.now()
    val targets = plan.foreignTargets(accountOverride, budgetOverride)
    val allowOlder = !online && plan.day <= today
    var cache by remember(plan.base, plan.day, targets, online) { mutableStateOf(emptyMap<String, RateQuote?>()) }
    var loading by remember(plan.base, plan.day, targets, online) {
        mutableStateOf(targets.isNotEmpty() && online && plan.day <= today)
    }
    LaunchedEffect(plan.base, plan.day, targets, allowOlder) {
        if (targets.isNotEmpty()) vm.rates.observe(plan.base, targets, plan.day, allowOlder).collect { cache = it }
    }
    LaunchedEffect(plan.base, plan.day, targets, online) {
        if (loading) {
            try {
                withContext(Dispatchers.IO) { vm.rates.ensure(plan.base, targets, plan.day, today) }
            } finally {
                loading = false
            }
        }
    }
    val quotes = plan.quotes(cache, accountOverride, budgetOverride)
    Panel(Modifier.fillMaxWidth()) {
        if (plan.budget == plan.account && quotes.budget == quotes.account) {
            RateLine("Budget and account amount", plan.base, plan.budget, amount, quotes.budget, loading)
        } else {
            if (plan.budget != plan.base) RateLine("Budget amount", plan.base, plan.budget, amount, quotes.budget, loading)
            if (plan.account != plan.base) RateLine("Account amount", plan.base, plan.account, amount, quotes.account, loading)
        }
        if (!online && targets.isNotEmpty()) {
            Text(tr("Offline · cached rates may be outdated"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun RecordedExchangeRates(entry: Entry) {
    if (entry.type == EntryType.TRANSFER) return
    val plan = EntryRatePlan(entry.currency, LocalDate.parse(entry.date), entry.accountCurrency, entry.budgetCurrency, entry)
    val quotes = plan.quotes(emptyMap(), null, null)
    val amount = entry.amount.decimal()
    if (plan.budget != plan.base) {
        RateLine("Budget amount", plan.base, plan.budget, amount, quotes.budget.takeIf { entry.budgetAmount != null },
            recordedAmount = entry.budgetAmount)
    }
    if (plan.account != plan.base && (plan.account != plan.budget || quotes.account != quotes.budget || entry.accountAmount != entry.budgetAmount)) {
        RateLine("Account amount", plan.base, plan.account, amount, quotes.account.takeIf { entry.accountAmount != null },
            recordedAmount = entry.accountAmount)
    }
}

@Composable
private fun RateLine(
    label: String, base: String, target: String, amount: BigDecimal?, quote: RateQuote?, loading: Boolean = false,
    recordedAmount: String? = null
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr(label), style = MaterialTheme.typography.labelMedium)
        if (quote != null) {
            val converted = recordedAmount ?: amount?.let { quote.convert(it, target) }
            converted?.let { Text(money(it.decimal(), target), style = MaterialTheme.typography.titleMedium) }
            Text("1 $base = ${quote.value} $target", style = MaterialTheme.typography.bodySmall)
            Text("${LocalDate.parse(quote.date).shortDate()} · ${tr(quote.source)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(tr("Checking exchange rates…"), style = MaterialTheme.typography.bodySmall)
            }
        } else Text(tr("Conversion pending"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun CurrencySettings(state: LedgerState, vm: LedgerViewModel) {
    val context = LocalContext.current
    val online = onlineRates(vm)
    val running by vm.rateRefresh.running.collectAsStateWithLifecycle()
    val syncFlow = remember(vm) { vm.repository.dao.rateSyncFlow() }
    val sync by syncFlow.collectAsStateWithLifecycle(initialValue = null)
    val today = LocalDate.now()
    val displayBase = state.budget?.currency ?: state.preferences.defaultCurrency
    val targets = (initialCurrencies + state.ledger.accounts.filterNot { it.archived }.map { it.currency })
        .distinct().filterNot { it == displayBase }
    val latestFlow = remember(displayBase, targets, today) { vm.repository.dao.currentRates(displayBase, targets, today.toString()) }
    val latest by latestFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val manualFlow = remember(vm) { vm.repository.dao.manualRates() }
    val manual by manualFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    var base by rememberSaveable { mutableStateOf("EUR") }
    var quote by rememberSaveable { mutableStateOf(displayBase) }
    var dayText by rememberSaveable { mutableStateOf(today.toString()) }
    var rate by rememberSaveable { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Panel(Modifier.fillMaxWidth()) {
            val record = sync
            val status = when {
                running -> "Checking exchange rates…"
                !online -> "Offline · cached rates may be outdated"
                record?.retryable == true -> "Rate service unavailable · retry queued"
                record?.incomplete == true -> "Some rates are unavailable"
                record?.successDate == today.toString() -> "Up to date"
                else -> "Rates need refreshing"
            }
            Text(tr(status), style = MaterialTheme.typography.titleMedium)
            Text(vm.rates.providerNames.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            Text(tr("Last successful refresh"), style = MaterialTheme.typography.labelMedium)
            Text(record?.lastSuccess?.let {
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(LedgerFormats.locale)
                    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
            } ?: tr("No successful refresh yet"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Automatic updates"), Modifier.weight(1f))
                Switch(state.preferences.automaticRates, { enabled ->
                    vm.execute { vm.repository.preferences { it.copy(automaticRates = enabled) } }
                })
            }
            Text(tr("Updates rates in the background. Entry looks up missing rates as needed."),
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ RateWork.enqueue(context, manual = true) }, enabled = online && !running) { Text(tr("Refresh now")) }
        } }
        item { Panel(Modifier.fillMaxWidth()) {
            Text(tr("Current rates"), style = MaterialTheme.typography.titleLarge)
            val observed = latest.filter { it.base == displayBase && it.date <= today.toString() }.distinctBy { it.target }.associateBy { it.target }
            targets.forEach { target ->
                val observedRate = observed[target]
                if (observedRate == null) Text("$displayBase → $target · ${tr("Unavailable")}", style = MaterialTheme.typography.bodySmall)
                else {
                    Text("1 $displayBase = ${observedRate.value} $target", style = MaterialTheme.typography.titleMedium)
                    Text("${LocalDate.parse(observedRate.date).shortDate()} · ${observedRate.source}", style = MaterialTheme.typography.bodySmall)
                }
            }
        } }
        item { Panel(Modifier.fillMaxWidth()) {
            Select("Default currency for new budgets", state.preferences.defaultCurrency, currencyCodes, { it }, Modifier.fillMaxWidth()) { value ->
                vm.execute { vm.repository.preferences { it.copy(defaultCurrency = value) } }
            }
        } }
        item { Panel(Modifier.fillMaxWidth()) {
            Text(tr("Manual historical rate"), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Select("From", base, currencyCodes, { it }, Modifier.weight(1f)) { base = it }
                Select("To", quote, currencyCodes, { it }, Modifier.weight(1f)) { quote = it }
            }
            DateButton("", LocalDate.parse(dayText), Modifier.fillMaxWidth()) { dayText = it.toString() }
            Field("Units of $quote per 1 $base", rate, { rate = it })
            Text(tr("Saved rates can be used offline. Existing converted amounts keep their recorded rate."))
            Button({
                val value = parseMoney(rate)
                if (value == null || value.signum() <= 0) vm.notice("Enter a positive exchange rate.")
                else vm.execute("Rate saved", { rate = "" }) {
                    vm.rateRefresh.saveManualRate(Rate(base, quote, dayText, dayText, value.storage(), "Manual"))
                }
            }, enabled = base != quote && rate.isNotBlank()) { Text(tr("Save rate")) }
        } }
        if (manual.isNotEmpty()) item { Text(tr("Saved manual rates"), style = MaterialTheme.typography.titleLarge) }
        items(manual, key = { "${it.base}/${it.target}/${it.date}" }) { value ->
            Panel(Modifier.fillMaxWidth()) {
                Text("1 ${value.base} = ${value.value} ${value.target}")
                Text("${LocalDate.parse(value.date).shortDate()} · ${tr(value.source)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
