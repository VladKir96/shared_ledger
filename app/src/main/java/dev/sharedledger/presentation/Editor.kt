package dev.sharedledger.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sharedledger.data.*
import dev.sharedledger.domain.*
import java.time.LocalDate
import java.math.RoundingMode

private enum class BudgetInput { RATE, AMOUNT, REFERENCE }

@Composable
fun EditorScreen(state: LedgerState, vm: LedgerViewModel, type: EntryType, entry: Entry?, duplicate: Boolean, feeAccount: String? = null, navigateBack: () -> Unit) {
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
    var accountId by rememberSaveable { mutableStateOf(entry?.accountId ?: feeAccount ?: state.preferences.lastAccount
        ?.takeIf { last -> accounts.any { it.id == last } } ?: accounts.firstOrNull()?.id) }
    if (accounts.none { it.id == accountId }) accountId = accounts.firstOrNull()?.id
    val account = accounts.find { it.id == accountId }
    var categoryId by rememberSaveable { mutableStateOf(entry?.categoryId ?: feeAccount?.let {
        categories.firstOrNull { category -> category.name == "Taxes & Fees" }?.id
    }) }
    var dateText by rememberSaveable { mutableStateOf(if (duplicate) LocalDate.now().toString() else entry?.date ?: LocalDate.now().toString()) }
    val date = LocalDate.parse(dateText)
    var currencyOverride by rememberSaveable { mutableStateOf(entry?.currency) }
    val currency = if (type == EntryType.TRANSFER) account?.currency ?: budget.currency else currencyOverride ?: account?.currency ?: budget.currency
    var expression by rememberSaveable { mutableStateOf(entry?.amount.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(entry?.note ?: if (feeAccount != null) "Bank fee" else "") }
    var payer by rememberSaveable { mutableStateOf(entry?.payer ?: vm.repository.principal.value.takeIf { budget.principal != null }) }
    var destinationId by rememberSaveable { mutableStateOf(entry?.destinationId) }
    val destinations = accounts.filter { it.id != accountId }
    val destination = destinations.find { it.id == destinationId }
    var received by rememberSaveable { mutableStateOf(entry?.destinationAmount.orEmpty()) }
    var showRates by rememberSaveable { mutableStateOf(false) }
    var manualAccount by rememberSaveable { mutableStateOf("") }
    var budgetInput by rememberSaveable { mutableStateOf<BudgetInput?>(null) }
    var budgetText by rememberSaveable { mutableStateOf("") }
    val editingActual = editing && entry?.bankConversion?.source == BankSource.BANK_ACTUAL
    val editingEntered = editing && entry?.bankConversion?.source == BankSource.ENTERED_AMOUNT
    var enteredAmount by rememberSaveable { mutableStateOf(entry?.accountAmount.takeIf { editingActual || editingEntered }) }
    var bankConfirmed by rememberSaveable { mutableStateOf(editingActual) }
    var keepAmount by rememberSaveable { mutableStateOf(false) }
    var inverseRate by rememberSaveable { mutableStateOf(true) }
    val pair = account?.takeIf { type != EntryType.TRANSFER && it.currency != currency }?.let {
        BankPair(budget.id, it.id, currency, it.currency, type)
    }
    val estimate = observeBankOffer(pair, date, entry?.id.takeIf { editing }, state.ledger.entries, vm)
    val budgetPair = account?.takeIf { type != EntryType.TRANSFER && currency != budget.currency }?.let {
        BudgetRatePair(budget.id, it.id, currency, budget.currency, type)
    }
    val previous = entry.takeIf { editing }
    val manualBudget = budgetInput == BudgetInput.RATE || budgetInput == BudgetInput.AMOUNT
    val useReference = budgetInput == BudgetInput.REFERENCE
    val budgetSuggestion = observeBudgetRate(budgetPair, date, previous, state.ledger.entries, useReference, manualBudget, vm)
    val budgetValuation = when (budgetInput) {
        BudgetInput.RATE -> parseValuationRate(budgetText)?.let { BudgetValuationEdit.Rate(it) }
        BudgetInput.AMOUNT -> parseMoney(budgetText)?.let { BudgetValuationEdit.Amount(it) }
        BudgetInput.REFERENCE -> BudgetValuationEdit.Reference
        null -> null
    }

    fun clearBankInput() {
        enteredAmount = null
        bankConfirmed = false
        keepAmount = false
    }

    fun clearBudgetInput() {
        budgetInput = null
        budgetText = ""
    }

    fun originalAmount(value: java.math.BigDecimal) = previous?.takeIf {
        it.currency == currency && it.amount.decimal().compareTo(value) == 0
    }?.amount?.decimal() ?: value.forCurrency(currency)

    fun changeOriginal(value: String) {
        if (value == expression) return
        val oldValue = calculate(expression)
        val newValue = calculate(value)
        if (budgetInput == BudgetInput.AMOUNT && oldValue != null && oldValue.compareTo(newValue ?: java.math.BigDecimal.ZERO) != 0) {
            budgetValuation?.manualConversion(originalAmount(oldValue), budget.currency, date)?.let {
                budgetInput = BudgetInput.RATE
                budgetText = it.rate
            }
        }
        if (!keepAmount && oldValue?.compareTo(newValue ?: java.math.BigDecimal.ZERO) != 0) clearBankInput()
        expression = value
    }

    var delete by remember { mutableStateOf(false) }
    val result = remember(expression) { calculate(expression) }
    val liveResult = result ?: remember(expression) { calculate(expression.trimEnd('+', '−', '×', '÷')) }
    val allowed = budget.role != Role.VIEWER
    val busy by vm.busy.collectAsState()
    val original = liveResult?.let(::originalAmount)?.takeIf { it.isBudgetMagnitude() }
    val recordedBudgetRate = budgetPair?.recorded(previous, date)?.takeUnless { useReference || manualBudget }
    val budgetPreview = original?.let { value ->
        when {
            manualBudget -> budgetValuation?.manualConversion(value, budget.currency, date)
            recordedBudgetRate != null && previous != null && value.compareTo(previous.amount.decimal()) == 0 ->
                Conversion(requireNotNull(previous.budgetAmount), recordedBudgetRate.value, recordedBudgetRate.date, recordedBudgetRate.source)
            else -> budgetSuggestion.quote?.budgetConversion(value, budget.currency)
        }
    }
    val budgetError = original != null && (
        (manualBudget || budgetSuggestion.quote != null) && budgetPreview == null ||
            recordedBudgetRate != null && previous?.budgetRateConfirmedAt != null && budgetPreview?.amount?.decimal()?.signum() == 0
        )
    val manualQuote = parseMoney(manualAccount)?.takeIf { it.signum() > 0 }?.let { RateQuote(it.storage(), dateText, "Manual") }
    val exactStored = entry?.takeIf { editing && it.accountId == accountId && it.currency == currency && it.date == dateText &&
        original?.compareTo(it.amount.decimal()) == 0 && manualQuote == null }
    val suggestion = pair?.let {
        exactStored?.accountAmount ?: original?.let { amount ->
            if (manualQuote != null) manualQuote.convert(amount, it.accountCurrency).takeIf { value -> value.decimal().signum() > 0 }
            else estimate?.amount(amount, it.accountCurrency)?.storage()
        }
    }
    val shownAccount = enteredAmount ?: suggestion.orEmpty()
    val validEntered = pair?.let { scope ->
        parseMoney(shownAccount)?.takeIf { it.signum() > 0 &&
            runCatching { it.setScale(currencyScale(scope.accountCurrency), RoundingMode.UNNECESSARY) }.isSuccess }
    }
    val correctionNeedsConfirmation = editingActual && exactStored != null && enteredAmount != null &&
        validEntered?.compareTo(exactStored.accountAmount?.decimal() ?: java.math.BigDecimal.ZERO) != 0 && !bankConfirmed
    val canSave = allowed && !busy && result?.let(::originalAmount)?.isBudgetMagnitude() == true && account != null &&
        !budgetError && (!useReference || budgetPreview != null) &&
        (!correctionNeedsConfirmation && (enteredAmount == null || pair == null || validEntered != null)) &&
        (if (type == EntryType.TRANSFER) destination != null else categoryId != null)

    fun submit() {
        val selectedAccount = account ?: return
        val amount = result ?: return
        val accountRate = parseMoney(manualAccount)
        if (manualAccount.isNotBlank() && accountRate?.signum() != 1) {
            vm.notice("Enter a positive decimal exchange rate.")
            return
        }
        val draft = EntryDraft(type, amount, date, selectedAccount.id, categoryId, currency, note, payer,
            destinationId, parseMoney(received), accountRate, budgetValuation,
            validEntered.takeIf { pair != null && enteredAmount != null }, bankConfirmed && pair != null)
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
                        if (pair == null && budgetPair == null) Text(expression.ifBlank { "0" }, color = Color.White, style = MaterialTheme.typography.headlineLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else BasicTextField(expression, { value -> if (value.length <= 96) changeOriginal(value.replace(',', '.')) },
                            modifier = Modifier.weight(3f).testTag("transaction-amount").semantics { contentDescription = "Transaction amount in $currency" },
                            textStyle = MaterialTheme.typography.headlineLarge.copy(color = Color.White, textAlign = TextAlign.End),
                            cursorBrush = SolidColor(Mint), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterEnd) {
                                    if (expression.isBlank()) Text("0", color = Mint, style = MaterialTheme.typography.headlineLarge)
                                    inner()
                                }
                            })
                        IconButton({ changeOriginal(expression.dropLast(1)) }) { Icon(Icons.Outlined.Backspace, "Delete last digit", tint = Mint) }
                    }
                    Text(liveResult?.let { money(it, currency) } ?: "Enter an amount", color = Mint, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (pair != null) {
                val source = when {
                    bankConfirmed -> BankSource.BANK_ACTUAL
                    enteredAmount != null -> BankSource.ENTERED_AMOUNT
                    exactStored?.bankConversion != null -> exactStored.bankConversion.source
                    manualQuote != null -> BankSource.MANUAL_RATE
                    exactStored?.accountRateSource?.startsWith(MANUAL_RATE) == true -> BankSource.MANUAL_RATE
                    exactStored?.accountRateDate == dateText -> BankSource.REFERENCE_API
                    exactStored?.accountAmount != null -> BankSource.CACHED_RATE
                    else -> estimate?.conversion?.source ?: BankSource.PENDING
                }
                val sourceDay = exactStored?.accountRateDate ?: estimate?.quote?.date
                Panel(Modifier.fillMaxWidth()) {
                    Text("Transaction amount in $currency", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(shownAccount, { enteredAmount = it; bankConfirmed = false }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("bank-account-amount"),
                        label = { Text(if (type == EntryType.INCOME) "Account credit in ${pair.accountCurrency}" else "Account debit in ${pair.accountCurrency}") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = enteredAmount != null && validEntered == null)
                    val priorConfirmation = exactStored?.bankConversion?.priorBankConfirmationAt ?: estimate?.conversion?.priorBankConfirmationAt
                    Text(bankSourceLabel(source, sourceDay, priorConfirmation), style = MaterialTheme.typography.bodySmall)
                    if (original != null && validEntered != null) {
                        Text(bankRateText(original, validEntered, currency, pair.accountCurrency, inverseRate),
                            Modifier.testTag("effective-bank-rate"), style = MaterialTheme.typography.titleMedium)
                        TextButton({ inverseRate = !inverseRate }) { Text("Swap rate direction") }
                    }
                    if (correctionNeedsConfirmation) Text("Confirm the corrected bank amount before saving.", color = MaterialTheme.colorScheme.error)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(bankConfirmed, { checked ->
                            if (checked) enteredAmount = validEntered?.storage()
                            bankConfirmed = checked
                        }, enabled = allowed && original != null && validEntered != null && !(editingActual && bankConfirmed && exactStored != null),
                            modifier = Modifier.testTag("confirm-bank-amount"))
                        Text("Confirm actual bank amount")
                    }
                    if (enteredAmount != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(keepAmount, { keepAmount = it })
                            Text("Keep account amount when changing transaction amount")
                        }
                        if (!editingActual || exactStored == null) TextButton(::clearBankInput) { Text("Use estimate") }
                    }
                    Text("Record a separately posted bank fee as a separate expense.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (budgetPair != null) {
                Panel(Modifier.fillMaxWidth()) {
                    Text("Budget valuation", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        if (budgetInput == BudgetInput.RATE) budgetText else
                            budgetPreview?.rate ?: budgetSuggestion.quote?.value.orEmpty().takeUnless { manualBudget }.orEmpty(),
                        { if (it.length <= 80) { budgetInput = BudgetInput.RATE; budgetText = it } },
                        Modifier.fillMaxWidth().testTag("budget-rate"), enabled = allowed, singleLine = true,
                        label = { Text("Rate in ${budgetPair.budget} per ${budgetPair.original}") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = budgetError && budgetInput == BudgetInput.RATE
                    )
                    OutlinedTextField(
                        if (budgetInput == BudgetInput.AMOUNT) budgetText else budgetPreview?.amount.orEmpty(),
                        { if (it.length <= 28) { budgetInput = BudgetInput.AMOUNT; budgetText = it } },
                        Modifier.fillMaxWidth().testTag("budget-amount"), enabled = allowed, singleLine = true,
                        label = { Text("Budget amount in ${budgetPair.budget}") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = budgetError && budgetInput == BudgetInput.AMOUNT
                    )
                    val quote = budgetSuggestion.quote
                    val status = when {
                        budgetError -> "Enter a positive budget amount within the currency’s precision and supported range."
                        manualBudget -> "User-defined · Save to remember this rate for this account and transaction type."
                        quote != null -> "${budgetRateLabel(quote.source)} · ${LocalDate.parse(quote.date).shortDate()}"
                        budgetSuggestion.checking -> "Checking exchange rates…"
                        useReference -> "No reference rate is available for this date."
                        else -> "Conversion pending"
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall,
                        color = if (budgetError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (allowed && !useReference && (manualBudget || quote?.source.isManualBudgetRate() || recordedBudgetRate != null))
                        TextButton({ budgetInput = BudgetInput.REFERENCE; budgetText = "" }) {
                            Text("Use reference rate")
                        }
                    if (allowed && budgetInput != null) TextButton(::clearBudgetInput) { Text("Use saved or suggested rate") }
                }
            } else if (account != null && type != EntryType.TRANSFER && account.currency != budget.currency) {
                Panel(Modifier.fillMaxWidth()) {
                    Text("Budget valuation", style = MaterialTheme.typography.titleMedium)
                    original?.let { Text(money(it, budget.currency)) }
                    Text("Transaction already in ${budget.currency}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Calculator(expression, ::changeOriginal)
            val accountNames = accounts.associate { it.id to "${it.name} · ${it.currency}" }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Select(if (type == EntryType.TRANSFER) "From account" else "Account", accountId, accounts.map { it.id },
                    { accountNames[it] ?: "Choose account" }, Modifier.weight(1f)) {
                    accountId = it; currencyOverride = null; manualAccount = ""; clearBudgetInput(); clearBankInput()
                    if (destinationId == it) destinationId = null
                }
                if (type != EntryType.TRANSFER) Select("Currency", currency, currencyCodes, { it }, Modifier.weight(.65f)) {
                    currencyOverride = it; manualAccount = ""; clearBudgetInput(); clearBankInput()
                } else Select("To account", destinationId, destinations.map { it.id }, { accountNames[it] ?: "Choose account" },
                    Modifier.weight(1f)) { destinationId = it; received = "" }
            }
            if (type != EntryType.TRANSFER) {
                val names = categories.associate { it.id to it.name }
                Select("Category", categoryId, categories.map { it.id }, { names[it] ?: "Choose category" }, Modifier.fillMaxWidth()) { categoryId = it }
            } else if (destination != null && destination.currency != currency) {
                Field("Amount received in ${destination.currency}", received, { received = it })
            }
            DateButton("", date, Modifier.fillMaxWidth()) { dateText = it.toString(); manualAccount = ""; clearBudgetInput() }
            if (state.ledger.members.isNotEmpty()) {
                val members = state.ledger.members.associate { it.userId to it.name }
                Select("Paid by", payer, state.ledger.members.filter { it.active || it.userId == payer }.map { it.userId },
                    { members[it] ?: "Select payer" }, Modifier.fillMaxWidth()) { payer = it }
            }
            Field(tr("Note (optional)"), note, { if (it.length <= 2000) note = it }, singleLine = false)
            if (pair != null) {
                TextButton({ showRates = !showRates }) {
                    Text(tr(if (showRates) "Hide manual rate" else "Manual exchange rate"))
                }
                if (showRates) Field("Manual $currency → ${pair.accountCurrency}", manualAccount, { manualAccount = it })
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
