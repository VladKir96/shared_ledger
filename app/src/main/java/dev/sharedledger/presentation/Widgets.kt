package dev.sharedledger.presentation

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.sharedledger.data.*
import dev.sharedledger.domain.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun Enum<*>.label(): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }

fun LocalDate.shortDate(): String = format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LedgerFormats.locale))
fun Period.title(): String = when (mode) {
    PeriodMode.ALL -> "All time"
    PeriodMode.MONTH -> anchor.format(DateTimeFormatter.ofPattern("LLLL yyyy", LedgerFormats.locale)).replaceFirstChar { it.titlecase() }
    PeriodMode.YEAR -> anchor.year.toString()
    PeriodMode.DAY, PeriodMode.SPECIFIC -> anchor.shortDate()
    else -> "${span.from?.shortDate().orEmpty()} – ${span.to?.shortDate().orEmpty()}"
}

@Composable
fun <T> Select(label: String, current: T, options: List<T>, describe: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    var show by remember { mutableStateOf(false) }
    val context = LocalContext.current
    OutlinedButton(onClick = { show = true }, modifier = modifier, shape = RoundedCornerShape(12.dp), enabled = options.isNotEmpty()) {
        Column(Modifier.weight(1f)) {
            Text(tr(label), style = MaterialTheme.typography.labelSmall)
            Text(tr(describe(current)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
    }
    if (show) Dialog(onDismissRequest = { show = false }) {
        var query by remember { mutableStateOf("") }
        Panel(Modifier.fillMaxWidth().heightIn(max = 524.dp)) {
            Text(tr(label), style = MaterialTheme.typography.titleLarge)
            if (options.size > 10) Field(tr("Search"), query, { query = it })
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(options.filter { translated(context, describe(it)).contains(query, true) }) { option ->
                    ListItem(
                        headlineContent = { Text(tr(describe(option))) },
                        trailingContent = { if (option == current) Icon(Icons.Outlined.Check, "Selected") },
                        modifier = Modifier.clickable { onSelect(option); show = false }
                    )
                }
            }
            TextButton({ show = false }, Modifier.align(Alignment.End)) { Text(tr("Cancel")) }
        }
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = modifier.fillMaxWidth(), singleLine = singleLine,
        shape = RoundedCornerShape(12.dp))
}

@Composable
fun DateButton(label: String, date: LocalDate, modifier: Modifier = Modifier, onDate: (LocalDate) -> Unit) {
    val context = LocalContext.current
    OutlinedButton({
        DatePickerDialog(context, { _, year, month, day -> onDate(LocalDate.of(year, month + 1, day)) },
            date.year, date.monthValue - 1, date.dayOfMonth).show()
    }, modifier, shape = RoundedCornerShape(12.dp)) {
        Icon(Icons.Outlined.CalendarToday, null, Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(listOf(tr(label), date.shortDate()).filter { it.isNotBlank() }.joinToString(" "), maxLines = 1)
    }
}

@Composable
fun PeriodControl(period: Period, onPeriod: (Period) -> Unit, extended: Boolean = false) {
    var show by remember { mutableStateOf(false) }
    var mode by remember(period.mode) { mutableStateOf(period.mode) }
    var from by remember(period) { mutableStateOf(period.span.from ?: period.anchor) }
    var to by remember(period) { mutableStateOf(period.span.to ?: period.anchor) }
    val modes = PeriodMode.entries.filter { extended || it !in listOf(PeriodMode.THREE_MONTHS, PeriodMode.SIX_MONTHS) }
    Surface(
        modifier = Modifier.fillMaxWidth().pointerInput(period) {
            var drag = 0f
            detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                if (kotlin.math.abs(drag) > 45.dp.toPx()) onPeriod(period.shift(if (drag < 0) 1 else -1))
            }) { change, distance -> change.consume(); drag += distance }
        }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ onPeriod(period.shift(-1)) }, enabled = period.mode != PeriodMode.ALL) { Icon(Icons.Outlined.ChevronLeft, "Previous period") }
            TextButton({ show = true }, Modifier.weight(1f)) {
                Icon(Icons.Outlined.CalendarToday, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr(period.title()), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton({ onPeriod(period.shift(1)) }, enabled = period.mode != PeriodMode.ALL) { Icon(Icons.Outlined.ChevronRight, "Next period") }
        }
    }
    if (show) AlertDialog(
        onDismissRequest = { show = false }, title = { Text(tr("Date range")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Select("Period", mode, modes, { if (it == PeriodMode.SPECIFIC) "Specific date" else it.label() }, Modifier.fillMaxWidth()) { mode = it }
                if (mode != PeriodMode.ALL) DateButton(if (mode == PeriodMode.CUSTOM) "From" else "", from, Modifier.fillMaxWidth()) { from = it }
                if (mode == PeriodMode.CUSTOM) DateButton("Through", to, Modifier.fillMaxWidth()) { to = it }
            }
        },
        confirmButton = {
            TextButton({
                onPeriod(Period(mode, from, if (mode == PeriodMode.CUSTOM) DateSpan(from, to) else DateSpan()))
                show = false
            }, enabled = mode != PeriodMode.CUSTOM || from <= to) { Text(tr("Apply")) }
        }, dismissButton = { TextButton({ show = false }) { Text(tr("Cancel")) } }
    )
}

@Composable
fun EntryRow(entry: Entry, ledger: Ledger, budgetCurrency: String = entry.budgetCurrency, onClick: (() -> Unit)? = null) {
    val category = ledger.categories.find { it.id == entry.categoryId }
    val account = ledger.accounts.find { it.id == entry.accountId }
    val dest = ledger.accounts.find { it.id == entry.destinationId }
    val payer = ledger.members.find { it.userId == entry.payer }?.name
    val isTransfer = entry.type == EntryType.TRANSFER
    val interaction = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(Modifier.fillMaxWidth().then(interaction).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CategoryMark(category?.icon ?: if (isTransfer) "transfer" else "more", category?.color ?: 0xFF719681)
        Column(Modifier.weight(1f)) {
            Text(category?.name ?: if (isTransfer) "Transfer" else "Uncategorized", fontWeight = FontWeight.SemiBold, maxLines = 1)
            val subtitle = listOfNotNull(account?.name, dest?.name, payer, entry.note.takeIf { it.isNotBlank() }).joinToString(" · ")
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            val amounts = transactionAmounts(entry, budgetCurrency)
            TransactionAmountBlock(amounts, entry.type, budgetCurrency)
            Text(LocalDate.parse(entry.date).shortDate(), style = MaterialTheme.typography.labelSmall)
            if (!isTransfer && entry.accountAmount == null && amounts.primary != null) {
                Text("Account conversion pending", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun TransactionAmountBlock(amounts: TransactionAmounts, type: EntryType, budget: String) {
    val sign = when (type) { EntryType.EXPENSE -> "−"; EntryType.INCOME -> "+"; EntryType.TRANSFER -> "" }
    val color = when (type) {
        EntryType.EXPENSE -> MaterialTheme.colorScheme.error
        EntryType.INCOME -> MaterialTheme.colorScheme.primary
        EntryType.TRANSFER -> MaterialTheme.colorScheme.onSurface
    }
    val primary = amounts.primary
    Text(primary?.let { (if (it.approximate) "≈ " else "") + sign + money(it.value, it.currency) } ?: "$budget · Pending",
        color = color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    if (amounts.secondary.isNotEmpty()) {
        Text(amounts.secondary.joinToString(" · ") { (if (it.approximate) "≈ " else "") + sign + money(it.value, it.currency) },
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
    }
}

fun valuationMoney(value: BigDecimal, currency: String, approximate: Boolean) =
    (if (approximate) "≈ " else "") + money(value, currency)
