package dev.sharedledger.domain

import dev.sharedledger.data.Entry
import dev.sharedledger.presentation.GroupBy
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class TransactionGroupsTest {
    private val day = LocalDate.of(2026, 11, 18)
    private val groceries = entry("grocery", amount = "2500", payer = "alex", note = "Weekly market")
    private val usdPurchase = entry("coffee", amount = "20", currency = "USD", budgetAmount = "2300",
        account = "debit", payer = "alex", note = "Coffee", rateSource = "Reference")
    private val salary = entry("salary", EntryType.INCOME, "47500", category = "salary", account = "debit", payer = "jo")
    private val older = entry("older", amount = "2400", date = day.minusDays(1), payer = "jo")
    private val transfer = entry("transfer", EntryType.TRANSFER, "30", currency = "USD", budgetAmount = null,
        category = null, account = "debit", payer = "alex").copy(destinationId = "cash", destinationCurrency = "RSD", destinationAmount = "3450")
    private val rows = listOf(groceries, usdPurchase, salary, transfer, older)

    @Test fun dateGroupsKeepBothDirectionsAndUseStoredValuations() {
        val groups = groups(GroupBy.DATE)
        val current = groups.getValue(day.toString())
        assertEquals(4, current.entries.size)
        assertEquals(BigDecimal("4800"), current.totals.expenses)
        assertEquals(BigDecimal("47500"), current.totals.income)
        assertTrue(current.totals.approximateExpenses)
        assertFalse(current.totals.approximateIncome)
        assertTrue(current.transfersByCurrency.isEmpty())
        val earlier = groups.getValue(day.minusDays(1).toString())
        assertEquals(BigDecimal("2400"), earlier.totals.expenses)
        assertEquals(1, earlier.totals.outgoing.count)
        assertEquals(0, earlier.totals.incoming.count)
    }

    @Test fun categoryGroupsKeepIncomeAndExpenseCategoriesDistinct() {
        val groups = groups(GroupBy.CATEGORY)
        val food = groups.getValue("food").totals
        assertEquals(BigDecimal("7200"), food.expenses)
        assertEquals(0, food.incoming.count)
        val payroll = groups.getValue("salary").totals
        assertEquals(BigDecimal("47500"), payroll.income)
        assertEquals(0, payroll.outgoing.count)
        assertEquals(mapOf("USD" to BigDecimal("30")), groups.getValue("transfer").transfersByCurrency)
    }

    @Test fun accountAndPayerGroupsUseSourceAccountAndRecordedPayer() {
        val accounts = groups(GroupBy.ACCOUNT)
        assertEquals(3, accounts.getValue("debit").entries.size)
        assertEquals(BigDecimal("2300"), accounts.getValue("debit").totals.expenses)
        assertEquals(BigDecimal("47500"), accounts.getValue("debit").totals.income)
        assertEquals(2, accounts.getValue("cash").entries.size)
        assertEquals(BigDecimal("4900"), accounts.getValue("cash").totals.expenses)
        val payers = groups(GroupBy.PAYER)
        assertEquals(3, payers.getValue("alex").entries.size)
        assertEquals(BigDecimal("4800"), payers.getValue("alex").totals.expenses)
        assertEquals(BigDecimal("47500"), payers.getValue("jo").totals.income)
        assertEquals(BigDecimal("2400"), payers.getValue("jo").totals.expenses)
        assertEquals(rows.size, payers.values.sumOf { it.entries.size })
        assertEquals("unassigned", groups(GroupBy.PAYER, listOf(groceries.copy(id = "unassigned", payer = null)))
            .getValue("").entries.single().id)
    }

    @Test fun pendingAndZeroValuationsRemainDistinguishableInEachDirection() {
        val pendingIncome = salary.copy(budgetAmount = null)
        val otherBudgetCurrency = older.copy(date = day.toString(), budgetCurrency = "EUR", budgetAmount = "100")
        val result = groups(GroupBy.DATE, listOf(groceries.copy(budgetAmount = "0"), pendingIncome, otherBudgetCurrency))
            .values.single().totals
        assertEquals(2, result.pending)
        assertFalse(result.incoming.hasAmount)
        assertEquals(1, result.incoming.count)
        assertTrue(result.outgoing.hasAmount)
        assertEquals(BigDecimal.ZERO, result.expenses)
        assertEquals(1, result.outgoing.pending)
    }

    @Test fun transferOnlyGroupsTotalSourceCurrenciesWithoutAddingDestinationLegs() {
        val second = entry("transfer2", EntryType.TRANSFER, "2400", budgetAmount = null, category = null)
            .copy(destinationId = "debit", destinationCurrency = "USD", destinationAmount = "20")
        val visible = filterEntries(rows + second, DateSpan(day, day), Filters(type = EntryType.TRANSFER), emptyMap(), "RSD")
        val group = groups(GroupBy.DATE, visible).values.single()
        assertEquals(2, group.entries.size)
        assertEquals(Totals(), group.totals)
        assertEquals(mapOf("USD" to BigDecimal("30"), "RSD" to BigDecimal("2400")), group.transfersByCurrency)
        val incomingToCash = filterEntries(visible, DateSpan(), Filters(account = "cash", currency = "USD"), emptyMap(), "RSD")
        assertEquals(listOf(transfer), groups(GroupBy.ACCOUNT, incomingToCash).getValue("debit").entries)
    }

    @Test fun filteredGroupsUseOnlyMatchingRowsAndRecomputeWhenTheDateRangeChanges() {
        val filters = Filters(category = "food", account = "debit", payer = "alex", type = EntryType.EXPENSE,
            currency = "USD", minimum = BigDecimal("2000"), maximum = BigDecimal("3000"), query = "coffee")
        val entries = rows + usdPurchase.copy(id = "older-coffee", date = day.minusDays(1).toString(), budgetAmount = "2600")
        fun summary(date: LocalDate) = groups(GroupBy.ACCOUNT,
            filterEntries(entries, DateSpan(date, date), filters, mapOf("food" to "Food"), "RSD")).getValue("debit")
        assertEquals(listOf(usdPurchase), summary(day).entries)
        assertEquals(BigDecimal("2300"), summary(day).totals.expenses)
        assertEquals(BigDecimal("2600"), summary(day.minusDays(1)).totals.expenses)
        assertEquals(2, filterEntries(entries, DateSpan(), filters.copy(query = "FOOD"), mapOf("food" to "Food"), "RSD").size)
    }

    @Test fun groupsHandleLargeListsAndEmptyOrDeletedHistory() {
        val many = List(10_000) { groceries.copy(id = "expense-$it") }
        val result = groups(GroupBy.DATE, many).values.single()
        assertEquals(10_000, result.entries.size)
        assertEquals(BigDecimal("25000000"), result.totals.expenses)
        assertTrue(groups(GroupBy.DATE, emptyList()).isEmpty())
        assertTrue(groups(GroupBy.CATEGORY, rows.map { it.copy(deletedAt = 1) }).isEmpty())
    }

    private fun groups(mode: GroupBy, entries: List<Entry> = rows) =
        transactionGroups(entries, "RSD", mode::key).associateBy { it.key }

    private fun entry(id: String, type: EntryType = EntryType.EXPENSE, amount: String,
                      currency: String = "RSD", budgetAmount: String? = amount, category: String? = "food",
                      account: String = "cash", payer: String? = null, date: LocalDate = day,
                      note: String = "", rateSource: String = "Same currency") =
        Entry(id, "budget", account, category, type, amount, currency, currency, "RSD", date.toString(),
            budgetAmount = budgetAmount, budgetRateSource = rateSource, note = note, payer = payer)
}
