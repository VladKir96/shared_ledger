package dev.sharedledger.domain

import dev.sharedledger.data.Account
import dev.sharedledger.data.Category
import dev.sharedledger.data.Entry
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

internal data class HistoryBucket(val from: LocalDate, val through: LocalDate, val opening: Boolean = false)
internal data class HistoryValue(val amount: BigDecimal?, val pending: Int = 0, val approximate: Boolean = false)
internal data class HistorySeries(val id: String, val name: String, val color: Long, val values: List<HistoryValue>)
internal data class FinancialHistory(val buckets: List<HistoryBucket>, val series: List<HistorySeries>)

internal fun historyBuckets(period: Period, earliest: LocalDate?, today: LocalDate): List<HistoryBucket> {
    val span = period.span
    val start = span.from ?: earliest ?: return emptyList()
    val end = minOf(span.to ?: today, today)
    if (start > end) return emptyList()
    val days = ChronoUnit.DAYS.between(start, end) + 1
    val months = ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(end)) + 1
    val monthStep = (months + 59) / 60
    // Transactions carry a local calendar date. Creation metadata is not the time of the transaction.
    return buildList {
        var day = start
        while (true) {
            val boundary = when (period.mode) {
                PeriodMode.DAY, PeriodMode.SPECIFIC, PeriodMode.WEEK, PeriodMode.MONTH -> day
                PeriodMode.THREE_MONTHS, PeriodMode.SIX_MONTHS ->
                    day.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                PeriodMode.YEAR, PeriodMode.ALL -> YearMonth.from(day).plusMonths(monthStep - 1).atEndOfMonth()
                PeriodMode.CUSTOM -> when {
                    days <= 45 -> day
                    days <= 184 -> day.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                    else -> YearMonth.from(day).plusMonths(monthStep - 1).atEndOfMonth()
                }
            }
            val close = minOf(end, boundary)
            add(HistoryBucket(day, close))
            if (close == end) break
            day = close.plusDays(1)
        }
    }
}

internal fun accountHistory(
    period: Period,
    accounts: List<Account>,
    entries: List<Entry>,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone)
): Map<String, FinancialHistory> {
    if (accounts.isEmpty()) return emptyMap()
    val selected = accounts.mapTo(mutableSetOf(), Account::id)
    val activity = mutableMapOf<String, MutableList<Pair<LocalDate, Balance>>>()
    entries.forEach { entry ->
        if (entry.deletedAt != null) return@forEach
        for (account in listOfNotNull(entry.accountId, entry.destinationId?.takeUnless { it == entry.accountId })) {
            if (account !in selected) continue
            val day = LocalDate.parse(entry.date)
            if (day <= today) activity.getOrPut(account, ::mutableListOf)
                .add(day to requireNotNull(accountMovement(account, entry)))
        }
    }
    fun firstKnownDay(account: Account): LocalDate {
        val created = Instant.ofEpochMilli(account.createdAt).atZone(zone).toLocalDate()
        return minOf(created, activity[account.id]?.minOfOrNull { it.first } ?: created)
    }
    return accounts.groupBy(Account::currency).mapValues { (_, group) ->
        val closing = historyBuckets(period, group.minOf(::firstKnownDay), today)
        if (closing.isEmpty()) FinancialHistory(emptyList(), emptyList()) else {
            val first = closing.first().from
            val opening = HistoryBucket(first, first, opening = true)
            val buckets = listOf(opening) + closing
            val series = group.map { account ->
                val start = firstKnownDay(account)
                val movements = activity[account.id].orEmpty().sortedBy { it.first }
                var index = 0
                var amount = account.opening.decimal()
                var pending = 0
                val values = buckets.map { bucket ->
                    while (index < movements.size) {
                        val (day, delta) = movements[index]
                        if (day > bucket.through || bucket.opening && day == bucket.through) break
                        amount += delta.value
                        pending += delta.pending
                        index++
                    }
                    if (bucket.through < start) HistoryValue(null) else HistoryValue(amount, pending)
                }
                HistorySeries(account.id, account.name, account.color, values)
            }
            FinancialHistory(buckets, series.filter { row -> row.values.any { it.amount != null } })
        }
    }
}

internal fun categoryHistory(
    period: Period,
    categories: List<Category>,
    entries: List<Entry>,
    currency: String,
    type: EntryType,
    today: LocalDate = LocalDate.now()
): FinancialHistory {
    val selected = categories.filter { it.type == type && it.deletedAt == null }
    val ids = selected.mapTo(mutableSetOf(), Category::id)
    if (ids.isEmpty() || type == EntryType.TRANSFER) return FinancialHistory(emptyList(), emptyList())
    val activity = entries.asSequence()
        .filter { it.deletedAt == null && it.type == type && it.categoryId in ids }
        .map { LocalDate.parse(it.date) to it }
        .filter { (date) -> date <= today }.toList()
    val buckets = historyBuckets(period, activity.minOfOrNull { it.first }, today)
    if (buckets.isEmpty()) return FinancialHistory(emptyList(), emptyList())
    val groups = activity.asSequence().filter { (date) -> date >= buckets.first().from && date <= buckets.last().through }
        .groupBy({ (date, entry) ->
            val result = buckets.binarySearchBy(date) { it.through }
            (if (result < 0) -result - 1 else result) to entry.categoryId
        }, { it.second })
    val series = selected.map { category ->
        val values = buckets.indices.map { index ->
            val sums = totals(groups[index to category.id].orEmpty(), currency)
            val total = if (type == EntryType.INCOME) sums.incoming else sums.outgoing
            HistoryValue(total.amount.takeIf { total.pending == 0 || total.hasAmount }, total.pending, total.approximate)
        }
        HistorySeries(category.id, category.name, category.color, values)
    }
    return FinancialHistory(buckets, series)
}
