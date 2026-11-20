package dev.sharedledger.domain

import dev.sharedledger.data.Account
import dev.sharedledger.data.Entry
import java.math.BigDecimal
import java.time.LocalDate

data class Totals(val income: BigDecimal = BigDecimal.ZERO, val expenses: BigDecimal = BigDecimal.ZERO, val pending: Int = 0) {
    val net: BigDecimal get() = income.subtract(expenses)
}

fun totals(entries: List<Entry>, currency: String): Totals {
    var income = BigDecimal.ZERO
    var expenses = BigDecimal.ZERO
    var pending = 0
    entries.forEach { entry ->
        if (entry.deletedAt != null || entry.type == EntryType.TRANSFER) return@forEach
        val amount = entry.budgetAmount?.takeIf { entry.budgetCurrency == currency }?.decimal()
        when {
            amount == null -> pending++
            entry.type == EntryType.INCOME -> income += amount
            else -> expenses += amount
        }
    }
    return Totals(income, expenses, pending)
}

data class Balance(val value: BigDecimal, val pending: Int)

fun accountBalance(account: Account, entries: List<Entry>, through: LocalDate? = null): Balance {
    var value = account.opening.decimal()
    var pending = 0
    entries.forEach { entry ->
        if (entry.deletedAt != null || through != null && entry.date > through.toString()) return@forEach
        if (entry.accountId == account.id) {
            val amount = entry.accountAmount?.decimal()
            if (amount == null) pending++ else value += if (entry.type == EntryType.INCOME) amount else amount.negate()
        } else if (entry.destinationId == account.id) {
            val amount = entry.destinationAmount?.decimal()
            if (amount == null) pending++ else value += amount
        }
    }
    return Balance(value, pending)
}

data class Filters(
    val category: String? = null, val account: String? = null, val payer: String? = null,
    val type: EntryType? = null, val currency: String? = null, val minimum: BigDecimal? = null,
    val maximum: BigDecimal? = null, val query: String = "", val byAmount: Boolean = false
)

fun filterEntries(entries: List<Entry>, span: DateSpan, filters: Filters, categories: Map<String, String>, budgetCurrency: String): List<Entry> {
    val lower = span.from?.toString()
    val upper = span.to?.toString()
    val matching = entries.filter { entry ->
        val amount = entry.budgetAmount?.takeIf { entry.budgetCurrency == budgetCurrency }?.decimal()
        entry.deletedAt == null && (lower == null || entry.date >= lower) && (upper == null || entry.date <= upper) &&
            (filters.category == null || filters.category == entry.categoryId) &&
            (filters.account == null || filters.account == entry.accountId || filters.account == entry.destinationId) &&
            (filters.payer == null || filters.payer == entry.payer) &&
            (filters.type == null || filters.type == entry.type) &&
            (filters.currency == null || filters.currency == entry.currency) &&
            (filters.minimum == null || amount != null && amount >= filters.minimum) &&
            (filters.maximum == null || amount != null && amount <= filters.maximum) &&
            (filters.query.isBlank() || entry.note.contains(filters.query, true) ||
                categories[entry.categoryId]?.contains(filters.query, true) == true)
    }
    return if (filters.byAmount) matching.sortedWith(compareByDescending<Entry> {
        it.budgetAmount?.takeIf { _ -> it.budgetCurrency == budgetCurrency }?.decimal()
    }.thenByDescending { it.date }) else matching.sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.createdAt })
}

fun spendingBy(entries: List<Entry>, currency: String, type: EntryType = EntryType.EXPENSE, key: (Entry) -> String?): Map<String?, BigDecimal> {
    val result = mutableMapOf<String?, BigDecimal>()
    entries.forEach { entry ->
        val amount = entry.budgetAmount?.takeIf { entry.budgetCurrency == currency && entry.type == type && entry.deletedAt == null }
            ?.decimal() ?: return@forEach
        result.merge(key(entry), amount, BigDecimal::add)
    }
    return result.toList().sortedByDescending { it.second }.toMap()
}
