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
fun observeBankOffer(pair: BankPair?, day: LocalDate, except: String?, entries: List<Entry>, vm: LedgerViewModel): BankOffer? {
    val online = onlineRates(vm)
    var offer by remember(pair, day, except, entries, online) { mutableStateOf<BankOffer?>(null) }
    LaunchedEffect(pair, day, except, entries, online) {
        if (pair != null) {
            vm.rates.observe(pair.currency, setOf(pair.accountCurrency), day, allowOlder = true).collect {
                offer = withContext(Dispatchers.IO) { vm.repository.bankRates.offer(pair, day, except, offline = !online) }
            }
        }
    }
    LaunchedEffect(pair, day, online) {
        val today = LocalDate.now()
        if (pair != null && online && day <= today) withContext(Dispatchers.IO) {
            val prior = vm.repository.bankRates.offer(pair, day, except)
            if (prior?.conversion?.source != BankSource.BANK_STALE)
                vm.rates.ensure(pair.currency, setOf(pair.accountCurrency), day, today)
        }
    }
    return offer
}

internal data class BudgetRateSuggestion(val quote: RateQuote?, val checking: Boolean)

@Composable
internal fun observeBudgetRate(
    pair: BudgetRatePair?, day: LocalDate, previous: Entry?, entries: List<Entry>,
    referenceOnly: Boolean, manualInput: Boolean, vm: LedgerViewModel
): BudgetRateSuggestion {
    val online = onlineRates(vm)
    val today = LocalDate.now()
    val offline = !online && day <= today
    val saved = pair?.recorded(previous, day)?.takeUnless { referenceOnly }
    var quote by remember(pair, day, previous, entries, referenceOnly, offline) { mutableStateOf(saved) }
    var checking by remember(pair, day, previous, entries, referenceOnly, offline, manualInput) { mutableStateOf(false) }
    LaunchedEffect(pair, day, previous, entries, referenceOnly, offline) {
        if (pair != null && saved == null) {
            vm.rates.observe(pair.original, setOf(pair.budget), day, offline, includeManual = !referenceOnly).collect {
                quote = withContext(Dispatchers.IO) { vm.repository.budgetRate(pair, day, previous, referenceOnly, offline) }
            }
        }
    }
    LaunchedEffect(pair, day, previous, entries, referenceOnly, online, manualInput) {
        if (pair != null && saved == null && !manualInput && online && day <= today) {
            try {
                val available = withContext(Dispatchers.IO) {
                    vm.repository.budgetRate(pair, day, previous, referenceOnly, offline = false)
                }
                if (available == null) {
                    checking = true
                    withContext(Dispatchers.IO) {
                        vm.rates.ensure(pair.original, setOf(pair.budget), day, today, includeManual = !referenceOnly)
                    }
                }
            } finally {
                checking = false
            }
        }
    }
    return BudgetRateSuggestion(quote, checking)
}

internal fun budgetRateLabel(source: String) = when {
    source == USER_DEFINED_BUDGET_RATE || source == LAST_CONFIRMED_BUDGET_RATE || source == "Same currency" -> source
    source.startsWith(MANUAL_RATE) -> "Configured manual rate"
    source.startsWith(CACHED_BUDGET_RATE_PREFIX) -> "Cached reference rate · ${source.removePrefix(CACHED_BUDGET_RATE_PREFIX)}"
    source.startsWith("Bank") || source == "Entered account amount" -> source
    else -> "Reference API rate · $source"
}

fun bankSourceLabel(source: BankSource, date: String? = null, priorConfirmation: Long? = null): String = when (source) {
    BankSource.BANK_ACTUAL -> "Bank actual · Confirmed"
    BankSource.BANK_ESTIMATED -> "Bank estimate" + (date?.let { " · Reference $it" } ?: "")
    BankSource.BANK_STALE -> "Last bank rate · Estimated" + (priorConfirmation?.let {
        " · Confirmed ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().shortDate()}"
    } ?: date?.let { " · $it" }.orEmpty())
    BankSource.REFERENCE_API -> "Reference API · Estimated"
    BankSource.CACHED_RATE -> "Cached reference rate · Estimated" + (date?.let { " · $it" } ?: "")
    BankSource.MANUAL_RATE -> "Manual rate · Unconfirmed"
    BankSource.ENTERED_AMOUNT -> "Entered account amount · Unconfirmed"
    BankSource.PENDING -> "Conversion pending"
}

fun bankRateText(original: BigDecimal, account: BigDecimal, base: String, target: String, inverse: Boolean): String {
    val forward = effectiveBankRate(original, account)
    val rate = if (inverse) inverseBankRate(forward) else forward
    val rounded = rate.setScale(4, java.math.RoundingMode.HALF_EVEN)
    val label = if (rounded.signum() > 0) rounded.storage() else rate.round(java.math.MathContext(4)).storage()
    return if (inverse) "1 $target = $label $base" else "1 $base = $label $target"
}

@Composable
fun RecordedExchangeRates(entry: Entry, currency: String) {
    if (entry.type == EntryType.TRANSFER) return
    val primary = transactionAmounts(entry, currency).primary
    Text("Budget amount · " + (primary?.let { (if (it.approximate) "≈ " else "") + money(it.value, it.currency) } ?: "Pending ($currency)"))
    Text("Original amount · ${money(entry.amount.decimal(), entry.currency)}")
    val leg = if (entry.type == EntryType.INCOME) "Account credit" else "Account debit"
    Text("$leg · " + (entry.accountAmount?.let { money(it.decimal(), entry.accountCurrency) } ?: "Pending (${entry.accountCurrency})"))
    val bank = entry.bankConversion
    if (entry.currency != entry.accountCurrency) {
        if (bank != null) {
            Text(bankSourceLabel(bank.source, entry.accountRateDate, bank.priorBankConfirmationAt), style = MaterialTheme.typography.bodySmall)
            entry.accountAmount?.decimal()?.takeIf { it.signum() > 0 }?.let {
                Text("Effective account rate · " + bankRateText(entry.amount.decimal(), it, entry.currency, entry.accountCurrency, true))
            }
            bank.reference?.let { Text("Bank reference · 1 ${entry.currency} = ${it.value} ${entry.accountCurrency} · ${it.date} · ${it.source}",
                style = MaterialTheme.typography.bodySmall) }
        } else entry.accountRateSource?.let { Text("Recorded account rate · $it · ${entry.accountRateDate.orEmpty()}",
            style = MaterialTheme.typography.bodySmall) }
    }
    if (entry.currency != entry.budgetCurrency && entry.budgetRate != null) {
        Text("Budget valuation rate · 1 ${entry.currency} = ${entry.budgetRate} ${entry.budgetCurrency}",
            style = MaterialTheme.typography.bodySmall)
        Text("Rate source · ${entry.budgetRateSource?.let(::budgetRateLabel).orEmpty()} · ${entry.budgetRateDate.orEmpty()}",
            style = MaterialTheme.typography.bodySmall)
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
        item { LastBankRatesPanel(state, vm, today) }
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


@Composable
private fun LastBankRatesPanel(state: LedgerState, vm: LedgerViewModel, today: LocalDate) {
    val accounts = state.ledger.accounts.associateBy(Account::id)
    val pairs = state.ledger.entries.asSequence().filter { it.bankConversion?.source == BankSource.BANK_ACTUAL &&
        it.bankConversion.learningEligible && accounts.containsKey(it.accountId) }
        .map { BankPair(it.budgetId, it.accountId, it.currency, it.accountCurrency, it.type) }.distinct().toList()
    var chosen by remember(state.budget?.id) { mutableStateOf<BankPair?>(null) }
    val selected = chosen?.takeIf { it in pairs } ?: pairs.firstOrNull()
    val latest by produceState<BankObservation?>(null, selected, state.ledger.entries) {
        value = if (selected == null) null else withContext(Dispatchers.IO) {
            vm.repository.bankRates.observations(selected, today).firstOrNull()
        }
    }
    Panel(Modifier.fillMaxWidth()) {
        Text("Last confirmed bank rates", style = MaterialTheme.typography.titleMedium)
        if (selected == null) {
            Text("Confirm a cross-currency debit or credit to use its rate on your next transaction.", style = MaterialTheme.typography.bodySmall)
        } else {
            Select("Account and currency pair", selected, pairs, { pair ->
                "${accounts[pair.account]?.name.orEmpty()} · ${pair.currency} → ${pair.accountCurrency} · ${pair.direction.label()}"
            }, Modifier.fillMaxWidth()) { chosen = it }
            latest?.let {
                Text("1 ${selected.currency} = ${it.rate.round(java.math.MathContext(8)).storage()} ${selected.accountCurrency}")
                Text("Transaction ${it.day.shortDate()} · Confirmed ${Instant.ofEpochMilli(it.confirmedAt).atZone(ZoneId.systemDefault()).toLocalDate().shortDate()}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun RecurringAmount(rule: Rule, account: Account?, budget: String, entries: List<Entry>, vm: LedgerViewModel) {
    val day = rule.nextDate() ?: LocalDate.parse(rule.start)
    val original = MonetaryLine(rule.amount.decimal(), rule.currency)
    val offline = !onlineRates(vm) && day <= LocalDate.now()
    val amounts by produceState(transactionAmounts(original.takeIf { rule.currency == budget }, null, original),
        rule, account, budget, day, entries, offline) {
        val targets = setOfNotNull(budget, account?.currency).filterTo(mutableSetOf()) { it != rule.currency }
        vm.rates.observe(rule.currency, targets, day, offline).collect {
            val primary = if (rule.currency == budget) original else account?.let {
                val pair = BudgetRatePair(rule.budgetId, it.id, rule.currency, budget, rule.type)
                vm.repository.budgetRate(pair, day, null, useReference = false, offline = offline)?.let { quote ->
                    quote.budgetConversion(original.value, budget)?.let { conversion ->
                        MonetaryLine(conversion.amount.decimal(), budget, !quote.source.isManualBudgetRate())
                    }
                }
            }
            val accountAmount = if (account == null || account.currency == rule.currency) null else {
                val pair = BankPair(rule.budgetId, account.id, rule.currency, account.currency, rule.type)
                vm.repository.bankRates.offer(pair, day)?.let { offer ->
                    offer.amount(original.value, account.currency)?.let { MonetaryLine(it, account.currency, true) }
                }
            }
            value = transactionAmounts(primary, accountAmount, original)
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        TransactionAmountBlock(amounts, rule.type, budget)
    }
}
