package dev.sharedledger.domain

import dev.sharedledger.data.Account
import dev.sharedledger.data.Category
import dev.sharedledger.data.Entry
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class FinancialHistoryTest {
    private val november = LocalDate.of(2026, 11, 1)
    private val today = november.plusDays(4)
    private val zone = ZoneId.of("Europe/Belgrade")
    private val month = Period(PeriodMode.MONTH, november)
    private val cash = account("cash", "RSD", "100")
    private val usd = account("usd", "USD", "10")
    private val groceries = Category("groceries", "budget", "Groceries", EntryType.EXPENSE, "basket", 1)
    private val transport = groceries.copy(id = "transport", name = "Transport", color = 2)
    private val salary = groceries.copy(id = "salary", name = "Salary", type = EntryType.INCOME, color = 3)

    @Test fun balancesIncludePrePeriodActivityBothTransferLegsAndMissingDays() {
        val rows = listOf(
            row("previous", november.minusDays(2), EntryType.INCOME, accountAmount = "50"),
            row("outgoing", november, accountAmount = "200"),
            row("incoming", november, EntryType.INCOME, accountAmount = "20"),
            row("usd-transfer", november.plusDays(2), EntryType.TRANSFER, "usd", accountAmount = "1")
                .copy(destinationId = "cash", destinationCurrency = "RSD", destinationAmount = "100"),
            row("missing", november.plusDays(2), accountAmount = null),
            row("rsd-transfer", november.plusDays(3), EntryType.TRANSFER, accountAmount = "25")
                .copy(destinationId = "usd", destinationCurrency = "USD", destinationAmount = "0.20"),
            row("deleted", november.plusDays(3), accountAmount = "900").copy(deletedAt = 1),
            row("future", today.plusDays(1), accountAmount = "900")
        )
        val histories = accountHistory(month, listOf(cash, usd), rows, zone, today)
        assertEquals(setOf("RSD", "USD"), histories.keys)
        val rsd = histories.getValue("RSD")
        assertTrue(rsd.buckets.first().opening)
        assertEquals(today, rsd.buckets.last().through)
        assertEquals(listOf("150", "-30", "-30", "70", "45", "45"), values(rsd.series.single()))
        assertEquals(listOf(0, 0, 0, 1, 1, 1), rsd.series.single().values.map(HistoryValue::pending))
        assertEquals(listOf("10", "10", "10", "9", "9.2", "9.2"), values(histories.getValue("USD").series.single()))
        assertEquals(accountBalance(cash, rows, today).value, rsd.series.single().values.last().amount)
    }

    @Test fun accountHistoryDoesNotInventBalancesBeforeAnAccountExisted() {
        // This UTC time is already November 3 in Belgrade.
        val opened = november.plusDays(1).atTime(23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        val later = cash.copy(id = "new", createdAt = opened, opening = "75")
        val data = accountHistory(month, listOf(cash, later), emptyList(), zone, today).getValue("RSD")
        assertEquals(listOf(null, null, null, "75", "75", "75"), values(data.series.last()))
        val older = row("imported", november.minusDays(2), accountId = "new", accountAmount = "25")
        val imported = accountHistory(month, listOf(later), listOf(older), zone, today).getValue("RSD")
        assertEquals("50", values(imported.series.single()).first())
    }

    @Test fun categoryHistoryUsesBudgetAmountsAndSeparatesZerosUnknownAndPartialTotals() {
        val rows = listOf(
            row("local", november, accountAmount = "25", budgetAmount = "2500"),
            row("offline", november, accountId = "usd", budgetAmount = "1700").copy(currency = "USD", payer = "alex"),
            row("transport", november.plusDays(1), budgetAmount = "300").copy(categoryId = transport.id),
            row("unknown", november.plusDays(2), budgetAmount = null),
            row("known", november.plusDays(3), budgetAmount = "10"),
            row("wrong-currency", november.plusDays(3), budgetAmount = "300").copy(budgetCurrency = "EUR"),
            row("reference", today, accountId = "usd", budgetAmount = "15").copy(currency = "USD", budgetRateSource = "Reference"),
            row("income", november.plusDays(1), EntryType.INCOME, budgetAmount = "75000").copy(categoryId = salary.id),
            row("transfer", november, EntryType.TRANSFER, budgetAmount = "55000"),
            row("deleted", november, budgetAmount = "85000").copy(deletedAt = 1),
        ).reversed()
        val data = categoryHistory(month, listOf(groceries, transport), rows, "RSD", EntryType.EXPENSE, today)
        val result = data.series.first().values
        assertEquals(listOf("4200", "0", null, "10", "15"), values(data.series.first()))
        assertEquals(listOf(0, 0, 1, 1, 0), result.map(HistoryValue::pending))
        assertTrue(result.last().approximate)
        assertEquals(listOf("0", "300", "0", "0", "0"), values(data.series.last()))
        val incoming = categoryHistory(month, listOf(salary), rows, "RSD", EntryType.INCOME, today)
        assertEquals(listOf("0", "75000", "0", "0", "0"), values(incoming.series.single()))
        val filtered = filterEntries(rows, month.span, Filters(account = "usd", payer = "alex"), emptyMap(), "RSD")
        val scoped = categoryHistory(month, listOf(groceries), filtered, "RSD", EntryType.EXPENSE, today)
        assertEquals(listOf("1700", "0", "0", "0", "0"), values(scoped.series.single()))
    }

    @Test fun longHistoriesUseWholeCalendarBucketsAndSingleDaysRemainSingle() {
        val end = LocalDate.of(2026, 12, 19)
        val quarter = historyBuckets(Period(PeriodMode.THREE_MONTHS, end), null, end)
        assertEquals(LocalDate.of(2026, 10, 1), quarter.first().from)
        assertEquals(LocalDate.of(2026, 10, 4), quarter.first().through)
        assertEquals(end, quarter.last().through)
        val year = historyBuckets(Period(PeriodMode.YEAR, end), null, end)
        assertEquals(12, year.size)
        assertEquals(LocalDate.of(2026, 2, 28), year[1].through)
        val extended = historyBuckets(Period(PeriodMode.ALL), LocalDate.of(1980, 1, 1), end)
        assertTrue(extended.size in 2..60)
        assertTrue(extended.zipWithNext().all { (a, b) -> a.through.plusDays(1) == b.from })
        val custom = Period(PeriodMode.CUSTOM, custom = DateSpan(LocalDate.of(2024, 2, 28), LocalDate.of(2024, 3, 1)))
        assertEquals(LocalDate.of(2024, 2, 29), historyBuckets(custom, null, end)[1].through)
        assertTrue(historyBuckets(Period(PeriodMode.DAY, end.plusDays(1)), null, end).isEmpty())
        assertEquals(listOf(HistoryBucket(end, end)), historyBuckets(Period(PeriodMode.DAY, end), null, end))
        assertTrue(categoryHistory(Period(PeriodMode.ALL), listOf(groceries), emptyList(), "RSD",
            EntryType.EXPENSE, today).buckets.isEmpty())
    }

    @Test fun weeklyAndMonthlyBucketsCloseBalancesAndSumPeriodicActivity() {
        val end = november.plusMonths(1).plusDays(19)
        val events = listOf(row("first", november, accountAmount = "20", budgetAmount = "20"),
            row("second", november.plusDays(1), accountAmount = "10", budgetAmount = "10"),
            row("third", november.plusMonths(1), accountAmount = "10", budgetAmount = "10"))
        val weekly = Period(PeriodMode.CUSTOM, custom = DateSpan(november, end))
        val yearly = Period(PeriodMode.YEAR, end)
        for (period in listOf(weekly, yearly)) {
            val balances = accountHistory(period, listOf(cash), events, zone, end).getValue("RSD")
            val expenses = categoryHistory(period, listOf(groceries), events, "RSD", EntryType.EXPENSE, end)
            assertEquals("60", values(balances.series.single()).last())
            assertEquals("40", expenses.series.single().values.sumByAmount())
            assertEquals(end, balances.buckets.last().through)
            assertEquals(end, expenses.buckets.last().through)
        }
    }

    private fun List<HistoryValue>.sumByAmount() = mapNotNull(HistoryValue::amount)
        .fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add).storage()

    private fun values(series: HistorySeries) = series.values.map { it.amount?.storage() }

    private fun account(id: String, currency: String, opening: String) = Account(id, "budget", id, currency, opening = opening,
        createdAt = november.minusMonths(2).atStartOfDay(zone).toInstant().toEpochMilli())

    private fun row(id: String, day: LocalDate, type: EntryType = EntryType.EXPENSE, accountId: String = "cash",
                    accountAmount: String? = "1", budgetAmount: String? = "1") =
        Entry(id, "budget", accountId, groceries.id, type, "1", "RSD", if (accountId == "usd") "USD" else "RSD",
            "RSD", day.toString(), accountAmount = accountAmount, budgetAmount = budgetAmount)
}
