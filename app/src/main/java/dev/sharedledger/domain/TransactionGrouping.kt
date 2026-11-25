package dev.sharedledger.domain

import dev.sharedledger.data.Entry
import java.math.BigDecimal
import java.text.Collator
import java.util.Locale

enum class GroupBy {
    DATE, CATEGORY, ACCOUNT, PAYER;

    fun key(entry: Entry): String = when (this) {
        DATE -> entry.date
        CATEGORY -> when (entry.type) {
            EntryType.TRANSFER -> TRANSFERS
            EntryType.INCOME -> entry.categoryId ?: "uncategorized-income"
            EntryType.EXPENSE -> entry.categoryId ?: "uncategorized-expense"
        }
        ACCOUNT -> entry.accountId
        PAYER -> entry.payer.orEmpty()
    }

    val sortOptions get() = if (this == DATE) dateSorts else namedSorts

    fun compatibleSort(sort: GroupSort): GroupSort = sort.takeIf { it in sortOptions } ?: sortOptions.first()

    companion object {
        const val TRANSFERS = "transfer"
        private val dateSorts = listOf(GroupSort.NEWEST, GroupSort.OLDEST, GroupSort.AMOUNT_HIGH, GroupSort.AMOUNT_LOW)
        private val namedSorts = listOf(GroupSort.NAME_ASC, GroupSort.NAME_DESC, GroupSort.AMOUNT_HIGH, GroupSort.AMOUNT_LOW)
    }
}

enum class GroupSort { NEWEST, OLDEST, AMOUNT_HIGH, AMOUNT_LOW, NAME_ASC, NAME_DESC }

fun sortTransactionGroups(
    groups: List<TransactionGroup>,
    mode: GroupBy,
    sort: GroupSort,
    names: Map<String, String> = emptyMap(),
    locale: Locale = LedgerFormats.locale
): List<TransactionGroup> {
    val byNewest = compareByDescending(TransactionGroup::key)
    return when (mode.compatibleSort(sort)) {
        GroupSort.NEWEST -> groups.sortedWith(byNewest)
        GroupSort.OLDEST -> groups.sortedBy(TransactionGroup::key)
        GroupSort.NAME_ASC, GroupSort.NAME_DESC -> groups.sortedWith(byName(names, locale, sort))
        GroupSort.AMOUNT_HIGH, GroupSort.AMOUNT_LOW -> {
            val amountOrder = if (sort == GroupSort.AMOUNT_HIGH) reverseOrder<BigDecimal>() else naturalOrder()
            val byAmount = compareBy<TransactionGroup, BigDecimal?>(nullsLast(amountOrder)) { group ->
                val direction = if (mode == GroupBy.CATEGORY && categoryOrder(group) == 0)
                    group.totals.incoming else group.totals.outgoing
                when {
                    direction.count == 0 -> BigDecimal.ZERO
                    direction.hasAmount -> direction.amount.abs()
                    else -> null
                }
            }
            when (mode) {
                GroupBy.CATEGORY -> groups.sortedWith(compareBy(::categoryOrder).then(byAmount).then(byName(names, locale, sort)))
                GroupBy.DATE -> groups.sortedWith(byAmount.then(byNewest))
                GroupBy.ACCOUNT, GroupBy.PAYER -> groups.sortedWith(byAmount.then(byName(names, locale, sort)))
            }
        }
    }
}

private fun categoryOrder(group: TransactionGroup): Int = when {
    group.totals.outgoing.count != 0 -> 1
    group.totals.incoming.count != 0 -> 0
    else -> 2
}

private fun byName(names: Map<String, String>, locale: Locale, sort: GroupSort): Comparator<TransactionGroup> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    val order = compareBy<TransactionGroup, String>(collator) { names[it.key] ?: it.key }
    return (if (sort == GroupSort.NAME_DESC) order.reversed() else order).thenBy(TransactionGroup::key)
}
