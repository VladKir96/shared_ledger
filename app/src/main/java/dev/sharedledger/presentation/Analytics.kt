package dev.sharedledger.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.sharedledger.data.Entry
import dev.sharedledger.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit

private data class Point(val label: String, val a: BigDecimal, val b: BigDecimal = BigDecimal.ZERO,
                         val approximateA: Boolean = false, val approximateB: Boolean = false)
private data class Report(
    val trends: List<Point>, val months: List<Point>, val memberSpend: List<Point>, val accountSpend: List<Point>,
    val balances: List<Point>, val categoryHistory: List<Point>, val averageDay: BigDecimal, val averageMonth: BigDecimal,
    val largest: Entry?, val largestCategory: String?, val difference: BigDecimal?, val approximateDifference: Boolean
)

@Composable
fun AnalyticsScreen(state: LedgerState, vm: LedgerViewModel, drill: (EntryType, String?) -> Unit) {
    val budget = state.budget ?: return
    val entries = state.entries
    val accounts = state.ledger.accounts
    val categories = state.ledger.categories
    val names = remember(categories) { categories.associate { it.id to it.name } }
    val members = remember(state.ledger.members) { state.ledger.members.associate { it.userId to it.name } }
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    var categoryId by remember(budget.id, categories) { mutableStateOf(state.filters.category ?: categories.firstOrNull { it.type == EntryType.EXPENSE }?.id) }
    var accountId by remember(budget.id, accounts) { mutableStateOf(state.filters.account ?: accounts.firstOrNull()?.id) }
    val currency = budget.currency
    val report by produceState<Report?>(null, state, categoryId, accountId) {
        value = withContext(Dispatchers.Default) {
            val boundary = state.period.span
            val fallback = LocalDate.now()
            val from = boundary.from ?: entries.minOfOrNull { LocalDate.parse(it.date) } ?: fallback
            val to = boundary.to ?: entries.maxOfOrNull { LocalDate.parse(it.date) } ?: fallback
            val days = ChronoUnit.DAYS.between(from, to).coerceAtLeast(0) + 1
            val months = ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)).coerceAtLeast(0) + 1
            val bucketDays = ((days + 19) / 20).coerceAtLeast(1)
            val daily = entries.groupBy { ChronoUnit.DAYS.between(from, LocalDate.parse(it.date)) / bucketDays }
            val trends = (0L until (days + bucketDays - 1) / bucketDays).map { index ->
                val sums = totals(daily[index].orEmpty(), currency)
                Point(from.plusDays(index * bucketDays).shortDate(), sums.expenses, approximateA = sums.approximateExpenses)
            }
            val firstMonth = YearMonth.from(from)
            val monthSize = ((months + 23) / 24).coerceAtLeast(1)
            val monthGroups = entries.groupBy { ChronoUnit.MONTHS.between(firstMonth, YearMonth.from(LocalDate.parse(it.date))) / monthSize }
            val buckets = (0L until (months + monthSize - 1) / monthSize).map { index ->
                val start = firstMonth.plusMonths(index * monthSize)
                val end = firstMonth.plusMonths(minOf(months, (index + 1) * monthSize) - 1)
                Triple(index, start, end)
            }
            fun monthLabel(start: YearMonth, end: YearMonth): String {
                fun name(value: YearMonth) = value.month.getDisplayName(TextStyle.SHORT, LedgerFormats.locale) + " " + value.year
                return if (start == end) name(start) else "${name(start)} – ${name(end)}"
            }
            val bars = buckets.map { (index, start, end) ->
                val sums = totals(monthGroups[index].orEmpty(), currency)
                Point(monthLabel(start, end), sums.income, sums.expenses, sums.approximateIncome, sums.approximateExpenses)
            }
            val history = buckets.map { (index, start, end) ->
                val sums = totals(monthGroups[index].orEmpty().filter { it.categoryId == categoryId }, currency)
                Point(monthLabel(start, end), sums.expenses, approximateA = sums.approximateExpenses)
            }
            val account = accounts.find { it.id == accountId }
            val balance = if (account == null) emptyList() else buckets.map { (_, start, end) ->
                Point(monthLabel(start, end), accountBalance(account, state.ledger.entries, minOf(to, end.atEndOfMonth())).value)
            }
            val past = totals(state.comparison, currency)
            val delta = if (state.period.mode == PeriodMode.ALL || past.expenses.signum() == 0 ||
                past.pending > 0 || state.totals.pending > 0) null else
                state.totals.expenses.subtract(past.expenses).divide(past.expenses, MathContext.DECIMAL64).movePointRight(2)
            val memberEstimates = estimatedGroups(entries, currency, EntryType.EXPENSE) { it.payer }
            val accountEstimates = estimatedGroups(entries, currency, EntryType.EXPENSE) { it.accountId }
            val memberSpend = spendingBy(entries, currency) { it.payer }.map { (key, sum) ->
                Point(members[key] ?: "Unassigned", sum, approximateA = key in memberEstimates)
            }
            val accountSpend = spendingBy(entries, currency) { it.accountId }.map { (key, sum) ->
                Point(accountNames[key] ?: "Account", sum, approximateA = key in accountEstimates)
            }
            Report(trends, bars, memberSpend, accountSpend,
                balance, history, state.totals.expenses.divide(BigDecimal.valueOf(days), MathContext.DECIMAL64),
                state.totals.expenses.divide(BigDecimal.valueOf(months), MathContext.DECIMAL64),
                entries.filter { it.type == EntryType.EXPENSE && it.budgetCurrency == currency && it.budgetAmount != null }
                    .maxByOrNull { it.budgetAmount!!.decimal() },
                spendingBy(entries, currency) { it.categoryId }.keys.firstOrNull()?.let(names::get), delta,
                past.approximateExpenses || state.totals.approximateExpenses)
        }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp, 14.dp, 20.dp, 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(tr("Analytics"), style = MaterialTheme.typography.headlineLarge) }
        item { PeriodControl(state.period, { vm.period.value = it }, true) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Select("Account filter", state.filters.account, listOf(null) + accountNames.keys, { accountNames[it] ?: "All accounts" },
                    Modifier.weight(1f)) { vm.filters.value = state.filters.copy(account = it) }
                Select("Member filter", state.filters.payer, listOf(null) + members.keys, { members[it] ?: "All members" },
                    Modifier.weight(1f)) { vm.filters.value = state.filters.copy(payer = it) }
            }
        }
        item { Panel(Modifier.fillMaxWidth()) {
            Text(tr("Period summary"), style = MaterialTheme.typography.titleLarge)
            Measure(tr("Income"), valuationMoney(state.totals.income, currency, state.totals.approximateIncome))
            Measure(tr("Expenses"), valuationMoney(state.totals.expenses, currency, state.totals.approximateExpenses))
            Measure(tr("Net cash flow"), valuationMoney(state.totals.net, currency, state.totals.approximate))
            if (state.totals.approximate) Text("Charts include estimated budget conversions.", style = MaterialTheme.typography.bodySmall)
            report?.let {
                Measure(tr("Average daily expenses"), valuationMoney(it.averageDay, currency, state.totals.approximateExpenses))
                Measure(tr("Average monthly expenses"), valuationMoney(it.averageMonth, currency, state.totals.approximateExpenses))
                it.largest?.let { row -> Measure(tr("Largest expense"), valuationMoney(row.budgetAmount!!.decimal(), currency, row.isBudgetApproximate())) }
                it.largestCategory?.let { name -> Measure(tr("Most expensive category"), name) }
                it.difference?.let { value ->
                    val percent = java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(value)
                    Measure(tr("Change versus previous period"), (if (it.approximateDifference) "≈ " else "") +
                        (if (value.signum() > 0) "+" else "") + "$percent%")
                }
            }
            if (state.totals.pending > 0) Text("Partial totals · ${state.totals.pending} missing conversions", color = MaterialTheme.colorScheme.error)
        } }
        item { Distribution(entries, currency, categories, drill) }
        report?.let {
            item { ChartPanel(tr("Expense trends"), it.trends, currency) }
            item { ChartPanel(tr("Income vs. expenses"), it.months, currency, comparisons = true) }
            if (members.isNotEmpty()) item { Ranked(tr("Member contributions"), it.memberSpend, currency) }
            item { Ranked(tr("Spending by account"), it.accountSpend, currency) }
            item { Panel(Modifier.fillMaxWidth()) {
                Text(tr("Account balance history"), style = MaterialTheme.typography.titleLarge)
                Select("Account", accountId, accounts.map { item -> item.id }, { key -> accountNames[key] ?: "Select an account" }, Modifier.fillMaxWidth()) { accountId = it }
                val selected = accounts.find { item -> item.id == accountId }
                if (selected != null) {
                    Chart(it.balances, selected.currency, false)
                    Text("Includes opening balance and account activity through each date.", style = MaterialTheme.typography.bodySmall)
                }
            } }
            item { Panel(Modifier.fillMaxWidth()) {
                Text(tr("Category history"), style = MaterialTheme.typography.titleLarge)
                Select("Category", categoryId, categories.filter { item -> item.type == EntryType.EXPENSE }.map { item -> item.id },
                    { key -> names[key] ?: "Select a category" }, Modifier.fillMaxWidth()) { categoryId = it }
                Chart(it.categoryHistory, currency, false)
            } }
        }
    }
}

@Composable
private fun Measure(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Ranked(title: String, points: List<Point>, currency: String) {
    Panel(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (points.isEmpty()) Text("No spending in this period.")
        val max = points.maxOfOrNull { it.a }?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
        points.forEach {
            Measure(it.label, valuationMoney(it.a, currency, it.approximateA))
            LinearProgressIndicator(progress = { normalized(it.a, max) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ChartPanel(title: String, points: List<Point>, currency: String, comparisons: Boolean = false) {
    Panel(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (comparisons) Text("Income in green · Expenses in coral", style = MaterialTheme.typography.labelMedium)
        Chart(points, currency, comparisons)
    }
}

@Composable
private fun Chart(points: List<Point>, currency: String, comparisons: Boolean) {
    if (points.isEmpty()) { EmptyMessage("No chart data", "Record transactions to see this report.", Icons.Outlined.BarChart); return }
    var index by remember(points) { mutableIntStateOf(points.lastIndex) }
    val selected = points[index]
    val selectedAmount = valuationMoney(selected.a, currency, selected.approximateA) +
        if (comparisons) " / ${valuationMoney(selected.b, currency, selected.approximateB)}" else ""
    val high = maxOf(BigDecimal.ZERO, points.maxOf { maxOf(it.a, it.b) })
    val low = minOf(BigDecimal.ZERO, points.minOf { minOf(it.a, it.b) })
    val magnitude = (high - low).takeIf { it.signum() > 0 } ?: BigDecimal.ONE
    val tint = MaterialTheme.colorScheme.primary
    val expense = MaterialTheme.colorScheme.error
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(124.dp).semantics {
        contentDescription = "${selected.label}, $selectedAmount"
    }.pointerInput(points) {
        detectTapGestures { pos -> index = ((pos.x / size.width) * points.size).toInt().coerceIn(points.indices) }
    }) {
        val lane = size.width / points.size
        val bottom = size.height - 4.dp.toPx()
        fun y(amount: BigDecimal): Float = bottom * (1f - normalized(amount - low, magnitude))
        val zero = y(BigDecimal.ZERO)
        drawLine(grid, Offset.Zero.copy(y = zero), Offset(size.width, zero), strokeWidth = 1.dp.toPx())
        var previous: Offset? = null
        points.forEachIndexed { i, p ->
            val x = lane * (i + .5f)
            val value = y(p.a)
            if (comparisons) {
                val second = y(p.b)
                drawRect(tint.copy(alpha = if (i == index) 1f else .55f), Offset(x - lane * .32f, value), Size(lane * .28f, (zero - value).coerceAtLeast(1f)))
                drawRect(expense.copy(alpha = if (i == index) 1f else .55f), Offset(x + lane * .04f, second), Size(lane * .28f, (zero - second).coerceAtLeast(1f)))
            } else {
                val point = Offset(x, value)
                previous?.let { drawLine(tint, it, point, strokeWidth = 2.dp.toPx()) }
                drawCircle(tint, if (i == index) 5.dp.toPx() else 2.dp.toPx(), point)
                previous = point
            }
        }
    }
    Measure(selected.label, selectedAmount)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton({ index-- }, enabled = index > 0) { Text(tr("Previous point")) }
        TextButton({ index++ }, enabled = index < points.lastIndex) { Text(tr("Next point")) }
    }
}

private fun normalized(amount: BigDecimal, total: BigDecimal) = amount.divide(total, MathContext.DECIMAL64).toFloat().coerceIn(0f, 1f)
