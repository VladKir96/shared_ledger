package dev.sharedledger.domain

import dev.sharedledger.data.Account
import dev.sharedledger.data.Entry
import dev.sharedledger.data.csvRows
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FinanceTest {
    @Test fun calculatorPrecedencePrecisionAndErrors() {
        assertEquals("1700", calculate("1200 + 350 + 150")?.storage())
        assertEquals("8.5", calculate("1.5 + 2 × 3 + 4 ÷ 4")?.storage())
        assertEquals("0.3", calculate("0.1 + 0.2")?.storage())
        assertEquals("-1", calculate("-2 + 1")?.storage())
        assertNull(calculate("2 ÷ 0"))
        assertNull(calculate("9 +"))
        assertNull(calculate("1; 2"))
    }

    @Test fun isoDecimalsAndRounding() {
        assertEquals(BigDecimal("2343.40"), BigDecimal("20").multiply(BigDecimal("117.17")).forCurrency("RSD"))
        assertEquals(BigDecimal("12.34"), BigDecimal("12.345").forCurrency("EUR"))
        assertEquals(BigDecimal("12.36"), BigDecimal("12.355").forCurrency("EUR"))
        assertEquals(BigDecimal("24"), BigDecimal("24.40").forCurrency("JPY"))
        assertEquals("12.2", parseMoney("12,20")?.storage())
        assertNull(parseMoney("1.2.3"))
    }

    @Test fun periodNavigationAndMonthEndOccurrences() {
        val jan = LocalDate.parse("2024-01-31")
        assertEquals(LocalDate.parse("2024-02-29"), occurrence(jan, Frequency.MONTHLY, 1, 1))
        assertEquals(LocalDate.parse("2024-03-31"), occurrence(jan, Frequency.MONTHLY, 1, 2))
        assertEquals(LocalDate.parse("2025-02-28"), occurrence(LocalDate.parse("2024-02-29"), Frequency.YEARLY, 1, 1))
        assertEquals(occurrenceId("rule", jan), occurrenceId("rule", jan))
        assertNotEquals(occurrenceId("rule", jan), occurrenceId("rule", jan.plusDays(1)))
        val week = Period(PeriodMode.WEEK, jan).span
        assertEquals(LocalDate.parse("2024-01-29"), week.from)
        assertTrue(jan in week)
        val date = LocalDate.parse("2025-01-15")
        assertEquals("2024-11-01", Period(PeriodMode.THREE_MONTHS, date).span.from.toString())
        val custom = Period(PeriodMode.CUSTOM, jan, DateSpan(jan, jan.plusDays(1))).shift(1)
        assertEquals(jan.plusDays(2), custom.span.from)
    }

    @Test fun totalsFiltersAndTransfers() {
        val account = Account("cash", "budget", "Cash", "RSD", opening = "1000")
        val target = Account("card", "budget", "Card", "EUR", opening = "100")
        val expense = entry("expense", EntryType.EXPENSE, "250")
        val income = entry("income", EntryType.INCOME, "500")
        val pending = entry("pending", EntryType.EXPENSE, null)
        val transfer = entry("transfer", EntryType.TRANSFER, null).copy(accountAmount = "75", destinationId = "card",
            destinationAmount = "0.65", destinationCurrency = "EUR")
        val rows = listOf(expense, income, pending, transfer)
        val totals = totals(rows, "RSD")
        assertEquals("250", totals.net.storage())
        assertEquals(1, totals.pending)
        assertEquals("1175", accountBalance(account, rows).value.storage())
        assertEquals("100.65", accountBalance(target, rows).value.storage())
        assertEquals(mapOf("c" to BigDecimal("250")), spendingBy(rows, "RSD") { it.categoryId })
        val filtered = filterEntries(rows, DateSpan(LocalDate.parse("2025-01-15"), LocalDate.parse("2025-01-15")),
            Filters(account = "cash", query = "grocer", minimum = BigDecimal("200"), maximum = BigDecimal("300")),
            mapOf("c" to "Groceries"), "RSD")
        assertEquals(listOf(expense), filtered)
    }

    @Test fun csvAcceptsQuotesAndMultilineFields() {
        assertEquals(listOf(listOf("date", "note"), listOf("2025-01-15", "First, \"quoted\"\nSecond")),
            csvRows("date,note\r\n2025-01-15,\"First, \"\"quoted\"\"\nSecond\"\r\n"))
        assertThrows(IllegalArgumentException::class.java) { csvRows("date,note\nrow,\"unfinished") }
    }

    private fun entry(id: String, type: EntryType, amount: String?) =
        Entry(id, "budget", "cash", "c", type, amount ?: "1", "RSD", "RSD", "RSD", "2025-01-15",
            accountAmount = amount, budgetAmount = amount)
}
