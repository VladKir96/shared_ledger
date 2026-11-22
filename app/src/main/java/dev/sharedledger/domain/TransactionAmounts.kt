package dev.sharedledger.domain

import dev.sharedledger.data.BankSource
import dev.sharedledger.data.Entry
import java.math.BigDecimal

data class MonetaryLine(val value: BigDecimal, val currency: String, val approximate: Boolean = false)
data class TransactionAmounts(val primary: MonetaryLine?, val secondary: List<MonetaryLine>)

fun Entry.isBudgetApproximate(): Boolean = budgetAmount != null && currency != budgetCurrency &&
    budgetRateSource?.startsWith("Manual") != true

fun transactionAmounts(entry: Entry, selectedCurrency: String): TransactionAmounts {
    if (entry.type == EntryType.TRANSFER) return TransactionAmounts(
        MonetaryLine(entry.amount.decimal(), entry.currency),
        listOfNotNull(entry.destinationAmount?.let { MonetaryLine(it.decimal(), requireNotNull(entry.destinationCurrency)) })
    )
    val value = when {
        entry.budgetCurrency == selectedCurrency && entry.budgetAmount != null ->
            MonetaryLine(entry.budgetAmount.decimal(), selectedCurrency, entry.isBudgetApproximate())
        entry.currency == selectedCurrency -> MonetaryLine(entry.amount.decimal(), selectedCurrency)
        else -> null
    }
    val source = entry.bankConversion?.source
    val approximateAccount = entry.currency != entry.accountCurrency && source != BankSource.BANK_ACTUAL &&
        source != BankSource.MANUAL_RATE && entry.accountRateSource?.startsWith("Manual") != true
    val account = entry.accountAmount?.let { MonetaryLine(it.decimal(), entry.accountCurrency, approximateAccount) }
    val original = MonetaryLine(entry.amount.decimal(), entry.currency)
    return transactionAmounts(value, account, original)
}

fun transactionAmounts(primary: MonetaryLine?, account: MonetaryLine?, original: MonetaryLine): TransactionAmounts {
    val secondary = buildList<MonetaryLine> {
        for (line in listOfNotNull(account, original)) {
            if (primary?.sameValue(line) != true && none { it.sameValue(line) }) add(line)
        }
    }
    return TransactionAmounts(primary, secondary)
}

private fun MonetaryLine.sameValue(other: MonetaryLine): Boolean =
    currency == other.currency && value.compareTo(other.value) == 0
